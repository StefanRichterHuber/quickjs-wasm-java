package io.github.stefanrichterhuber.quickjswasmjava;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.dylibso.chicory.runtime.ExportFunction;
import com.dylibso.chicory.runtime.HostFunction;
import com.dylibso.chicory.runtime.Instance;
import com.dylibso.chicory.runtime.Store;
import com.dylibso.chicory.wasi.WasiOptions;
import com.dylibso.chicory.wasi.WasiPreview1;
import com.dylibso.chicory.wasm.WasmModule;
import com.dylibso.chicory.wasm.types.FunctionType;
import com.dylibso.chicory.wasm.types.ValType;

/**
 * Represents a QuickJS runtime. This is the main entry point for the QuickJS
 * wasm library.
 */
public final class QuickJSRuntime implements AutoCloseable {
    /**
     * Guard for the script duration. Ensures that the script duration is reset
     * when the guard is closed.
     */
    static class ScriptDurationGuard implements AutoCloseable {
        private final QuickJSRuntime runtime;

        public ScriptDurationGuard(QuickJSRuntime runtime) {
            this.runtime = runtime;
            this.runtime.scriptStarted();
        }

        @Override
        public void close() {
            this.runtime.scriptFinished();
        }
    }

    /**
     * Logger for the QuickJSRuntime class.
     */
    private static final Logger LOGGER = LogManager.getLogger(QuickJSRuntime.class);
    /**
     * Logger for the native WasmLib class. All log calls from the native library
     * are redirected to this logger.
     */
    private static final Logger NATIVE_LOGGER = LogManager
            .getLogger("io.github.stefanrichterhuber.quickjswasmjava.native.WasmLib");

    /**
     * Pointer to the runtime in the wasm library.
     */
    private long ptr;
    /**
     * The wasm instance.
     */
    private final Instance instance;
    /**
     * The native alloc function.
     */
    private final ExportFunction alloc;
    /**
     * The native dealloc function.
     */
    private final ExportFunction dealloc;
    /**
     * The native closeRuntime function.
     */
    private final ExportFunction closeRuntime;

    /**
     * The native setMemoryLimit funciton
     */
    private final ExportFunction setMemoryLimit;

    /**
     * Map of contexts belonging to this runtime.
     */
    private final Map<Long, QuickJSContext> contexts = new HashMap<>();

    /**
     * Number of milliseconds a script is allowed to run. Defaults to infinite
     * runtime (scriptRuntimeLimit = -1)
     */
    private long scriptRuntimeLimit = -1;

    /**
     * Time in milliseconds when the script was started. This is used to ensure the
     * script meets its runtime limits
     */
    private long scriptStartTime = -1;

    /**
     * Creates a new QuickJSRuntime. Loads the wasm library from the classpath and
     * instantiates it.
     * 
     */
    public QuickJSRuntime() {
        final WasiOptions options = WasiOptions.builder().withStdout(System.out).build();
        final WasiPreview1 wasi = WasiPreview1.builder().withOptions(options).build();
        final Store store = new Store().addFunction(wasi.toHostFunctions()).addFunction(createCallHostFunctions());

        final WasmModule module = WasmLib.load();
        this.instance = Instance.builder(module).withImportValues(store.toImportValues())
                .withMachineFactory(WasmLib::create).build();

        this.alloc = this.instance.export("alloc");
        this.dealloc = this.instance.export("dealloc");
        this.closeRuntime = this.instance.export("close_runtime_wasm");
        this.setMemoryLimit = this.instance.export("set_memory_limit_runtime_wasm");

        initLogging();

        long[] result = this.instance.export("create_runtime_wasm").apply();
        this.ptr = result[0];
    }

    /**
     * Initializes the logging for the QuickJS wasm runtime, depending on the log
     * level of the native logger.
     */
    private void initLogging() {
        if (NATIVE_LOGGER.getLevel() == Level.ERROR || LOGGER.getLevel() == Level.FATAL) {
            initLogging(1);
        } else if (NATIVE_LOGGER.getLevel() == Level.WARN) {
            initLogging(2);
        } else if (NATIVE_LOGGER.getLevel() == Level.INFO) {
            initLogging(3);
        } else if (NATIVE_LOGGER.getLevel() == Level.DEBUG) {
            initLogging(4);
        } else if (NATIVE_LOGGER.getLevel() == Level.TRACE) {
            initLogging(5);
        } else if (NATIVE_LOGGER.getLevel() == Level.OFF) {
            initLogging(0);
        } else {
            LOGGER.warn("Unknown log level " + NATIVE_LOGGER.getLevel() + " , using INFO for native library");
            initLogging(3);
        }
    }

    /**
     * Initializes the logging for the QuickJS wasm runtime with the given level.
     * 
     * @param level The log level to use for the QuickJS wasm runtime.
     */
    private void initLogging(int level) {
        this.instance.export("init_logger_wasm").apply(level);
    }

    /**
     * Creates the host functions that are used to call Java functions from the
     * QuickJS wasm runtime.
     * 
     * @return The host functions.
     */
    private HostFunction[] createCallHostFunctions() {
        HostFunction hostFunction = new HostFunction(
                "env",
                "call_java_function",
                FunctionType.of(
                        // First param is the context pointer, second is the function pointer, third is
                        // the pointer to the message pack object, fourth is the length of the message
                        // pack object
                        List.of(ValType.I32, ValType.I32, ValType.I32, ValType.I32),
                        // Return value is the pointer to the result message pack object and the length
                        // packed into a i64
                        List.of(ValType.I64)),
                this::callHostFunction);

        HostFunction logHostFunction = new HostFunction(
                "env",
                "log_java",
                FunctionType.of(
                        // First param is the level, second is the pointer to the message string, third
                        // is the length of the message string
                        List.of(ValType.I32, ValType.I32, ValType.I32),
                        List.of()),
                this::logHostFunction);

        HostFunction interruptHandlerHostFunction = new HostFunction(
                "env",
                "js_interrupt_handler",
                FunctionType.of(
                        List.of(),
                        // Return value is 1 if the execution should be interrupted, 0 otherwise
                        List.of(ValType.I32)),
                this::interruptHandlerHostFunction);

        HostFunction createCompletableFutureHostFunction = new HostFunction(
                "env",
                "create_completable_future",
                FunctionType.of(
                        List.of(ValType.I64, ValType.I64),
                        List.of(ValType.I64)),
                this::createCompletableFutureHostFunction);

        HostFunction completeCompletableFuture = new HostFunction(
                "env",
                "complete_completable_future",
                FunctionType.of(
                        List.of(ValType.I64, ValType.I32, ValType.I32, ValType.I32, ValType.I32),
                        List.of(ValType.I64)),
                this::completeCompletableFutureHostFunction);

        return new HostFunction[] { hostFunction, logHostFunction, interruptHandlerHostFunction,
                createCompletableFutureHostFunction, completeCompletableFuture };
    }

    /**
     * This callback is called regularly from the QuickJS runtime to check if there
     * is an interrupt. If it returns 1 (true), the execution is interrupted. This
     * is currently used to set a max execution time for a script
     * 
     * @param instance
     * @param args
     * @return
     */
    private long[] interruptHandlerHostFunction(Instance instance, long... args) {
        if (this.scriptStartTime > 0 && scriptRuntimeLimit > 0) {
            final boolean result = !(System.currentTimeMillis() - scriptStartTime < scriptRuntimeLimit);
            if (result) {
                LOGGER.warn("Script runtime limit of {} ms exceeded, interrupting script", scriptRuntimeLimit);
            }
            return result ? new long[] { 1 } : new long[] { 0 };
        }
        return new long[] { 0 };
    }

    /**
     * Creates a new completable future and returns its index. Used to wrap native
     * promises.
     */
    private long[] createCompletableFutureHostFunction(Instance instance, long... args) {
        long contextPtr = args[0];
        long promisePtr = args[1];
        final QuickJSContext context = contexts.get(contextPtr);
        if (context == null) {
            throw new RuntimeException("Context not found: " + contextPtr);
        }
        return context.createCompletableFutureHostFunction(instance, promisePtr);
    }

    private long[] completeCompletableFutureHostFunction(Instance instance, long... args) {
        long contextPtr = (long) args[0];
        final QuickJSContext context = contexts.get(contextPtr);
        if (context == null) {
            throw new RuntimeException("Context not found: " + contextPtr);
        }
        return context.completeCompletableFutureHostFunction(instance, (int) args[1], (int) args[2], args[3],
                (int) args[4]);
    }

    /**
     * Callback called by QuickJSContext when a script is started
     */
    void scriptStarted() {
        scriptStartTime = System.currentTimeMillis();
        LOGGER.debug("Script started at time {}", scriptStartTime);
    }

    /**
     * Callback called by QuickJSContext when a script is finished
     */
    void scriptFinished() {
        LOGGER.debug("Script finished at time {}. Total runtime {} ms", () -> System.currentTimeMillis(),
                () -> System.currentTimeMillis() - scriptStartTime);
        this.scriptStartTime = -1;
    }

    /**
     * Sets the time a script is allowed to run. Negative values allow for infinite
     * runtime
     * 
     * @param limit Limit to set
     * @param unit  Time Unit of the limit
     * @return this QuickJSRuntime instance for method chaining.
     */
    public QuickJSRuntime withScriptRuntimeLimit(long limit, TimeUnit unit) {
        if (limit < 0) {
            scriptRuntimeLimit = 0;
        } else {
            if (unit == null) {
                throw new IllegalArgumentException("Time unit cannot be null");
            }
            scriptRuntimeLimit = unit.toMillis(limit);
        }
        return this;
    }

    /**
     * Sets the memory limit for JS Scripts. A value of 0 (default) allows for
     * unlimited memory
     * 
     * @param limit memory limit. (Should be at least 10000 bytes, or 0 for
     *              unlimited memory)
     * @return this QuickJSRuntime instance for method chaining.
     */
    public QuickJSRuntime withScriptMemoryLimit(long limit) {
        if (limit < 0) {
            throw new IllegalStateException("Memory limit must not be lower than 0");
        }
        this.setMemoryLimit.apply(this.getRuntimePointer(), limit);
        return this;
    }

    /**
     * Logs a message from the QuickJS wasm runtime to the native logger.
     * 
     * @param instance
     * @param args
     * @return
     */
    private long[] logHostFunction(Instance instance, long... args) {
        final int level = (int) args[0];
        final int messagePtr = (int) args[1];
        final int messageLen = (int) args[2];

        final String message = instance.memory().readString(messagePtr, messageLen);
        dealloc(messagePtr, messageLen);
        switch (level) {
            case 0:
                // DO nothing -> log is off
                break;
            case 5:
                NATIVE_LOGGER.trace(message);
                break;
            case 4:
                NATIVE_LOGGER.debug(message);
                break;
            case 3:
                NATIVE_LOGGER.info(message);
                break;
            case 2:
                NATIVE_LOGGER.warn(message);
                break;
            case 1:
                NATIVE_LOGGER.error(message);
                break;
            default:
                NATIVE_LOGGER.error(message);
        }
        return new long[] {};
    }

    /**
     * Calls the host function. This is a host function that is called from the
     * QuickJS runtime. Delegate the call to the corresponding context.
     * 
     * @param instance
     * @param args
     * @return
     */
    private long[] callHostFunction(Instance instance, long... args) {
        final long contextPtr = args[0];
        final long functionPtr = args[1];
        final long argsPtr = args[2];
        final long argsLen = args[3];

        final QuickJSContext context = contexts.get(contextPtr);
        if (context == null) {
            throw new RuntimeException("Context not found: " + contextPtr);
        }
        return context.callHostFunction(instance, functionPtr, argsPtr, argsLen);
    }

    /**
     * Creates a new context for this runtime.
     * 
     * @return New QuickJS Context
     */
    public QuickJSContext createContext() {
        QuickJSContext context = new QuickJSContext(this);
        contexts.put(context.getContextPointer(), context);
        LOGGER.debug("Created context with pointer: {}", context.getContextPointer());
        return context;
    }

    /**
     * Returns the pointer to the runtime in the wasm library
     * 
     * @return native pointer to the runtime
     */
    long getRuntimePointer() {
        return this.ptr;
    }

    /**
     * Returns the instance of the runtime
     * 
     * @return WASM instance
     */
    Instance getInstance() {
        return this.instance;
    }

    /**
     * Writes the given data to memory and returns the memory location of the data
     * 
     * @param data the data to write
     * @return the memory location of the data
     */
    MemoryLocation writeToMemory(byte[] data) {
        long ptr = alloc(data.length);
        getInstance().memory().write((int) ptr, data);
        return new MemoryLocation(ptr, data.length, this);
    }

    /**
     * Writes the given string to memory and returns the memory location of the data
     * 
     * @param data the string to write
     * @return the memory location of the data
     */
    MemoryLocation writeToMemory(String data) {
        return writeToMemory(data.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Allocates memory
     * 
     * @param size the size of the memory to allocate
     * @return the pointer to the allocated memory
     */
    long alloc(int size) {
        long[] ptr = alloc.apply(size);
        return ptr[0];
    }

    /**
     * Deallocates memory
     * 
     * @param ptr  the pointer to the memory to deallocate
     * @param size the size of the memory to deallocate
     */
    void dealloc(long ptr, int size) {
        dealloc.apply(ptr, size);
    }

    /**
     * Closes the runtime and all associated contexts
     */
    @Override
    public void close() throws Exception {
        if (ptr == 0) {
            LOGGER.warn("Tried to close QuickJS runtime that was already closed");
            return;
        }

        for (QuickJSContext context : contexts.values()) {
            context.close();
        }
        contexts.clear();

        try {
            closeRuntime.apply(getRuntimePointer());
        } catch (Exception e) {
            // Maybe the runtime was already closed?
            LOGGER.debug("Error closing runtime", e);
        }
        ptr = 0;
    }
}
