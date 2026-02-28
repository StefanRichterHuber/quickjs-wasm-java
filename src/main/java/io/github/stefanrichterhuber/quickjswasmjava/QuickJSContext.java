package io.github.stefanrichterhuber.quickjswasmjava;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import javax.script.Invocable;
import javax.script.ScriptException;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.dylibso.chicory.runtime.ExportFunction;
import com.dylibso.chicory.runtime.Instance;

import io.github.stefanrichterhuber.quickjswasmjava.QuickJSRuntime.ScriptDurationGuard;

/**
 * Java representation of a QuickJS context object. The context is the central
 * interaction point with the JS runtime. Here you set / get global variables
 * and execute scripts. Each context has its own memory space and can be used to
 * evaluate scripts independently of other contexts. It is recommended to close
 * the context when it is no longer needed to free up resources.
 */
public final class QuickJSContext implements AutoCloseable, Invocable {
    private static final Logger LOGGER = LogManager.getLogger();

    /**
     * Pointer to the QuickJS context object in the Wasm memory.
     */
    private long contextPtr;

    /**
     * The runtime this context belongs to.
     */
    private final QuickJSRuntime runtime;

    /**
     * The native eval function.
     */
    private final ExportFunction eval;

    /**
     * The native eval with async support function.
     */
    private final ExportFunction evalAsync;

    /**
     * The native createContext function.
     */
    private final ExportFunction createContext;

    /**
     * The native setGlobal function.
     */
    private final ExportFunction setGlobal;

    /**
     * The native closeContext function.
     */
    private final ExportFunction closeContext;

    /**
     * The native getGlobal function.
     */
    private final ExportFunction getGlobal;

    /**
     * The native invoke function.
     */
    private final ExportFunction invoke;

    /**
     * The native poll function.
     */
    private final ExportFunction poll;

    /**
     * List of resources that are dependent on this context. If this context is
     * closed, all dependent resources will be closed too.
     */
    private final List<AutoCloseable> dependentResources = new ArrayList<>();

    /**
     * List of host functions.
     */
    final List<Function<List<Object>, Object>> hostFunctions = new ArrayList<>();

    private final MessagePackRegistry messagePackRegistry;

    /**
     * List of completable futures wrapping native promises.
     */
    final List<CompletableFuture<Object>> completableFutures = new ArrayList<>();

    /**
     * Creates a new QuickJS context from a existing runtime. Not public, use
     * {@link QuickJSRuntime#createContext()} instead.
     * 
     * @param runtime The runtime to create the context in.
     */
    QuickJSContext(final QuickJSRuntime runtime) {
        this.messagePackRegistry = new MessagePackRegistry(this);
        this.runtime = runtime;
        this.createContext = runtime.getInstance().export("create_context_wasm");
        this.closeContext = runtime.getInstance().export("close_context_wasm");
        this.eval = runtime.getInstance().export("eval_script_wasm");
        this.setGlobal = runtime.getInstance().export("set_global_wasm");
        this.getGlobal = runtime.getInstance().export("get_global_wasm");
        this.invoke = runtime.getInstance().export("invoke_wasm");
        this.evalAsync = runtime.getInstance().export("eval_script_async_wasm");
        this.poll = runtime.getInstance().export("poll_wasm");
        this.contextPtr = createContext.apply(runtime.getRuntimePointer())[0];

    }

    /**
     * Adds a resource depending on this context and needs to be closed before this
     * context closes
     * 
     * @param resource Resource to close
     */
    void addDependentResource(AutoCloseable resource) {
        if (resource == null) {
            throw new IllegalArgumentException("Resource to close must not be null");
        }
        dependentResources.add(resource);
    }

    /**
     * Calls a registred java host function from the QuickJS context.
     * 
     * @param instance    The Wasm instance.
     * @param functionPtr The pointer to the host function.
     * @param argPtr      The pointer to the arguments.
     * @param argLen      The length of the arguments.
     * @return The result of the host function.
     */
    long[] callHostFunction(Instance instance, long functionPtr, long argPtr, long argLen) {

        LOGGER.debug("Calling host function: {}", functionPtr);
        final Function<List<Object>, Object> function = hostFunctions.get((int) functionPtr);
        if (function == null) {
            LOGGER.error("Host function with pointer {} not found", functionPtr);
            throw new RuntimeException("Function not found: " + functionPtr);
        }

        try (MemoryLocation argsLocation = new MemoryLocation(argPtr, (int) argLen, this.getRuntime())) {
            final Object realArgs = unpackObjectFromMemory(argsLocation);

            final List<Object> args = switch (realArgs) {
                case List<?> l -> (List<Object>) l;
                default -> List.of(realArgs);
            };
            final Object result = function.apply(args);

            // Now we have to write the result back to the memory (don't close the memory
            // location here!)
            final MemoryLocation resultLocation = this.writeToMemory(result);
            return new long[] { resultLocation.pack() };
        } catch (RuntimeException e) {
            MemoryLocation resultLocation = this.writeToMemory(e);
            return new long[] { resultLocation.pack() };
        }
    }

    /**
     * Creates a new completable future and returns its index. Used to wrap native
     * promises.
     */
    long[] createCompletableFutureHostFunction(Instance instance, long promise_ptr) {
        final QuickJSPromise completableFuture = new QuickJSPromise(this, promise_ptr);
        return new long[] { completableFuture.getCompletableFuturePointer() };
    }

    /**
     * Completes a completable future from the native code
     */
    long[] completeCompletableFutureHostFunction(Instance instance, int reject, int futurePtr, long argPtr,
            int argLen) {
        final CompletableFuture<Object> f = this.completableFutures.get(futurePtr);
        if (f == null) {
            throw new IllegalStateException("No completable future found for index " + futurePtr);
        }

        try (MemoryLocation arg = new MemoryLocation(argPtr, argLen, this.getRuntime())) {
            final Object r = unpackObjectFromMemory(arg);
            LOGGER.debug("Completing future from js promise with value {}", r);
            if (r instanceof Exception) {
                if (r instanceof QuickJSException) {
                    ((QuickJSPromise) f).completeExceptionallyByJS((QuickJSException) r);
                } else {
                    f.completeExceptionally((Exception) r);
                }
            } else {
                if (reject == 1) {
                    if (f instanceof QuickJSPromise) {
                        ((QuickJSPromise) f).completeExceptionallyByJS(new QuickJSException("Promise rejected", null));
                    } else {
                        f.completeExceptionally(new QuickJSException("Promise rejected", null));
                    }
                } else {
                    if (f instanceof QuickJSPromise) {
                        ((QuickJSPromise) f).completeByJS(r);
                    } else {
                        f.complete(r);
                    }
                }
            }
        }
        return new long[] { 0 };
    }

    /**
     * Invokes a function in the QuickJS context.
     * 
     * @param name The name of the function to invoke.
     * @param args The arguments to pass to the function.
     * @return The result of the function.
     * @throws NoSuchMethodException
     */
    public Object invoke(String name, Object... args) throws NoSuchMethodException {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("Name must not be null or empty");
        }

        // Support for directly calling functions in (nested) objects
        if (name.contains(".")) {
            final String functionName = name.substring(name.indexOf(".") + 1);
            final String objectName = name.substring(0, name.indexOf("."));

            final Object obj = this.getGlobal(objectName);
            try {
                if (obj instanceof QuickJSObject<?, ?>) {
                    return ((QuickJSObject<?, ?>) obj).invokeFunction(functionName, args);
                }
            } catch (NoSuchMethodException e) {
                // Catch the no such method exceptions for nested calls to only throw the
                // exception
                // with the correct name
            }
            throw new NoSuchMethodException(name);
        }

        try (final MemoryLocation nameLocation = this.writeStringToMemory(name);
                final MemoryLocation argsLocation = this.writeToMemory(List.of(args));
                final ScriptDurationGuard guard = new ScriptDurationGuard(this.runtime);) {
            final long[] result = invoke.apply(contextPtr, nameLocation.pointer(), nameLocation.length(),
                    argsLocation.pointer(),
                    argsLocation.length());
            return handleNativeResult(result);
        }
    }

    /**
     * Centralized result handling of native calls with an message packed result
     */
    Object handleNativeResult(long[] result) {
        try (MemoryLocation resLoc = MemoryLocation.unpack(result[0], runtime)) {
            Object unpacked = unpackObjectFromMemory(resLoc);
            if (unpacked instanceof RuntimeException ex)
                throw ex;
            return unpacked;
        }
    }

    /**
     * Evaluates a script in the QuickJS context.
     * 
     * @param script The script to evaluate.
     * @return The result of the script.
     */
    public Object eval(String script) {
        try (final MemoryLocation scriptLocation = this.writeStringToMemory(script);
                ScriptDurationGuard guard = new ScriptDurationGuard(this.runtime)) {
            long[] result = eval.apply(contextPtr, scriptLocation.pointer(), scriptLocation.length());
            return handleNativeResult(result);
        }
    }

    /**
     * Evaluates a script in the QuickJS context with async support
     * 
     * @param script The script to evaluate.
     * @return The result of the script.
     */
    public CompletableFuture<Object> evalAsync(String script) {
        try (final MemoryLocation scriptLocation = this.writeStringToMemory(script);
                ScriptDurationGuard guard = new ScriptDurationGuard(this.runtime)) {
            long[] result = evalAsync.apply(contextPtr, scriptLocation.pointer(), scriptLocation.length());
            return (CompletableFuture<Object>) handleNativeResult(result);
        }
    }

    /**
     * Polls the QuickJS context for pending jobs.
     * 
     * @return true if there are pending jobs, false otherwise.
     */
    public boolean poll() {
        long[] result = poll.apply(contextPtr);
        return result[0] == 1;
    }

    /**
     * Sets a global variable in the QuickJS context.
     * 
     * @param name  The name of the global variable.
     * @param value The value of the global variable.
     */
    public void setGlobal(String name, Object value) {

        LOGGER.debug("Setting global: {} = {}", name, value);

        try (final MemoryLocation nameLocation = writeStringToMemory(name);
                final MemoryLocation valueLocation = this.writeToMemory(value)) {
            // Then call the set global function
            final long[] result = setGlobal.apply(contextPtr, nameLocation.pointer(), nameLocation.length(),
                    valueLocation.pointer(),
                    valueLocation.length());

            try (final MemoryLocation resultLocation = MemoryLocation.unpack(result[0], runtime)) {
                final Object r = unpackObjectFromMemory(resultLocation);
                if (r == null) {
                    return;
                }
                if (r instanceof RuntimeException) {
                    throw (RuntimeException) r;
                } else {
                    throw new RuntimeException("Unexpected result: " + r);
                }
            }
        }
    }

    /**
     * Gets a global variable from the QuickJS context.
     * 
     * @param name The name of the global variable.
     * @return The global variable.
     */
    public Object getGlobal(String name) {
        LOGGER.debug("Getting global: {}", name);

        try (final MemoryLocation nameLocation = writeStringToMemory(name)) {
            final long[] result = getGlobal.apply(contextPtr, nameLocation.pointer(), nameLocation.length());
            return handleNativeResult(result);
        }
    }

    /**
     * Sets a global variable in the QuickJS context.
     * 
     * @param name  The name of the global variable.
     * @param value The value of the global variable.
     */
    public void setGlobal(String name, Double value) {
        setGlobal(name, (Object) value);
    }

    /**
     * Sets a global variable in the QuickJS context.
     * 
     * @param name  The name of the global variable.
     * @param value The value of the global variable.
     */
    public void setGlobal(String name, Integer value) {
        setGlobal(name, (Object) value);
    }

    /**
     * Sets a global variable in the QuickJS context.
     * 
     * @param name  The name of the global variable.
     * @param value The value of the global variable.
     */
    public void setGlobal(String name, String value) {
        setGlobal(name, (Object) value);
    }

    /**
     * Sets a global variable in the QuickJS context.
     * 
     * @param name  The name of the global variable.
     * @param value The value of the global variable.
     */
    public void setGlobal(String name, Boolean value) {
        setGlobal(name, (Object) value);
    }

    /**
     * Sets a global variable in the QuickJS context.
     * 
     * @param name  The name of the global variable.
     * @param value The value of the global variable.
     * @param <T>   Any supported java type, including other lists and maps
     */
    public <T> void setGlobal(String name, List<T> value) {
        setGlobal(name, (Object) value);
    }

    /**
     * Sets a global variable in the QuickJS context.
     * 
     * @param name  The name of the global variable.
     * @param value The value of the global variable.
     * @param <K>   String, numbers or boolean are supported as map keys
     * @param <V>   Any supported java type, including other lists and maps
     */
    public <K, V> void setGlobal(String name, QuickJSObject<K, V> value) {
        setGlobal(name, (Object) value);
    }

    /**
     * Sets a global variable in the QuickJS context.
     * 
     * @param name  The name of the global variable.
     * @param value The value of the global variable.
     * @param <T>   Any supported java type, including other lists and maps
     */
    public <T> void setGlobal(String name, Map<String, T> value) {
        setGlobal(name, (Object) value);
    }

    /**
     * Imports a java function as a global function in the QuickJS context.
     * 
     * @param <P>   The type of the parameter of the function.
     * @param <R>   The type of the return value of the function.
     * @param name  The name of the global function.
     * @param value The function to set.
     */
    public <P, R> void setGlobal(String name, Function<P, R> value) {
        if (value instanceof QuickJSFunction) {
            setGlobal(name, (Object) value);
        } else {
            @SuppressWarnings("unchecked")
            final Function<List<Object>, Object> function = (args) -> {
                Object arg = args != null && args.size() > 0 ? args.get(0) : null;
                Object result = value.apply((P) arg);
                return result;
            };
            setGlobal(name, (Object) function);
        }
    }

    /**
     * Imports a java function as a global function in the QuickJS context.
     * 
     * @param <P>   The type of the first parameter of the function.
     * @param <Q>   The type of the second parameter of the function
     * @param <R>   The type of the return value of the function.
     * @param name  The name of the global function.
     * @param value The function to set.
     */
    public <P, Q, R> void setGlobal(String name, BiFunction<P, Q, R> value) {
        @SuppressWarnings("unchecked")
        final Function<List<Object>, Object> function = (args) -> {
            return value.apply((P) args.get(0), (Q) args.get(1));
        };
        setGlobal(name, (Object) function);
    }

    /**
     * Imports a java function as a global function in the QuickJS context.
     * 
     * @param <P>   The type of the parameter of the function.
     * @param name  The name of the global function.
     * @param value The function to set.
     */
    public <P> void setGlobal(String name, Consumer<P> value) {
        @SuppressWarnings("unchecked")
        final Function<List<Object>, Object> function = (args) -> {
            value.accept((P) args.get(0));
            return null;
        };
        setGlobal(name, (Object) function);
    }

    /**
     * Imports a java function as a global function in the QuickJS context.
     * 
     * @param <P>   The type of the first parameter of the function.
     * @param <Q>   The type of the second parameter of the function.
     * @param name  The name of the global function.
     * @param value The function to set.
     */
    public <P, Q> void setGlobal(String name, BiConsumer<P, Q> value) {
        @SuppressWarnings("unchecked")
        final Function<List<Object>, Object> function = (args) -> {
            value.accept((P) args.get(0), (Q) args.get(1));
            return null;
        };
        setGlobal(name, (Object) function);
    }

    /**
     * Imports a java function as a global function in the QuickJS context.
     * 
     * @param <R>   The type of the parameter of the function.
     * @param name  The name of the global function.
     * @param value The function to set.
     */
    public <R> void setGlobal(String name, Supplier<R> value) {
        final Function<List<Object>, Object> function = (args) -> {
            return value.get();
        };
        setGlobal(name, (Object) function);
    }

    /**
     * Retrieves an interface from the QuickJS context. This way js functions
     * can be called as if they were java functions.
     * 
     * @param <T>   The type of the interface.
     * @param clasz The class of the interface.
     * @return The interface.
     */
    @Override
    public <T> T getInterface(Class<T> clasz) {
        return (T) Proxy.newProxyInstance(clasz.getClassLoader(), new Class[] { clasz },
                new ScriptInvocationHandler<>(this, null));
    }

    /**
     * Retrieves an interface from an object in the QuickJS context. This way js
     * functions can be called as if they were java functions.
     * 
     * @param <T>   The type of the interface.
     * @param clasz The class of the interface.
     * @return The interface.
     */
    public <T> T getInterface(QuickJSObject<?, ?> thiz, Class<T> clasz) {
        return thiz.getInterface(clasz);
    }

    @Override
    public <T> T getInterface(Object thiz, Class<T> clasz) {
        if (thiz instanceof QuickJSObject)
            return getInterface((QuickJSObject<?, ?>) thiz, clasz);
        throw new IllegalArgumentException("Object is not a QuickJSObject");
    }

    @Override
    public Object invokeMethod(Object thiz, String name, Object... args) throws ScriptException, NoSuchMethodException {
        if (name == null)
            throw new IllegalArgumentException("Method name cannot be null");

        if (thiz instanceof QuickJSObject)
            return ((QuickJSObject<?, ?>) thiz).invokeFunction(name, args);
        throw new IllegalArgumentException("Object " + thiz + " is not a QuickJSObject");
    }

    @Override
    public Object invokeFunction(String name, Object... args) throws ScriptException, NoSuchMethodException {
        return this.invoke(name, args);
    }

    /**
     * Writes an object to the memory of the QuickJS runtime.
     * 
     * @param data The object to write to the memory.
     * @return The memory location of the object.
     * @throws IOException If the object cannot be written to the memory.
     */
    MemoryLocation writeToMemory(Object data) {
        final byte[] valueBytes = this.messagePackRegistry.pack(data);
        return this.getRuntime().writeToMemory(valueBytes);
    }

    /**
     * Writes an raw string (without packing it to a message pack) to the memory of
     * the QuickJS runtime.
     * 
     * @param value The string to write to the memory.
     * @return The memory location of the string.
     */
    MemoryLocation writeStringToMemory(String value) {
        if (value == null) {
            throw new IllegalArgumentException("string value to write to the memory must not be null");
        }
        final byte[] valueBytes = value.getBytes(StandardCharsets.UTF_8);
        return this.getRuntime().writeToMemory(valueBytes);
    }

    /**
     * Returns the pointer to the QuickJS context.
     * 
     * @return The pointer to the QuickJS context.
     */
    long getContextPointer() {
        if (contextPtr == 0) {
            throw new IllegalStateException("Context already closed");
        }
        return contextPtr;
    }

    /**
     * Returns the runtime of the QuickJS context.
     * 
     * @return The runtime of the QuickJS context.
     */
    QuickJSRuntime getRuntime() {
        return runtime;
    }

    /**
     * Unpacks an object from a memory location.
     * 
     * @param memoryLocation The memory location to unpack the object from.
     * @return The unpacked object.
     * @throws IOException If the object cannot be unpacked.
     */
    Object unpackObjectFromMemory(MemoryLocation memoryLocation) {
        final byte[] bytes = memoryLocation.runtime().getInstance().memory().readBytes((int) memoryLocation.pointer(),
                (int) memoryLocation.length());
        return this.messagePackRegistry.unpack(bytes);
    }

    /**
     * Closes the context and all associated resources
     */
    @Override
    public void close() throws Exception {
        LOGGER.debug("Start closing QuickJSContext");
        if (contextPtr == 0) {
            return;
        }
        for (var resource : dependentResources) {
            resource.close();
        }
        dependentResources.clear();
        hostFunctions.clear();

        try {
            closeContext.apply(contextPtr);
        } catch (Exception e) {
            // Closing the context might fail after a runtime limit was reached
            LOGGER.warn("Error closing QuickJS context", e);
        }
        contextPtr = 0;
        LOGGER.debug("Successfully closed QuickJSContext");

    }
}
