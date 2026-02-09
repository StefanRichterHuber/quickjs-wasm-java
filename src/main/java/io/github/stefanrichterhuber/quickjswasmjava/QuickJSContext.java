package io.github.stefanrichterhuber.quickjswasmjava;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.msgpack.core.MessageUnpacker;

import com.dylibso.chicory.runtime.ExportFunction;

public class QuickJSContext implements AutoCloseable {

    private final long contextPtr;
    private final QuickJSRuntime runtime;
    private final ExportFunction eval;
    private final ExportFunction createContext;
    private final ExportFunction closeContext;

    QuickJSContext(QuickJSRuntime runtime) {
        this.runtime = runtime;
        this.createContext = runtime.getInstance().export("create_context_wasm");
        this.closeContext = runtime.getInstance().export("close_context_wasm");
        this.eval = runtime.getInstance().export("eval_script_wasm");
        this.contextPtr = createContext.apply(runtime.getRuntimePointer())[0];
    }

    public Object eval(String script) throws IOException {
        byte[] scriptBytes = script.getBytes();
        int scriptBytesLen = scriptBytes.length;

        long ptr = runtime.alloc(scriptBytesLen);
        runtime.getInstance().memory().writeString((int) ptr, script, StandardCharsets.UTF_8);
        System.out.println("Allocated memory for script: " + ptr + " with length " + scriptBytesLen);
        try {
            long[] result = eval.apply(contextPtr, ptr, scriptBytesLen);
            // Read the result with messagepack java, it is a pointer and a length to a
            // message pack object

            MessageUnpacker unpacker = runtime.unpackBytesFromMemory(result);
            JSJavaProxy proxy = JSJavaProxy.from(unpacker);

            return proxy.value;
        } finally {
            runtime.dealloc(ptr, scriptBytesLen);
        }
    }

    @Override
    public void close() throws Exception {
        // TODO implement cleanup
    }
}
