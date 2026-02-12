package io.github.stefanrichterhuber.quickjswasmjava;

import java.util.AbstractList;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.dylibso.chicory.runtime.ExportFunction;

/**
 * Java wrapper of a native JS array. All change to the js array are visible in
 * the java wrapper and vice versa
 * 
 * @param <T> Type of values in the array. All values types allowed by QuickJS
 *            are supported, including maps and other lists.
 */
public final class QuickJSArray<T> extends AbstractList<T> {
    private static final Logger LOGGER = LogManager.getLogger();

    /**
     * The context this function belongs to.
     */
    private final QuickJSContext context;
    /**
     * The pointer to the js array in the wasm library.
     */
    private long arrayPtr;

    private final ExportFunction size;

    private final ExportFunction add;

    private final ExportFunction set;

    private final ExportFunction remove;

    private final ExportFunction get;

    private final ExportFunction close;

    /**
     * Creates a new native JS array
     * 
     * @param context QuickJS context
     */
    public QuickJSArray(final QuickJSContext context) {
        this(context, createNativeArray(context));
    }

    /**
     * Creates a new native JS array and copies the content of the iterable into
     * this array
     * 
     * @param context QuickJS context
     * @param data    Data to copy into this array
     */
    public QuickJSArray(final QuickJSContext context, final Iterable<T> data) {
        this(context);
        if (data == null) {
            throw new NullPointerException("data must not be null");
        }
        for (T d : data) {
            this.add(d);
        }
    }

    /**
     * Creates a java wrapper of an existing native JS array
     * 
     * @param context  QuickJS context
     * @param arrayPtr Pointer to the native array
     */
    QuickJSArray(final QuickJSContext context, long arrayPtr) {
        LOGGER.debug("Created wrapper for native JS array with pointer {}", arrayPtr);
        this.size = context.getRuntime().getInstance().export("array_size_wasm");
        this.add = context.getRuntime().getInstance().export("array_add_wasm");
        this.set = context.getRuntime().getInstance().export("array_set_wasm");
        // this.close = context.getRuntime().getInstance().export("array_close_wasm");
        this.remove = context.getRuntime().getInstance().export("array_remove_wasm");
        this.get = context.getRuntime().getInstance().export("array_get_wasm");
        this.close = context.getRuntime().getInstance().export("array_close_wasm");

        this.arrayPtr = arrayPtr;
        this.context = context;

        this.context.addDependendResource(this::close);
    }

    /**
     * Creates a native array in the js runtime for the given context
     * 
     * @param context QuickJS context
     * @return native pointer to the js array
     */
    private static long createNativeArray(QuickJSContext context) {
        ExportFunction create = context.getRuntime().getInstance().export("array_create_wasm");
        long[] result = create.apply(context.getContextPointer());
        return result[0];
    }

    /**
     * Returns the native pointer to the js array
     * 
     * @return native pointer to the js array
     */
    long getArrayPointer() {
        if (arrayPtr == 0) {
            throw new IllegalStateException("QuickJSArray already closed");
        }
        return arrayPtr;
    }

    /**
     * Returns the native pointer to the js context
     * 
     * @return native pointer to the js context
     */
    private long getContextPointer() {
        return this.context.getContextPointer();
    }

    /**
     * Closes this native function, after that it is no longer usable!
     * 
     * @throws Exception
     */
    private void close() throws Exception {
        if (arrayPtr == 0) {
            return;
        }
        try {
            this.close.apply(this.getContextPointer(), this.getArrayPointer());
        } catch (Exception e) {
            // May fail
            LOGGER.debug("Failed to close native array", e);
        }
        arrayPtr = 0;
    }

    @Override
    public int size() {
        long[] result = size.apply(getContextPointer(), getArrayPointer());
        return (int) result[0];
    }

    @Override
    public T get(int index) {
        if (index < 0 || index >= size()) {
            throw new IndexOutOfBoundsException("Invalid index: " + index);
        }

        final long[] result = get.apply(getContextPointer(), getArrayPointer(), index);

        // Cleanup the memory location after reading the result
        try (final MemoryLocation resultLocation = MemoryLocation.unpack(result[0], context.getRuntime())) {
            final Object r = context.unpackObjectFromMemory(resultLocation);
            if (r instanceof RuntimeException) {
                throw (RuntimeException) r;
            } else {
                return (T) r;
            }
        }
    }

    @Override
    public boolean add(T value) {
        final int len = size();

        try (final MemoryLocation valueLocation = this.context.writeToMemory(value)) {
            final long[] result = this.set.apply(this.getContextPointer(), this.getArrayPointer(), len,
                    valueLocation.pointer(),
                    valueLocation.length());

            return result[0] == 1;
        }
    }

    @Override
    public void add(int index, T value) {
        if (index < 0 || index > size()) {
            throw new IndexOutOfBoundsException("Invalid index: " + index);
        }
        try (final MemoryLocation valueLocation = this.context.writeToMemory(value)) {
            this.add.apply(this.getContextPointer(), this.getArrayPointer(), index, valueLocation.pointer(),
                    valueLocation.length());
        }
    }

    @Override
    public T set(int index, T value) {
        if (index < 0 || index >= size()) {
            throw new IndexOutOfBoundsException("Invalid index: " + index);
        }

        final T oldValue = get(index);

        try (final MemoryLocation valueLocation = this.context.writeToMemory(value)) {
            this.set.apply(this.getContextPointer(), this.getArrayPointer(), index, valueLocation.pointer(),
                    valueLocation.length());
        }

        return oldValue;
    }

    @Override
    public T remove(int index) {
        if (index < 0 || index >= size()) {
            throw new IndexOutOfBoundsException("Invalid index: " + index);
        }
        T oldValue = this.get(index);

        this.remove.apply(this.getContextPointer(), this.getArrayPointer(), index);

        return oldValue;
    }

    @Override
    public boolean equals(Object other) {
        // Shortcut test if both arrays point to the same native arrays
        if (other instanceof QuickJSArray) {
            if (((QuickJSArray<T>) other).getArrayPointer() == getArrayPointer()) {
                return true;
            }
        }
        return super.equals(other);
    }
}
