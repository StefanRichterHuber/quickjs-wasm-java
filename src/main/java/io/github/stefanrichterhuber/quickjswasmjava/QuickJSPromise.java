package io.github.stefanrichterhuber.quickjswasmjava;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.dylibso.chicory.runtime.ExportFunction;

/**
 * A native wrapper around a QuickJS promise objects. It extends
 * CompletableFuture to provide a Java
 * interface to the promise.
 */
class QuickJSPromise extends CompletableFuture<Object> {
    private static final Logger LOGGER = LogManager.getLogger();

    private long promisePtr;
    private final int completableFuturePtr;
    private final QuickJSContext context;
    private final ExportFunction resolve;
    private final ExportFunction reject;
    private final ExportFunction close;

    private final AtomicBoolean completedByJS = new AtomicBoolean(false);

    /**
     * Creates a wrapper for an existing native promise.
     * 
     * @param context    The QuickJS context this promise belongs to.
     * @param promisePtr The pointer to the existing native promise.
     */
    QuickJSPromise(QuickJSContext context, long promisePtr) {
        this.context = context;
        this.resolve = context.getRuntime().getInstance().export("promise_resolve_wasm");
        this.reject = context.getRuntime().getInstance().export("promise_reject_wasm");
        this.close = context.getRuntime().getInstance().export("promise_close_wasm");
        this.context.completableFutures.add(this);
        this.completableFuturePtr = this.context.completableFutures.size() - 1;
        this.promisePtr = promisePtr;
        LOGGER.debug("Created JS promise with existing pointer: {} and a completable future pointer: {}",
                this.promisePtr,
                completableFuturePtr);

        registerCallbacks();
        this.context.addDependentResource(this::close);
    }

    /**
     * Creates a new native promise and the corresponding wrapper.
     * 
     * @param context The QuickJS context this promise belongs to.
     */
    QuickJSPromise(QuickJSContext context) {
        this.context = context;
        this.resolve = context.getRuntime().getInstance().export("promise_resolve_wasm");
        this.reject = context.getRuntime().getInstance().export("promise_reject_wasm");
        this.close = context.getRuntime().getInstance().export("promise_close_wasm");
        this.context.completableFutures.add(this);
        this.completableFuturePtr = this.context.completableFutures.size() - 1;
        this.promisePtr = createNativePromise(context, completableFuturePtr);
        LOGGER.debug("Created JS promise with new pointer: {} and a completable future pointer: {}",
                this.promisePtr,
                completableFuturePtr);
        registerCallbacks();
        this.context.addDependentResource(this::close);
    }

    /**
     * Closes the native promise and removes this wrapper from the context.
     * 
     * @throws Exception
     */
    private void close() throws Exception {
        LOGGER.debug("Closing QuickJSPromise with native pointer {} and corresponding CompletableFuture {}",
                this.promisePtr, this.completableFuturePtr);

        if (promisePtr == 0) {
            LOGGER.debug("QuickJSPromise already closed!");
            return;
        }

        try {
            this.close.apply(this.getContextPointer(), this.getPromisePointer());
        } catch (Exception e) {
            // May fail
            LOGGER.debug("Failed to close native promise", e);
        }
        promisePtr = 0;
    }

    /**
     * Wraps the given CompletionStage into a QuickJSPromise so that if the
     * CompletableFuture completes, the promise completes
     */
    static QuickJSPromise wrap(CompletionStage<?> src, QuickJSContext context) {
        if (src instanceof QuickJSPromise p) {
            return p;
        }
        final QuickJSPromise p = new QuickJSPromise(context);
        src.thenAccept(v -> {
            LOGGER.debug("External CompletableFuture completes this QuickJSPromise with value: {}", v);
            p.complete(v);
        });
        src.exceptionally(e -> {
            LOGGER.debug("External CompletableFuture rejects this QuickJSPromise with exception: {}", e);
            p.completeExceptionally(e);
            return null;
        });
        return p;
    }

    /**
     * Registers callbacks to synchronize the state of this
     * {@link CompletableFuture}
     * with the native QuickJS promise.
     */
    private void registerCallbacks() {
        this.thenAccept(value -> {
            if (completedByJS.get() == false) {
                LOGGER.debug("Completing JS promise with value: {}", value);
                // If this completable future was not completed by JS, complete the
                // corresponding js promise
                try (final MemoryLocation valueLocation = this.context.writeToMemory(value)) {
                    long[] r = this.resolve.apply(this.getContextPointer(), this.getPromisePointer(),
                            valueLocation.pointer(),
                            valueLocation.length());
                    LOGGER.debug("Result of resolving promise on js: {}", r[0]);

                }
            } else {
                LOGGER.debug("JS promise already completed");
            }
        });

        this.exceptionally(throwable -> {
            if (completedByJS.get() == false) {
                LOGGER.debug("Rejecting JS promise with exception: {}", throwable);
                // If this completable future was not completed by JS, complete the
                // corresponding js promise
                try (final MemoryLocation valueLocation = this.context.writeToMemory(throwable)) {
                    long[] r = this.reject.apply(this.getContextPointer(), this.getPromisePointer(),
                            valueLocation.pointer(),
                            valueLocation.length());
                    LOGGER.debug("Result of rejecting promise on js: {}", r[0]);
                }
            } else {
                LOGGER.debug("JS promise already rejected");
            }
            return null;
        });
    }

    /**
     * Creates a new native promise in the JS runtime.
     * 
     * @param context                QuickJS context.
     * @param completableFutureIndex Index of the completable future in the
     *                               context's
     *                               registry.
     * @return Pointer to the native JS promise.
     */
    private static long createNativePromise(QuickJSContext context, int completableFutureIndex) {
        final ExportFunction create = context.getRuntime().getInstance().export("promise_create_wasm");
        final long[] result = create.apply(context.getContextPointer(), completableFutureIndex);
        final long promisePtr = result[0];
        if (promisePtr == 0l) {
            throw new IllegalStateException("Failed to create native promise");
        }
        LOGGER.debug("Created JS promise with pointer: {} and a completable future pointer: {}", promisePtr,
                completableFutureIndex);
        return promisePtr;
    }

    /**
     * Completes this {@link CompletableFuture} from the JavaScript side.
     * 
     * @param value The value to complete the promise with.
     */
    void completeByJS(Object value) {
        this.completedByJS.set(true);
        this.complete(value);
    }

    /**
     * Completes this {@link CompletableFuture} exceptionally from the JavaScript
     * side.
     * 
     * @param value The exception to complete the promise with.
     */
    void completeExceptionallyByJS(Throwable value) {
        this.completedByJS.set(true);
        this.completeExceptionally(value);
    }

    /**
     * Returns the pointer to the native QuickJS context.
     * 
     * @return The context pointer.
     */
    long getContextPointer() {
        return this.context.getContextPointer();
    }

    /**
     * Returns the pointer to the native QuickJS promise.
     * 
     * @return The promise pointer.
     */
    long getPromisePointer() {
        return promisePtr;
    }

    /**
     * Returns the index of this {@link CompletableFuture} in the context's
     * registry.
     * 
     * @return The completable future pointer (index).
     */
    int getCompletableFuturePointer() {
        return this.completableFuturePtr;
    }
}
