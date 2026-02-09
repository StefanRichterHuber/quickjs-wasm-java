package io.github.stefanrichterhuber.quickjswasmjava;

import java.io.IOException;
import java.util.List;

import org.msgpack.core.MessageUnpacker;

import com.dylibso.chicory.runtime.ExportFunction;

public final class QuickJSFunction {
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

        try (MemoryLocation memoryLocation = context.writeToMemory(params)) {
            final long[] result = call.apply(context.getContextPointer(), functionPtr, memoryLocation.pointer(),
                    memoryLocation.length());
            // Read the result with messagepack java, it is a pointer and a length to a
            // message pack object
            MessageUnpacker unpacker = context.getRuntime().unpackBytesFromMemory(result);
            final Object r = context.from(unpacker);
            return r;
        }
    }

    public String getName() {
        return name;
    }
}
