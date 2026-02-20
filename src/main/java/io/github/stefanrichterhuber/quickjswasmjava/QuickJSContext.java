package io.github.stefanrichterhuber.quickjswasmjava;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import javax.script.Invocable;
import javax.script.ScriptException;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.msgpack.core.MessageFormat;
import org.msgpack.core.MessagePack;
import org.msgpack.core.MessagePacker;
import org.msgpack.core.MessageUnpacker;
import org.msgpack.value.ValueType;

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
     * List of resources that are dependent on this context. If this context is
     * closed, all dependent resources will be closed too.
     */
    private final List<AutoCloseable> dependendResources = new ArrayList<>();

    /**
     * List of host functions.
     */
    private final List<Function<List<Object>, Object>> hostFunctions = new ArrayList<>();

    /**
     * Creates a new QuickJS context from a existing runtime. Not public, use
     * {@link QuickJSRuntime#createContext()} instead.
     * 
     * @param runtime The runtime to create the context in.
     */
    QuickJSContext(final QuickJSRuntime runtime) {
        this.runtime = runtime;
        this.createContext = runtime.getInstance().export("create_context_wasm");
        this.closeContext = runtime.getInstance().export("close_context_wasm");
        this.eval = runtime.getInstance().export("eval_script_wasm");
        this.setGlobal = runtime.getInstance().export("set_global_wasm");
        this.getGlobal = runtime.getInstance().export("get_global_wasm");
        this.invoke = runtime.getInstance().export("invoke_wasm");
        this.contextPtr = createContext.apply(runtime.getRuntimePointer())[0];

    }

    void addDependendResource(AutoCloseable resource) {
        dependendResources.add(resource);
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

        try {
            final Object realArgs = unpackObjectFromMemory(instance, argPtr, argLen);

            Object result = null;
            if (realArgs instanceof List) {
                @SuppressWarnings("unchecked")
                List<Object> argsList = (List<Object>) realArgs;
                result = function.apply(argsList);
            } else {
                List<Object> argsList = List.of(realArgs);
                result = function.apply(argsList);
            }

            // Now we have to write the result back to the memory (don't close the memory
            // location here!)
            MemoryLocation resultLocation = this.writeToMemory(result);
            return new long[] { resultLocation.pack() };
        } catch (RuntimeException e) {
            MemoryLocation resultLocation = this.writeToMemory(e);
            return new long[] { resultLocation.pack() };
        }
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
     * Centralized result handling
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
     * @throws IOException If the script cannot be evaluated.
     */
    public Object eval(String script) throws IOException {
        try (final MemoryLocation scriptLocation = this.writeStringToMemory(script);
                ScriptDurationGuard guard = new ScriptDurationGuard(this.runtime)) {
            long[] result = eval.apply(contextPtr, scriptLocation.pointer(), scriptLocation.length());
            return handleNativeResult(result);
        }
    }

    /**
     * Sets a global variable in the QuickJS context.
     * 
     * @param name  The name of the global variable.
     * @param value The value of the global variable.
     */
    public void setGlobal(String name, Object value) {

        LOGGER.debug("Setting global: {} = {}", name, value);

        try (final MemoryLocation nameLocation = this.getRuntime().writeToMemory(name);
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

        try (final MemoryLocation nameLocation = this.getRuntime().writeToMemory(name)) {
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
                return value.apply((P) args.get(0));
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
        try {
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            final MessagePacker packer = MessagePack.newDefaultPacker(out);
            packObject(data, packer);
            packer.close();
            final byte[] valueBytes = out.toByteArray();
            return this.getRuntime().writeToMemory(valueBytes);
        } catch (IOException e) {
            throw new RuntimeException("Unable to write object to memory", e);
        }
    }

    /**
     * Writes an raw string (without packing it to a message pack) to the memory of
     * the QuickJS runtime.
     * 
     * @param value The string to write to the memory.
     * @return The memory location of the string.
     */
    MemoryLocation writeStringToMemory(String value) {
        final byte[] valueBytes = value.getBytes(StandardCharsets.UTF_8);
        final int valueBytesLen = valueBytes.length;

        final long valuePtr = runtime.alloc(valueBytesLen);
        runtime.getInstance().memory().writeString((int) valuePtr, value, StandardCharsets.UTF_8);

        return new MemoryLocation(valuePtr, valueBytesLen, runtime);
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
     * Packs an object to a message pack packer.
     * 
     * @param obj    The object to pack.
     * @param packer The message pack packer.
     * @throws IOException If the object cannot be packed.
     */
    @SuppressWarnings("unchecked")
    void packObject(Object obj, MessagePacker packer) throws IOException {
        if (obj == null) {
            packer.packString("null");
        } else if (obj instanceof Double) {
            packer.packMapHeader(1);
            packer.packString("float");
            packer.packDouble(((Double) obj).doubleValue());
        } else if (obj instanceof Float) {
            packObject(((Float) obj).doubleValue(), packer);
        } else if (obj instanceof Integer) {
            packer.packMapHeader(1);
            packer.packString("int");
            packer.packInt(((Integer) obj).intValue());
        } else if (obj instanceof Boolean) {
            packer.packMapHeader(1);
            packer.packString("boolean");
            packer.packBoolean(((Boolean) obj).booleanValue());
        } else if (obj instanceof String) {
            packer.packMapHeader(1);
            packer.packString("string");
            packer.packString((String) obj);
        } else if (obj instanceof QuickJSArray) {
            packer.packMapHeader(1);
            packer.packString("nativeArray");
            packer.packLong(((QuickJSArray<?>) obj).getArrayPointer());
        } else if (obj instanceof List) {
            packer.packMapHeader(1);
            packer.packString("array");
            packer.packArrayHeader(((List<?>) obj).size());
            for (Object item : (List<?>) obj) {
                packObject(item, packer);
            }
        } else if (obj instanceof QuickJSObject) {
            packer.packMapHeader(1);
            packer.packString("nativeObject");
            packer.packLong(((QuickJSObject<?, ?>) obj).getObjectPointer());
        } else if (obj instanceof Map) {
            packer.packMapHeader(1);
            packer.packString("object");
            packer.packMapHeader(((Map<?, ?>) obj).size());
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) obj).entrySet()) {
                packer.packString(entry.getKey().toString());
                packObject(entry.getValue(), packer);
            }
        } else if (obj instanceof QuickJSFunction) {
            packer.packMapHeader(1);
            packer.packString("function");
            packer.packArrayHeader(2);
            packer.packString(((QuickJSFunction) obj).getName());
            packer.packLong(((QuickJSFunction) obj).getFunctionPointer());
        } else if (obj instanceof Function) {
            packer.packMapHeader(1);
            packer.packString("javaFunction");
            packer.packArrayHeader(2);
            packer.packInt((int) this.getContextPointer());
            packer.packInt((int) hostFunctions.size());
            hostFunctions.add((Function<List<Object>, Object>) obj);
        } else if (obj instanceof Exception) {
            packer.packMapHeader(1);
            packer.packString("exception");
            packer.packArrayHeader(2);
            packer.packString(((Exception) obj).getMessage());
            packer.packString(
                    Arrays.asList(((Exception) obj).getStackTrace()).stream().map(Object::toString)
                            .collect(Collectors.joining("\n")));
        } else {
            throw new RuntimeException("Unsupported type: " + obj.getClass().getName());
        }
    }

    /**
     * Unpacks an object from a memory location.
     * 
     * @param memoryLocation The memory location to unpack the object from.
     * @return The unpacked object.
     * @throws IOException If the object cannot be unpacked.
     */
    Object unpackObjectFromMemory(MemoryLocation memoryLocation) {
        return unpackObjectFromMemory(memoryLocation.runtime().getInstance(), memoryLocation.pointer(),
                memoryLocation.length());
    }

    /**
     * Unpacks an object from memory.
     * 
     * @param instance The instance to read the memory from.
     * @param ptr      The pointer to the memory location.
     * @param len      The length of the memory location.
     * @return The unpacked object.
     * @throws IOException If the object cannot be unpacked.
     */
    Object unpackObjectFromMemory(Instance instance, long ptr, long len) {
        try {
            final byte[] bytes = instance.memory().readBytes((int) ptr, (int) len);
            MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(bytes);
            return unpackObject(unpacker);
        } catch (IOException e) {
            throw new RuntimeException("Failed to unpack object from memory", e);
        }
    }

    /**
     * Unpacks an object from a message unpacker.
     * 
     * @param unpacker The message unpacker to unpack the object from.
     * @return The unpacked object.
     * @throws IOException If the object cannot be unpacked.
     */
    Object unpackObject(MessageUnpacker unpacker) throws IOException {
        // First field is the type
        final MessageFormat format = unpacker.getNextFormat();
        final ValueType valueType = format.getValueType();

        if (valueType == ValueType.MAP) {
            // Should always be 1
            final int mapSize = unpacker.unpackMapHeader();
            if (mapSize != 1) {
                throw new RuntimeException("Expected map size of 1, got " + mapSize);
            }
            final String type = unpacker.unpackString();
            switch (type) {
                case "string":
                    return unpacker.unpackString();
                case "float":
                    return unpacker.unpackDouble();
                case "boolean":
                    return unpacker.unpackBoolean();
                case "int":
                    return unpacker.unpackInt();
                case "array": {
                    // Deprecated: Impossible to enter, since a nativeArray should always be
                    // returned

                    int arraySize = unpacker.unpackArrayHeader();
                    List<Object> array = new ArrayList<>();
                    for (int i = 0; i < arraySize; i++) {
                        array.add(unpackObject(unpacker));
                    }
                    return array;
                }
                case "object":
                    // Deprecated: Impossible to enter, since a nativeObject should always be
                    // returned

                    int objectSize = unpacker.unpackMapHeader();
                    Map<String, Object> object = new HashMap<>();
                    for (int i = 0; i < objectSize; i++) {
                        String key = unpacker.unpackString();
                        object.put(key, unpackObject(unpacker));
                    }
                    return object;
                case "function": {
                    int arraySize = unpacker.unpackArrayHeader();
                    if (arraySize != 2) {
                        throw new RuntimeException("Expected array with 2 element (function name, function ptr)");
                    }
                    String functionName = unpacker.unpackString();
                    long functionPtr = unpacker.unpackLong();
                    return new QuickJSFunction(this, functionName, functionPtr);
                }
                case "javaFunction": {
                    // Impossible to enter since, one would always get back a 'function', wrapping
                    // the Java function

                    int arraySize = unpacker.unpackArrayHeader();
                    if (arraySize != 2) {
                        throw new RuntimeException("Expected array with 2 element (context ptr, function index)");
                    }
                    final int contextPtr = unpacker.unpackInt();
                    final int functionIndex = unpacker.unpackInt();
                    if (contextPtr != this.getContextPointer()) {
                        throw new RuntimeException("Context pointer does not match");
                    }
                    return hostFunctions.get(functionIndex);
                }
                case "exception": {
                    int arraySize = unpacker.unpackArrayHeader();
                    if (arraySize != 2) {
                        throw new RuntimeException(
                                "Expected array with 2 element (exception message, exception stack)");
                    }
                    String message = unpacker.unpackString();
                    String stack = unpacker.unpackString();
                    return new QuickJSException(message, stack);
                }
                case "nativeArray": {
                    long pointer = unpacker.unpackLong();
                    return new QuickJSArray<>(this, pointer);
                }
                case "nativeObject": {
                    long pointer = unpacker.unpackLong();
                    return new QuickJSObject<>(this, pointer);
                }
                default:
                    throw new RuntimeException("Unknown type: " + type);
            }
        } else if (valueType == ValueType.STRING) {
            String rawValue = unpacker.unpackString();
            if (rawValue.equals("null")) {
                return null;
            } else if (rawValue.equals("undefined")) {
                return null;
            }
        }

        return null;
    }

    /**
     * Closes the context and all associated resources
     */
    @Override
    public void close() throws Exception {
        if (contextPtr == 0) {
            return;
        }
        for (var resource : dependendResources) {
            resource.close();
        }
        dependendResources.clear();
        hostFunctions.clear();

        try {
            closeContext.apply(contextPtr);
        } catch (Exception e) {
            // Closing the context might fail after a runtime limit was reached
            LOGGER.warn("Error closing QuickJS context", e);
        }
        contextPtr = 0;
    }
}
