package io.github.stefanrichterhuber.quickjswasmjava;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.msgpack.core.MessagePacker;
import org.msgpack.core.MessageUnpacker;

class MessagePackRegistry {
    private static interface TypeHandler {
        void pack(Object o, MessagePacker p, QuickJSContext c) throws IOException;

        Object unpack(MessageUnpacker u, QuickJSContext c) throws IOException;
    }

    private final Map<String, TypeHandler> handlers = new HashMap<>();
    private final Map<Class<?>, String> classToTag = new LinkedHashMap<>();

    private void register(String tag, Class<?> clazz, TypeHandler handler) {
        handlers.put(tag, handler);
        classToTag.put(clazz, tag);
    }

    public MessagePackRegistry(QuickJSContext ctx) {
        register("string", String.class, new TypeHandler() {
            public void pack(Object o, MessagePacker p, QuickJSContext c) throws IOException {
                // TODO implement
            }

            public Object unpack(MessageUnpacker u, QuickJSContext c) throws IOException {
                return u.unpackString();
            }
        });

        register("float", Float.class, new TypeHandler() {
            public void pack(Object o, MessagePacker p, QuickJSContext c) throws IOException {
                // TODO implement
            }

            public Object unpack(MessageUnpacker u, QuickJSContext c) throws IOException {
                return u.unpackDouble();
            }
        });

        register("boolean", Boolean.class, new TypeHandler() {
            public void pack(Object o, MessagePacker p, QuickJSContext c) throws IOException {
                // TODO implement
            }

            public Object unpack(MessageUnpacker u, QuickJSContext c) throws IOException {
                return u.unpackBoolean();
            }
        });

        register("int", Boolean.class, new TypeHandler() {
            public void pack(Object o, MessagePacker p, QuickJSContext c) throws IOException {
                // TODO implement
            }

            public Object unpack(MessageUnpacker u, QuickJSContext c) throws IOException {
                return u.unpackInt();
            }
        });

        register("array", List.class, new TypeHandler() {
            public void pack(Object o, MessagePacker p, QuickJSContext c) throws IOException {
                // TODO implement
            }

            public Object unpack(MessageUnpacker u, QuickJSContext c) throws IOException {
                int arraySize = u.unpackArrayHeader();
                List<Object> array = new ArrayList<>();
                for (int i = 0; i < arraySize; i++) {
                    array.add(unpackObject(u));
                }
                return array;
            }
        });

        register("object", Map.class, new TypeHandler() {
            public void pack(Object o, MessagePacker p, QuickJSContext c) throws IOException {
                // TODO implement
            }

            public Object unpack(MessageUnpacker u, QuickJSContext c) throws IOException {
                int objectSize = u.unpackMapHeader();
                Map<String, Object> object = new HashMap<>();
                for (int i = 0; i < objectSize; i++) {
                    String key = u.unpackString();
                    object.put(key, unpackObject(u));
                }
                return object;
            }
        });

        register("function", QuickJSFunction.class, new TypeHandler() {
            public void pack(Object o, MessagePacker p, QuickJSContext c) throws IOException {
                // TODO implement
            }

            public Object unpack(MessageUnpacker u, QuickJSContext c) throws IOException {
                int arraySize = u.unpackArrayHeader();
                if (arraySize != 2) {
                    throw new RuntimeException("Expected array with 2 element (function name, function ptr)");
                }
                String functionName = u.unpackString();
                long functionPtr = u.unpackLong();
                return new QuickJSFunction(c, functionName, functionPtr);
            }
        });

        register("javaFunction", QuickJSFunction.class, new TypeHandler() {
            public void pack(Object o, MessagePacker p, QuickJSContext c) throws IOException {
                // TODO implement
            }

            public Object unpack(MessageUnpacker u, QuickJSContext c) throws IOException {
                // Impossible to enter since, one would always get back a 'function', wrapping
                // the Java function

                int arraySize = u.unpackArrayHeader();
                if (arraySize != 2) {
                    throw new RuntimeException("Expected array with 2 element (context ptr, function index)");
                }
                final int contextPtr = u.unpackInt();
                final int functionIndex = u.unpackInt();
                if (contextPtr != this.getContextPointer()) {
                    throw new RuntimeException("Context pointer does not match");
                }
                return c.hostFunctions.get(functionIndex);
            }
        });
    }
}
