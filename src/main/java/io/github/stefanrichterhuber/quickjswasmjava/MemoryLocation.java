package io.github.stefanrichterhuber.quickjswasmjava;

/**
 * Represents a location in the QuickJS memory.
 */
record MemoryLocation(long pointer, int length, QuickJSRuntime runtime) implements AutoCloseable {
    /**
     * Frees the memory location.
     */
    public void free() {
        runtime.dealloc(pointer, length);
    }

    @Override
    public void close() {
        free();
    }

    /**
     * Unpacks a memory location from a long.
     * The lower 32 bits are the pointer and the upper 32 bits are the length.
     * 
     * @param packed  The packed memory location.
     * @param runtime The runtime.
     * @return The unpacked memory location.
     */
    static MemoryLocation unpack(long packed, QuickJSRuntime runtime) {
        final int resultLen = (int) (packed & 0xffffffff);
        final int resultPtr = (int) ((packed >> 32) & 0xffffffff);
        return new MemoryLocation(resultPtr, resultLen, runtime);
    }

    /**
     * Packs the memory location into a long.
     * The lower 32 bits are the pointer and the upper 32 bits are the length.
     * 
     * @return The packed memory location.
     */
    long pack() {
        final int resultPtr = (int) (pointer & 0xffffffff);
        final int resultLen = (int) (length & 0xffffffff);
        return (long) resultLen | ((long) resultPtr << 32);
    }
}
