package io.github.stefanrichterhuber.quickjswasmjava;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.dylibso.chicory.runtime.ExportFunction;

/**
 * A native wrapper around a QuickJS promise objects. It extends
 * CompletableFuture to provide a Java
 * interface to the promise.
 */
class QuickJSPromise extends CompletableFuture<Object> {
    private final long promisePtr;
    private final QuickJSContext context;

    QuickJSPromise(QuickJSContext context) {
        this.context = context;
        final ExportFunction create = context.getRuntime().getInstance().export("promise_create_wasm");
        final long[] result = create.apply(context.getContextPointer());
        try (MemoryLocation resultLocation = MemoryLocation.unpack(result[0], context.getRuntime())) {
            final Map<String, Object> r = (Map<String, Object>) context.unpackObjectFromMemory(resultLocation);

            final QuickJSFunction resolve = (QuickJSFunction) r.get("resolve");
            final QuickJSFunction reject = (QuickJSFunction) r.get("reject");
            final Integer promisePtr = (Integer) r.get("promise_ptr");

            // FIXME: When this future is completeed by a native promise it should not call
            // resolve or reject
            // this.thenApply(v -> {
            // Object res = resolve.call(this, v);
            // return v;
            // });
            // this.exceptionally(e -> {
            // reject.call(this, e);
            // return null;
            // });
            this.promisePtr = promisePtr;
        }
    }

    long getPromisePtr() {
        return promisePtr;
    }
}
