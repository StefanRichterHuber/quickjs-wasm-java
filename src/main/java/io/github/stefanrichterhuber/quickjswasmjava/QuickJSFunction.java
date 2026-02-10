package io.github.stefanrichterhuber.quickjswasmjava;

import java.io.IOException;
import java.util.List;
import java.util.function.Function;

import com.dylibso.chicory.runtime.ExportFunction;

/**
 * Represents a js native function
 */
public final class QuickJSFunction implements Function<Object[], Object> {
    private final QuickJSContext context;
    private final String name;
    private final long functionPtr;
    private final ExportFunction call;

    /**
     * Creates a new QuickJSFunction
     * 
     * @param context     the context to use
     * @param name        the name of the function
     * @param functionPtr the pointer to the function in the wasm library
     */
    QuickJSFunction(QuickJSContext context, String name, long functionPtr) {
        this.context = context;
        this.name = name;
        this.functionPtr = functionPtr;
        this.call = context.getRuntime().getInstance().export("call_function_wasm");
        context.addDependendResource(this::close);
    }

    /**
     * Calls the function with the given arguments
     * 
     * @param args the arguments to pass to the function
     * @return the result of the function call
     * @throws IOException if an I/O error occurs
     */
    public Object call(Object... args) throws IOException {
        // We create a message pack object from the arguments, with the root of the pack
        // being an array
        final List<Object> params = args == null || args.length == 0 ? List.of() : List.of(args);

        // Don't close the memory location here, because it will be used by the wasm
        // function
        final MemoryLocation memoryLocation = context.writeToMemory(params);
        final long[] result = call.apply(context.getContextPointer(), functionPtr, memoryLocation.pointer(),
                memoryLocation.length());

        // Cleanup the memory location after reading the result
        try (final MemoryLocation resultLocation = MemoryLocation.unpack(result[0], context.getRuntime())) {
            final Object r = context.unpackObjectFromMemory(resultLocation);
            return r;
        }

    }

    /**
     * Closes the function
     * 
     * @throws Exception
     */
    private void close() throws Exception {
        // TODO implement cleanup
    }

    /**
     * Returns the name of the function
     * 
     * @return
     */
    public String getName() {
        return name;
    }

    /**
     * Returns the pointer to the function in the wasm library
     * 
     * @return
     */
    long getFunctionPointer() {
        return functionPtr;
    }

    @Override
    public Object apply(Object[] arg0) {
        try {
            return call(arg0);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public String toString() {
        return String.format("function %s(...)", name);
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + (int) (functionPtr ^ (functionPtr >>> 32));
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (obj == null)
            return false;
        if (getClass() != obj.getClass())
            return false;
        QuickJSFunction other = (QuickJSFunction) obj;
        if (functionPtr != other.functionPtr)
            return false;
        return true;
    }
}
