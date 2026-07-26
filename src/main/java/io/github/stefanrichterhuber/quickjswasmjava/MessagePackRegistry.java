package io.github.stefanrichterhuber.quickjswasmjava;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
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
    // Caches the wire-format wrapper for a given Java callback (Function,
    // Consumer, VarArgFunction, ...) by identity, so packing the *same*
    // callback instance repeatedly reuses its existing entry in
    // ctx.hostFunctions instead of registering a new one every time.
    private final Map<Object, Function<List<Object>, Object>> hostFunctionCache = new IdentityHashMap<>();

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
                // wrapFunction() already registered this wrapper (or reused an
                // existing registration for the same original callback), so just
                // look up its stable index here instead of adding it again.
                final int index = MessagePackRegistry.this.ctx.hostFunctions.indexOf(o);
                if (index < 0) {
                    throw new IllegalStateException("Java function was not registered before packing: " + o);
                }
                p.packArrayHeader(2);
                p.packInt((int) MessagePackRegistry.this.ctx.getContextPointer());
                p.packInt(index);
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

        register("completableFuture", List.of(CompletionStage.class), new TypeHandler() {
            public void pack(Object o, MessagePacker p) throws IOException {
                if (o instanceof CompletionStage cf) {
                    // Ensure the completablefuture is properly wrapped
                    final QuickJSPromise promise = QuickJSPromise.wrap(cf, MessagePackRegistry.this.ctx);

                    // First check if this is an already registred completable future
                    final int index = promise.getCompletableFuturePointer();

                    // Then check for a promise pointer -> available if it is a QuickJSPromise
                    final long promisePtr = promise.getPromisePointer();
                    p.packArrayHeader(2);
                    p.packInt(index);
                    p.packLong(promisePtr);
                } else {
                    throw new IllegalStateException(
                            "Calling pack for CompletionStage on an object which is not a CompletionStage: " + o);
                }
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
                if (f instanceof QuickJSPromise p) {
                    if (p.getPromisePointer() != promisePtr) {
                        throw new IllegalStateException(
                                "Promise pointer does not match for completable future " + futurePtr);
                    }
                }
                return f;
            }
        });

    }

    /**
     * Clears the host function identity cache. Called when the owning
     * QuickJSContext is closed so cached callbacks don't outlive it.
     */
    void clearHostFunctionCache() {
        hostFunctionCache.clear();
    }

    /**
     * Unpacks a java object from the given byte array containing a message
     * packed structure
     * 
     * @param obj byte array containing the object
     * @return The object
     */
    Object unpack(byte[] obj) {
        try (MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(obj)) {
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
        final Object wrappedObj = wrapFunction(obj);

        // Find the best matching tag based on class hierarchy
        String tag = classToTag.entrySet().stream()
                .filter(entry -> entry.getKey().isInstance(wrappedObj))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElseThrow(() -> new RuntimeException("No handler for " + wrappedObj.getClass()));

        packer.packMapHeader(1);
        packer.packString(tag);
        handlers.get(tag).pack(wrappedObj, packer);
    }

    /**
     * If the given object is Function, BiFunction, Consumer, BiConsumer or Supplier
     * wrap it into a suitable Function<List<Object>, Object> to hand into the js
     * context. Repeated calls with the same (by identity) callback instance
     * return the previously created wrapper instead of creating and registering
     * a new host function every time.
     *
     * @param Object to wrap
     * @return wrapped function or unmodified value
     */
    private Object wrapFunction(Object obj) {
        if (obj == null) {
            return null;
        }
        if (obj instanceof QuickJSFunction f) {
            return f;
        }
        final Function<List<Object>, Object> cached = hostFunctionCache.get(obj);
        if (cached != null) {
            return cached;
        }
        final Function<List<Object>, Object> wrapped = buildWireFunction(obj);
        if (wrapped == null) {
            return obj;
        }
        ctx.hostFunctions.add(wrapped);
        hostFunctionCache.put(obj, wrapped);
        return wrapped;
    }

    /**
     * Builds the actual Function<List<Object>, Object> adapter for a supported
     * callback shape (VarArgFunction, Function, BiFunction, Consumer,
     * BiConsumer or Supplier).
     *
     * @param obj callback to adapt
     * @return the adapter, or null if obj is not a supported callback shape
     */
    private Function<List<Object>, Object> buildWireFunction(Object obj) {
        if (obj instanceof VarArgFunction f) {
            return (args) -> f.apply(args.toArray());
        }
        if (obj instanceof Function f) {
            @SuppressWarnings("unchecked")
            final Function<Object, Object> typed = f;
            return (args) -> {
                final Object arg = args != null && args.size() > 0 ? args.get(0) : null;
                return typed.apply(arg);
            };
        }
        if (obj instanceof BiFunction f) {
            @SuppressWarnings("unchecked")
            final BiFunction<Object, Object, Object> typed = f;
            return (args) -> {
                final Object arg0 = args != null && args.size() > 0 ? args.get(0) : null;
                final Object arg1 = args != null && args.size() > 1 ? args.get(1) : null;
                return typed.apply(arg0, arg1);
            };
        }
        if (obj instanceof Consumer f) {
            @SuppressWarnings("unchecked")
            final Consumer<Object> typed = f;
            return (args) -> {
                final Object arg0 = args != null && args.size() > 0 ? args.get(0) : null;
                typed.accept(arg0);
                return null;
            };
        }
        if (obj instanceof BiConsumer f) {
            @SuppressWarnings("unchecked")
            final BiConsumer<Object, Object> typed = f;
            return (args) -> {
                final Object arg0 = args != null && args.size() > 0 ? args.get(0) : null;
                final Object arg1 = args != null && args.size() > 1 ? args.get(1) : null;
                typed.accept(arg0, arg1);
                return null;
            };
        }
        if (obj instanceof Supplier f) {
            return (args) -> f.get();
        }
        return null;
    }
}
