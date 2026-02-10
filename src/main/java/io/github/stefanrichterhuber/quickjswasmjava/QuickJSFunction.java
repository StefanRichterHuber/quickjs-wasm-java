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

    public QuickJSFunction(QuickJSContext context, String name, long functionPtr) {
        this.context = context;
        this.name = name;
        this.functionPtr = functionPtr;
        this.call = context.getRuntime().getInstance().export("call_function_wasm");
    }

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

    public String getName() {
        return name;
    }

    @Override
    public Object apply(Object[] arg0) {
        try {
            return call(arg0);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
