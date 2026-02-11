package io.github.stefanrichterhuber.quickjswasmjava;

import java.util.AbstractMap;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.dylibso.chicory.runtime.ExportFunction;

/**
 * Wrapper for native QuickJS objects. Exposed in the form of a Java Map.
 * 
 * @param <K> type of the keys in the object. Must be a String, Number or
 *            Boolean.
 * @param <V> type of the values in the object. Any type that can be converted
 *            to a QuickJS value (including other maps)
 */
public final class QuickJSObject<K, V> extends AbstractMap<K, V> {
    private static final Logger LOGGER = LogManager.getLogger(QuickJSObject.class);

    /**
     * Native pointer to js object
     */
    long objectPointer;

    /**
     * QuickJSContext this object is bound to.
     */
    private final QuickJSContext ctx;

    private final ExportFunction size;
    private final ExportFunction containsKey;
    private final ExportFunction getValue;
    private final ExportFunction removeValue;
    private final ExportFunction setValue;
    private final ExportFunction keySet;
    private final ExportFunction close;

    /**
     * Creates a java wrapper of an existing native JS object
     * 
     * @param ctx           QuickJS context
     * @param objectPointer Pointer to the native object
     */
    QuickJSObject(QuickJSContext ctx, long objectPointer) {
        this.ctx = ctx;
        this.objectPointer = objectPointer;
        this.ctx.addDependendResource(this::close);
        this.containsKey = ctx.getRuntime().getInstance().export("object_contains_key_wasm");
        this.getValue = ctx.getRuntime().getInstance().export("object_get_value_wasm");
        this.setValue = ctx.getRuntime().getInstance().export("object_set_value_wasm");
        this.removeValue = ctx.getRuntime().getInstance().export("object_remove_value_wasm");
        this.size = ctx.getRuntime().getInstance().export("object_size_wasm");
        this.keySet = ctx.getRuntime().getInstance().export("object_key_set_wasm");
        this.close = ctx.getRuntime().getInstance().export("object_close_wasm");
    }

    /**
     * Creates a new native JS object
     * 
     * @param ctx QuickJS context
     */
    public QuickJSObject(QuickJSContext ctx) {
        this(ctx, createNativeObject(ctx));
    }

    /**
     * Creates a new native JS object from a existing java map. The src map is
     * copied to the native object!
     * 
     * @param ctx QuickJS context
     * @param src the Map to copy
     */
    public QuickJSObject(QuickJSContext ctx, final Map<K, V> src) {
        this(ctx, createNativeObject(ctx));
        if (src == null) {
            throw new NullPointerException("src must not be null");
        }
        this.putAll(src);
    }

    private long getContextPointer() {
        return this.ctx.getContextPointer();
    }

    long getObjectPointer() {
        return this.objectPointer;
    }

    /**
     * Creates a native JS object
     * 
     * @param ctx QuickJS context
     * @return Pointer to the native object
     */
    private static long createNativeObject(QuickJSContext ctx) {
        final ExportFunction create = ctx.getRuntime().getInstance().export("object_create_wasm");
        final long[] result = create.apply(ctx.getContextPointer());
        return result[0];
    }

    /**
     * Closes the native QuickJS object. Makes the object invalid and this
     * instance unusable.
     * 
     * @throws Exception
     */
    private void close() throws Exception {
        if (objectPointer == 0) {
            return;
        }
        try {
            this.close.apply(this.getContextPointer(), this.getObjectPointer());
        } catch (Exception e) {
            // May fail
            LOGGER.debug("Failed to close native array", e);
        }
        objectPointer = 0;
    }

    @Override
    public boolean containsKey(Object key) {
        try (final MemoryLocation keyLocation = this.ctx.writeToMemory(key)) {
            long[] result = this.containsKey.apply(this.getContextPointer(), this.getObjectPointer(),
                    keyLocation.pointer(), keyLocation.length());
            return result[0] != 0;
        }
    }

    @Override
    public boolean containsValue(Object value) {
        return values().contains(value);
    }

    /**
     * Unlike the standard Map.entrySet(), this method returns a snapshot of the
     * entry set at the time of the call. Changes to the underlying QuickJS object
     * after the call will only reflected on the values of the entry set, not on
     * the keys.
     */
    @Override
    public Set<Entry<K, V>> entrySet() {
        return keySet().stream().map(k -> new QuickJSObjectEntry<>(this, k)).collect(Collectors.toSet());
    }

    @Override
    public V get(Object key) {
        if (key == null) {
            throw new NullPointerException("Key must not be null");
        }
        try (final MemoryLocation keyLocation = this.ctx.writeToMemory(key)) {

            final long[] result = this.getValue.apply(this.getContextPointer(), this.getObjectPointer(),
                    keyLocation.pointer(), keyLocation.length());

            try (final MemoryLocation resultLocation = MemoryLocation.unpack(result[0], this.ctx.getRuntime())) {
                final Object r = this.ctx.unpackObjectFromMemory(resultLocation);
                if (r instanceof RuntimeException) {
                    throw (RuntimeException) r;
                } else {
                    return (V) r;
                }
            }
        }

    }

    @Override
    public boolean isEmpty() {
        return this.size() == 0;
    }

    @Override
    public Set<K> keySet() {
        final long[] result = this.keySet.apply(this.getContextPointer(), this.getObjectPointer());

        try (final MemoryLocation resultLocation = MemoryLocation.unpack(result[0], this.ctx.getRuntime())) {
            final Object r = this.ctx.unpackObjectFromMemory(resultLocation);
            if (r instanceof RuntimeException) {
                throw (RuntimeException) r;
            } else {
                // TODO create live view on the object
                if (r instanceof Collection) {
                    return new HashSet<>((Collection<K>) r);
                } else {
                    throw new RuntimeException("Result is not a collection");
                }
            }
        }
    }

    @Override
    public V put(K key, V value) {
        if (key == null) {
            throw new NullPointerException("Key must not be null");
        }

        final V oldValue = get(key);

        try (final MemoryLocation keyLocation = this.ctx.writeToMemory(key);
                final MemoryLocation valueLocation = this.ctx.writeToMemory(value)) {

            this.setValue.apply(this.getContextPointer(), this.getObjectPointer(), keyLocation.pointer(),
                    keyLocation.length(), valueLocation.pointer(), valueLocation.length());

        }
        return oldValue;
    }

    @Override
    public V remove(Object key) {
        final V value = get(key);

        try (final MemoryLocation keyLocation = this.ctx.writeToMemory(key)) {
            this.removeValue.apply(this.getContextPointer(), this.getObjectPointer(), keyLocation.pointer(),
                    keyLocation.length());
        }
        return value;
    }

    @Override
    public int size() {
        final long[] result = this.size.apply(this.getContextPointer(), this.getObjectPointer());
        return (int) result[0];
    }

    /**
     * Returns the values of the object. Unlike the standard Map.values() this is a
     * copy of the values at the time of the call and not a view on the object.
     * 
     * @return collection of values
     */
    @Override
    public Collection<V> values() {
        return entrySet().stream().map(Entry::getValue).collect(Collectors.toList());
    }
}