package io.github.stefanrichterhuber.quickjswasmjava;

import java.util.List;
import java.util.function.Function;

import com.dylibso.chicory.runtime.ExportFunction;

import io.github.stefanrichterhuber.quickjswasmjava.QuickJSRuntime.ScriptDurationGuard;

/**
 * Represents a quickjs native function
 */
public final class QuickJSFunction implements Function<List<Object>, Object> {
    /**
     * The context this function belongs to.
     */
    private final QuickJSContext context;
    /**
     * The name of the function.
     */
    private final String name;
    /**
     * The pointer to the function in the wasm library.
     */
    private long functionPtr;
    /**
     * The native call function.
     */
    private final ExportFunction call;

    private final ExportFunction close;

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
        this.close = context.getRuntime().getInstance().export("close_function_wasm");
        context.addDependendResource(this::close);
    }

    /**
     * Calls the function with the given arguments
     * 
     * @param args the arguments to pass to the function
     * @return the result of the function call
     */
    public Object call(Object... args) {
        // We create a message pack object from the arguments, with the root of the pack
        // being an array
        final List<Object> params = args == null || args.length == 0 ? List.of() : List.of(args);

        // Don't close the memory location here, because it will be used by the wasm
        // function
        try (final ScriptDurationGuard guard = new ScriptDurationGuard(this.context.getRuntime());
                final MemoryLocation memoryLocation = context.writeToMemory(params)) {
            final long[] result = call.apply(getContextPointer(), getFunctionPointer(), memoryLocation.pointer(),
                    memoryLocation.length());

            return this.context.handleNativeResult(result);
        }
    }

    /**
     * Closes the function
     * 
     * @throws Exception
     */
    private void close() throws Exception {
        if (this.functionPtr != 0) {
            this.close.apply(getContextPointer(), functionPtr);
            this.functionPtr = 0;
        }
    }

    /**
     * Returns the name of the function
     * 
     * @return name of the function
     */
    public String getName() {
        return name;
    }

    /**
     * Returns the pointer to the function in the wasm library
     * 
     * @return native pointer of the function
     */
    long getFunctionPointer() {
        return functionPtr;
    }

    /**
     * Returns the native pointer to the quick js context
     * 
     * @return native pointer of the context
     */
    long getContextPointer() {
        return context.getContextPointer();
    }

    @Override
    public Object apply(List<Object> arg0) {
        return call(arg0);
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
