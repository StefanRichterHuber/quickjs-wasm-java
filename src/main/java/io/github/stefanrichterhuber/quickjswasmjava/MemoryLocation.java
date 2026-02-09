package io.github.stefanrichterhuber.quickjswasmjava;

/**
 * Represents a location in the QuickJS memory.
 */
public record MemoryLocation(long pointer, int length, QuickJSRuntime runtime) implements AutoCloseable {
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
}
