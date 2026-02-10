package io.github.stefanrichterhuber.quickjswasmjava;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.msgpack.core.MessageFormat;
import org.msgpack.core.MessagePack;
import org.msgpack.core.MessagePacker;
import org.msgpack.core.MessageUnpacker;
import org.msgpack.value.ValueType;

import com.dylibso.chicory.runtime.ExportFunction;
import com.dylibso.chicory.runtime.Instance;

public class QuickJSContext implements AutoCloseable {
    private static final Logger LOGGER = LogManager.getLogger();

    private final long contextPtr;
    private final QuickJSRuntime runtime;
    private final ExportFunction eval;
    private final ExportFunction createContext;
    private final ExportFunction setGlobal;
    @SuppressWarnings("unused")
    private final ExportFunction closeContext;

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
        this.contextPtr = createContext.apply(runtime.getRuntimePointer())[0];

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

        } catch (IOException e) {
            throw new RuntimeException("Error calling host function", e);
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
        byte[] scriptBytes = script.getBytes();
        int scriptBytesLen = scriptBytes.length;

        long ptr = runtime.alloc(scriptBytesLen);
        runtime.getInstance().memory().writeString((int) ptr, script, StandardCharsets.UTF_8);
        LOGGER.debug("Allocated memory for script: {} with length {}", ptr, scriptBytesLen);
        try {
            runtime.scriptStarted();
            long[] result = eval.apply(contextPtr, ptr, scriptBytesLen);
            // Read the result with messagepack java, it is a pointer and a length to a
            // message pack object

            // Cleanup the memory location after reading the result
            try (final MemoryLocation resultLocation = MemoryLocation.unpack(result[0], runtime)) {
                final Object r = unpackObjectFromMemory(resultLocation);
                return r;
            }
        } finally {
            runtime.dealloc(ptr, scriptBytesLen);
            runtime.scriptFinished();
        }
    }

    /**
     * Sets a global variable in the QuickJS context.
     * 
     * @param name  The name of the global variable.
     * @param value The value of the global variable.
     * @throws IOException If the global variable cannot be set.
     */
    public void setGlobal(String name, Object value) throws IOException {

        LOGGER.debug("Setting global: {} = {}", name, value);

        // We must not close the memory location here, because it is used by the
        // set global function
        final MemoryLocation nameLocation = this.getRuntime().writeToMemory(name);
        // We must not close the memory location here, because it is used by the
        // set global function
        final MemoryLocation valueLocation = this.writeToMemory(value);
        // Then call the set global function
        setGlobal.apply(contextPtr, nameLocation.pointer(), nameLocation.length(), valueLocation.pointer(),
                valueLocation.length());

    }

    /**
     * Writes an object to the memory of the QuickJS runtime.
     * 
     * @param data The object to write to the memory.
     * @return The memory location of the object.
     * @throws IOException If the object cannot be written to the memory.
     */
    MemoryLocation writeToMemory(Object data) throws IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final MessagePacker packer = MessagePack.newDefaultPacker(out);
        packObject(data, packer);
        packer.close();
        final byte[] valueBytes = out.toByteArray();
        return this.getRuntime().writeToMemory(valueBytes);
    }

    /**
     * Returns the pointer to the QuickJS context.
     * 
     * @return The pointer to the QuickJS context.
     */
    long getContextPointer() {
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
        } else {
            if (obj instanceof Double) {
                // Here we must create the map first
                packer.packMapHeader(1);
                packer.packString("float");
                packer.packDouble(((Double) obj).doubleValue());
            } else if (obj instanceof Integer) {
                // Here we must create the map first
                packer.packMapHeader(1);
                packer.packString("int");
                packer.packInt(((Integer) obj).intValue());
            } else if (obj instanceof Boolean) {
                // Here we must create the map first
                packer.packMapHeader(1);
                packer.packString("boolean");
                packer.packBoolean(((Boolean) obj).booleanValue());
            } else if (obj instanceof String) {
                // Here we must create the map first
                packer.packMapHeader(1);
                packer.packString("string");
                packer.packString((String) obj);
            } else if (obj instanceof List) {
                // Here we must create the map first
                packer.packMapHeader(1);
                packer.packString("array");
                packer.packArrayHeader(((List<?>) obj).size());
                for (Object item : (List<?>) obj) {
                    packObject(item, packer);
                }
            } else if (obj instanceof Map) {
                // Here we must create the map first
                packer.packMapHeader(1);
                packer.packString("object");
                packer.packMapHeader(((Map<?, ?>) obj).size());
                for (Map.Entry<?, ?> entry : ((Map<?, ?>) obj).entrySet()) {
                    packer.packString((String) entry.getKey());
                    packObject(entry.getValue(), packer);
                }
            } else if (obj instanceof Function) {
                // Here we must create the map first
                packer.packMapHeader(1);
                packer.packString("javaFunction");

                packer.packArrayHeader(2);
                packer.packInt((int) this.getContextPointer());
                packer.packInt((int) hostFunctions.size());
                hostFunctions.add((Function<List<Object>, Object>) obj);
            } else {
                throw new RuntimeException("Unsupported type: " + obj.getClass().getName());
            }

        }
    }

    /**
     * Unpacks an object from a memory location.
     * 
     * @param memoryLocation The memory location to unpack the object from.
     * @return The unpacked object.
     * @throws IOException If the object cannot be unpacked.
     */
    Object unpackObjectFromMemory(MemoryLocation memoryLocation) throws IOException {
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
    Object unpackObjectFromMemory(Instance instance, long ptr, long len) throws IOException {
        final byte[] bytes = instance.memory().readBytes((int) ptr, (int) len);
        MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(bytes);
        return unpackObject(unpacker);
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
                    int arraySize = unpacker.unpackArrayHeader();
                    List<Object> array = new ArrayList<>();
                    for (int i = 0; i < arraySize; i++) {
                        array.add(unpackObject(unpacker));
                    }
                    return array;
                }
                case "object":
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

    @Override
    public void close() throws Exception {
        // TODO implement cleanup
    }
}
