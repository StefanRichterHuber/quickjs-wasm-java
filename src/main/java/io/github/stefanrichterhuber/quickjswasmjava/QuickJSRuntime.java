package io.github.stefanrichterhuber.quickjswasmjava;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.msgpack.core.MessagePack;
import org.msgpack.core.MessageUnpacker;

import com.dylibso.chicory.runtime.ExportFunction;
import com.dylibso.chicory.runtime.HostFunction;
import com.dylibso.chicory.runtime.Instance;
import com.dylibso.chicory.runtime.Store;
import com.dylibso.chicory.wasi.WasiOptions;
import com.dylibso.chicory.wasi.WasiPreview1;
import com.dylibso.chicory.wasm.Parser;
import com.dylibso.chicory.wasm.types.FunctionType;
import com.dylibso.chicory.wasm.types.ValType;

/**
 * Represents a QuickJS runtime. This is the main entry point for the QuickJS
 * wasm library.
 */
public class QuickJSRuntime implements AutoCloseable {
    private static final Logger LOGGER = LogManager.getLogger(QuickJSRuntime.class);
    private static final Logger NATIVE_LOGGER = LogManager
            .getLogger("io.github.stefanrichterhuber.quickjswasmjava.native.WasmLib");

    private long ptr;
    private final Store store;
    private final Instance instance;
    private final ExportFunction alloc;
    private final ExportFunction dealloc;
    private final ExportFunction closeRuntime;

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
     * @throws IOException
     */
    public QuickJSRuntime() throws IOException {
        try (InputStream is = this.getClass()
                .getResourceAsStream("libs/wasm_lib.wasm")) {

            var options = WasiOptions.builder().withStdout(System.out).build();
            var wasi = WasiPreview1.builder().withOptions(options).build();
            this.store = new Store().addFunction(wasi.toHostFunctions()).addFunction(createCallHostFunctions());

            // instantiate and execute the main entry point
            this.instance = this.store.instantiate("quickjslib", Parser.parse(is));

            this.alloc = instance.export("alloc");
            this.dealloc = instance.export("dealloc");
            this.closeRuntime = instance.export("close_runtime_wasm");

            initLogging();

            long[] result = this.instance.export("create_runtime_wasm").apply();
            this.ptr = result[0];
        }
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
                        List.of(ValType.I32)),
                this::interruptHandlerHostFunction);

        return new HostFunction[] { hostFunction, logHostFunction, interruptHandlerHostFunction };
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
     * @return
     */
    public QuickJSContext createContext() {
        QuickJSContext context = new QuickJSContext(this);
        contexts.put(context.getContextPointer(), context);
        LOGGER.info("Created context with pointer: {}", context.getContextPointer());
        return context;
    }

    /**
     * Returns the pointer to the runtime in the wasm library
     * 
     * @return
     */
    long getRuntimePointer() {
        return this.ptr;
    }

    /**
     * Returns the instance of the runtime
     * 
     * @return
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
     * Reads the string from memory
     * 
     * @param result the result of the memory location
     * @return the string read from memory
     */
    String readStringFromMemory(long... result) {
        int resultLen = (int) (result[0] & 0xffffffff);
        int resultPtr = (int) ((result[0] >> 32) & 0xffffffff);
        return getInstance().memory().readString(resultPtr, resultLen);
    }

    /**
     * Reads the bytes from memory
     * 
     * @param result the result of the memory location
     * @return the bytes read from memory
     */
    byte[] readBytesFromMemory(long... result) {
        int resultLen = (int) (result[0] & 0xffffffff);
        int resultPtr = (int) ((result[0] >> 32) & 0xffffffff);
        return getInstance().memory().readBytes(resultPtr, resultLen);
    }

    /**
     * Unpacks the bytes from memory into a MessageUnpacker
     * 
     * @param result the result of the memory location
     * @return the MessageUnpacker
     */
    MessageUnpacker unpackBytesFromMemory(long... result) {
        byte[] resultBytes = readBytesFromMemory(result);
        MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(resultBytes);
        return unpacker;
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

        closeRuntime.apply(getRuntimePointer());
        ptr = 0;
    }
}
