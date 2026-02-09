package io.github.stefanrichterhuber.quickjswasmjava;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.msgpack.core.MessageFormat;
import org.msgpack.core.MessagePack;
import org.msgpack.core.MessagePacker;
import org.msgpack.core.MessageUnpacker;
import org.msgpack.value.ValueType;

import com.dylibso.chicory.runtime.ExportFunction;

public class QuickJSContext implements AutoCloseable {

    private final long contextPtr;
    private final QuickJSRuntime runtime;
    private final ExportFunction eval;
    private final ExportFunction createContext;
    private final ExportFunction setGlobal;
    private final ExportFunction closeContext;

    QuickJSContext(QuickJSRuntime runtime) {
        this.runtime = runtime;
        this.createContext = runtime.getInstance().export("create_context_wasm");
        this.closeContext = runtime.getInstance().export("close_context_wasm");
        this.eval = runtime.getInstance().export("eval_script_wasm");
        this.setGlobal = runtime.getInstance().export("set_global_wasm");
        this.contextPtr = createContext.apply(runtime.getRuntimePointer())[0];
    }

    public Object eval(String script) throws IOException {
        byte[] scriptBytes = script.getBytes();
        int scriptBytesLen = scriptBytes.length;

        long ptr = runtime.alloc(scriptBytesLen);
        runtime.getInstance().memory().writeString((int) ptr, script, StandardCharsets.UTF_8);
        System.out.println("Allocated memory for script: " + ptr + " with length " + scriptBytesLen);
        try {
            long[] result = eval.apply(contextPtr, ptr, scriptBytesLen);
            // Read the result with messagepack java, it is a pointer and a length to a
            // message pack object

            MessageUnpacker unpacker = runtime.unpackBytesFromMemory(result);
            Object r = from(unpacker);
            return r;
        } finally {
            runtime.dealloc(ptr, scriptBytesLen);
        }
    }

    public void setGlobal(String name, Object value) throws IOException {
        // First write the name and the valueto the memory
        try (MemoryLocation nameLocation = this.getRuntime().writeToMemory(name);
                MemoryLocation valueLocation = this.writeToMemory(value)) {

            // Then call the set global function
            setGlobal.apply(contextPtr, nameLocation.pointer(), nameLocation.length(), valueLocation.pointer(),
                    valueLocation.length());
        }

    }

    MemoryLocation writeToMemory(Object data) throws IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final MessagePacker packer = MessagePack.newDefaultPacker(out);
        to(data, packer);
        packer.close();
        final byte[] valueBytes = out.toByteArray();
        return this.getRuntime().writeToMemory(valueBytes);
    }

    long getContextPointer() {
        return contextPtr;
    }

    QuickJSRuntime getRuntime() {
        return runtime;
    }

    void to(Object obj, MessagePacker packer) throws IOException {
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
                    to(item, packer);
                }
            } else if (obj instanceof Map) {
                // Here we must create the map first
                packer.packMapHeader(1);
                packer.packString("object");
                packer.packMapHeader(((Map<?, ?>) obj).size());
                for (Map.Entry<?, ?> entry : ((Map<?, ?>) obj).entrySet()) {
                    packer.packString((String) entry.getKey());
                    to(entry.getValue(), packer);
                }
            } else {
                throw new RuntimeException("Unsupported type: " + obj.getClass().getName());
            }

        }
    }

    Object from(MessageUnpacker unpacker) throws IOException {
        // First field is the type
        MessageFormat format = unpacker.getNextFormat();
        ValueType valueType = format.getValueType();

        if (valueType == ValueType.MAP) {
            final int mapSize = unpacker.unpackMapHeader();
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
                        array.add(from(unpacker));
                    }
                    return array;
                }
                case "object":
                    int objectSize = unpacker.unpackMapHeader();
                    Map<String, Object> object = new HashMap<>();
                    for (int i = 0; i < objectSize; i++) {
                        String key = unpacker.unpackString();
                        object.put(key, from(unpacker));
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
