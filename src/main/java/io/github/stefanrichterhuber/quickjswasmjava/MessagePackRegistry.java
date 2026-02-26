package io.github.stefanrichterhuber.quickjswasmjava;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.msgpack.core.MessagePack;
import org.msgpack.core.MessagePacker;
import org.msgpack.core.MessageUnpacker;
import org.msgpack.value.ValueType;

/**
 * Utility class to pack / unpack supported java object into the format common
 * with the native library
 */
class MessagePackRegistry {
    private static interface TypeHandler {
        void pack(Object o, MessagePacker p) throws IOException;

        Object unpack(MessageUnpacker u) throws IOException;
    }

    private final Map<String, TypeHandler> handlers = new HashMap<>();
    // Required to maintain order of registered handlers to find handlers for more
    // specialised object (QuickJSArray) before generic ones (List)
    private final Map<Class<?>, String> classToTag = new LinkedHashMap<>();
    private final QuickJSContext ctx;

    /**
     * Registers a new pack / unpack handler for the given type(s)
     * 
     * @param tag     Tag within the packed structure
     * @param clazz   List of java types to map
     * @param handler Handler for packing / unpacking
     */
    private void register(String tag, List<Class<?>> clazz, TypeHandler handler) {
        handlers.put(tag, handler);

        clazz.forEach(c -> {
            classToTag.put(c, tag);
        });

    }

    /**
     * Creates a new MessagePackRegistry instance for the given QuickJSContext
     * 
     * @param ctx QuickJSContext to use
     */
    public MessagePackRegistry(QuickJSContext ctx) {
        this.ctx = ctx;
        register("string", List.of(String.class), new TypeHandler() {
            public void pack(Object o, MessagePacker p) throws IOException {
                p.packString((String) o);
            }

            public Object unpack(MessageUnpacker u) throws IOException {
                return u.unpackString();
            }
        });

        register("float", List.of(Double.class, Float.class), new TypeHandler() {
            public void pack(Object o, MessagePacker p) throws IOException {
                p.packDouble(((Number) o).doubleValue());
            }

            public Object unpack(MessageUnpacker u) throws IOException {
                return u.unpackDouble();
            }
        });

        register("boolean", List.of(Boolean.class), new TypeHandler() {
            public void pack(Object o, MessagePacker p) throws IOException {
                p.packBoolean(((Boolean) o).booleanValue());
            }

            public Object unpack(MessageUnpacker u) throws IOException {
                return u.unpackBoolean();
            }
        });

        register("int", List.of(Integer.class), new TypeHandler() {
            public void pack(Object o, MessagePacker p) throws IOException {
                p.packInt(((Integer) o).intValue());
            }

            public Object unpack(MessageUnpacker u) throws IOException {
                return u.unpackInt();
            }
        });

        register("nativeArray", List.of(QuickJSArray.class), new TypeHandler() {
            public void pack(Object o, MessagePacker p) throws IOException {
                p.packLong(((QuickJSArray<?>) o).getArrayPointer());
            }

            public Object unpack(MessageUnpacker u) throws IOException {
                long pointer = u.unpackLong();
                return new QuickJSArray<>(MessagePackRegistry.this.ctx, pointer);
            }
        });

        register("nativeObject", List.of(QuickJSObject.class), new TypeHandler() {
            public void pack(Object o, MessagePacker p) throws IOException {
                p.packLong(((QuickJSObject<?, ?>) o).getObjectPointer());
            }

            public Object unpack(MessageUnpacker u) throws IOException {
                long pointer = u.unpackLong();
                return new QuickJSObject<>(MessagePackRegistry.this.ctx, pointer);
            }
        });

        register("array", List.of(List.class), new TypeHandler() {
            public void pack(Object o, MessagePacker p) throws IOException {
                p.packArrayHeader(((List<?>) o).size());
                for (Object item : (List<?>) o) {
                    MessagePackRegistry.this.pack(item, p);
                }
            }

            public Object unpack(MessageUnpacker u) throws IOException {
                int arraySize = u.unpackArrayHeader();
                List<Object> array = new ArrayList<>();
                for (int i = 0; i < arraySize; i++) {
                    array.add(MessagePackRegistry.this.unpack(u));
                }
                return array;
            }
        });

        register("object", List.of(Map.class), new TypeHandler() {
            public void pack(Object o, MessagePacker p) throws IOException {
                p.packMapHeader(((Map<?, ?>) o).size());
                for (Map.Entry<?, ?> entry : ((Map<?, ?>) o).entrySet()) {
                    p.packString(entry.getKey().toString());
                    MessagePackRegistry.this.pack(entry.getValue(), p);
                }
            }

            public Object unpack(MessageUnpacker u) throws IOException {
                int objectSize = u.unpackMapHeader();
                Map<String, Object> object = new HashMap<>();
                for (int i = 0; i < objectSize; i++) {
                    String key = u.unpackString();
                    object.put(key, MessagePackRegistry.this.unpack(u));
                }
                return object;
            }
        });

        register("function", List.of(QuickJSFunction.class), new TypeHandler() {
            public void pack(Object o, MessagePacker p) throws IOException {
                p.packArrayHeader(2);
                p.packString(((QuickJSFunction) o).getName());
                p.packLong(((QuickJSFunction) o).getFunctionPointer());
            }

            public Object unpack(MessageUnpacker u) throws IOException {
                int arraySize = u.unpackArrayHeader();
                if (arraySize != 2) {
                    throw new RuntimeException("Expected array with 2 element (function name, function ptr)");
                }
                String functionName = u.unpackString();
                long functionPtr = u.unpackLong();
                return new QuickJSFunction(MessagePackRegistry.this.ctx, functionName, functionPtr);
            }
        });

        register("javaFunction", List.of(Function.class), new TypeHandler() {
            public void pack(Object o, MessagePacker p) throws IOException {
                p.packArrayHeader(2);
                p.packInt((int) MessagePackRegistry.this.ctx.getContextPointer());
                p.packInt((int) MessagePackRegistry.this.ctx.hostFunctions.size());
                MessagePackRegistry.this.ctx.hostFunctions.add((Function<List<Object>, Object>) o);
            }

            public Object unpack(MessageUnpacker u) throws IOException {
                // Impossible to enter since, one would always get back a 'function', wrapping
                // the Java function

                int arraySize = u.unpackArrayHeader();
                if (arraySize != 2) {
                    throw new RuntimeException("Expected array with 2 element (context ptr, function index)");
                }
                final int contextPtr = u.unpackInt();
                final int functionIndex = u.unpackInt();
                if (contextPtr != MessagePackRegistry.this.ctx.getContextPointer()) {
                    throw new RuntimeException("Context pointer does not match");
                }
                return MessagePackRegistry.this.ctx.hostFunctions.get(functionIndex);
            }
        });

        register("exception", List.of(Exception.class), new TypeHandler() {
            public void pack(Object o, MessagePacker p) throws IOException {
                p.packArrayHeader(2);
                p.packString(((Exception) o).getMessage());
                p.packString(
                        Arrays.asList(((Exception) o).getStackTrace()).stream().map(Object::toString)
                                .collect(Collectors.joining("\n")));
            }

            public Object unpack(MessageUnpacker u) throws IOException {
                int arraySize = u.unpackArrayHeader();
                if (arraySize != 2) {
                    throw new RuntimeException(
                            "Expected array with 2 element (exception message, exception stack)");
                }
                String message = u.unpackString();
                String stack = u.unpackString();
                return new QuickJSException(message, stack);
            }
        });

        register("completableFuture", List.of(CompletableFuture.class), new TypeHandler() {
            public void pack(Object o, MessagePacker p) throws IOException {
                // First check if this is an already registred completable future
                int index = MessagePackRegistry.this.ctx.completableFutures.indexOf(o);
                if (index == -1) {
                    MessagePackRegistry.this.ctx.completableFutures.add((CompletableFuture<Object>) o);
                    index = MessagePackRegistry.this.ctx.completableFutures.size() - 1;
                }

                // Then check for a promise pointer -> available if it is a QuickJSPromise
                long promisePtr = 0l;
                if (o instanceof QuickJSPromise) {
                    promisePtr = ((QuickJSPromise) o).getPromisePointer();
                }
                p.packArrayHeader(2);
                p.packInt(index);
                p.packLong(promisePtr);
            }

            public Object unpack(MessageUnpacker u) throws IOException {
                final int arraySize = u.unpackArrayHeader();
                if (arraySize != 2) {
                    throw new RuntimeException(
                            "Expected completableFuture with 2 element (completable futre pointer, promise pointer)");
                }
                final int futurePtr = u.unpackInt();
                final long promisePtr = u.unpackLong();

                // TODO wrap into a completablefuture with promise ptr
                final CompletableFuture<Object> f = MessagePackRegistry.this.ctx.completableFutures.get(futurePtr);
                if (f == null) {
                    throw new IllegalStateException("No future for future ptr " + futurePtr + " found");
                }
                return f;
            }
        });

    }

    /**
     * Unpacks a java object from the given byte array containing a message
     * packed structure
     * 
     * @param obj byte array containing the object
     * @return The object
     */
    Object unpack(byte[] obj) {
        MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(obj);
        try {
            return this.unpack(unpacker);
        } catch (IOException e) {
            throw new RuntimeException("Unable to unpack object", e);
        }
    }

    /**
     * Unpacks a java object from the given MessageUnpacker
     * 
     * @param unpacker MessageUnpacker
     * @return The object
     */
    Object unpack(MessageUnpacker unpacker) throws IOException {
        ValueType type = unpacker.getNextFormat().getValueType();

        if (type == ValueType.STRING) {
            String val = unpacker.unpackString();
            return val.equals("null") || val.equals("undefined") ? null : val;
        }

        if (type == ValueType.MAP) {
            unpacker.unpackMapHeader(); // Should be 1
            String tag = unpacker.unpackString();
            TypeHandler handler = handlers.get(tag);
            if (handler == null)
                throw new IOException("Unknown type tag: " + tag);
            return handler.unpack(unpacker);
        }
        return null;
    }

    /**
     * Packs the object content into a byte array using message pack
     * 
     * @param obj Object to pack (null values supported)
     * @return byte array containing the packed object
     */
    byte[] pack(Object obj) {
        try {
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (final MessagePacker packer = MessagePack.newDefaultPacker(out)) {
                pack(obj, packer);
            }
            final byte[] valueBytes = out.toByteArray();
            return valueBytes;
        } catch (IOException e) {
            throw new RuntimeException("Unable to pack object: " + obj, e);
        }
    }

    /**
     * Packs the object content into a MessagePacker
     * 
     * @param obj    Object to pack (null values supported)
     * @param packer MessagePacker to use
     * @return byte array containing the packed object
     */
    void pack(Object obj, MessagePacker packer) throws IOException {
        if (obj == null) {
            packer.packString("null");
            return;
        }

        // Find the best matching tag based on class hierarchy
        String tag = classToTag.entrySet().stream()
                .filter(entry -> entry.getKey().isInstance(obj))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElseThrow(() -> new RuntimeException("No handler for " + obj.getClass()));

        packer.packMapHeader(1);
        packer.packString(tag);
        handlers.get(tag).pack(obj, packer);
    }
}
