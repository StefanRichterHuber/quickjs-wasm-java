package io.github.stefanrichterhuber.quickjswasmjava;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.msgpack.core.MessagePack;
import org.msgpack.core.MessageUnpacker;

import com.dylibso.chicory.runtime.ExportFunction;
import com.dylibso.chicory.runtime.HostFunction;
import com.dylibso.chicory.runtime.Instance;
import com.dylibso.chicory.runtime.Store;
import com.dylibso.chicory.wasi.WasiOptions;
import com.dylibso.chicory.wasi.WasiPreview1;
import com.dylibso.chicory.wasm.Parser;
import com.dylibso.chicory.wasm.types.FunctionType;
import com.dylibso.chicory.wasm.types.ValType;

public class QuickJSRuntime implements AutoCloseable {
    private static final Logger LOGGER = LogManager.getLogger();

    private final long ptr;
    private final Store store;
    private final Instance instance;
    private final ExportFunction alloc;
    private final ExportFunction dealloc;

    private final Map<Long, QuickJSContext> contexts = new HashMap<>();

    public QuickJSRuntime() throws IOException {
        try (InputStream is = this.getClass()
                .getResourceAsStream("libs/wasm_lib.wasm")) {

            var options = WasiOptions.builder().withStdout(System.out).build();
            var wasi = WasiPreview1.builder().withOptions(options).build();
            this.store = new Store().addFunction(wasi.toHostFunctions()).addFunction(createCallHostFunction());

            // instantiate and execute the main entry point
            this.instance = this.store.instantiate("quickjslib", Parser.parse(is));

            this.alloc = instance.export("alloc");
            this.dealloc = instance.export("dealloc");

            long[] result = this.instance.export("create_runtime_wasm").apply();
            this.ptr = result[0];
        }
    }

    private HostFunction createCallHostFunction() {
        HostFunction hostFunction = new HostFunction(
                "env",
                "call_java_function",
                FunctionType.of(
                        // First param is the context pointer, second is the function pointer, third is
                        // the pointer to the message pack object, fourth is the length of the message
                        // pack object
                        List.of(ValType.I32, ValType.I32, ValType.I32, ValType.I32),
                        // Return value is the pointer to the result message pack object and the length
                        // packed into a i64
                        List.of(ValType.I64)),
                this::callHostFunction);
        return hostFunction;
    }

    /**
     * Calls the host function. This is a host function that is called from the
     * QuickJS runtime. Delegate the call to the corresponding context.
     * 
     * @param instance
     * @param args
     * @return
     */
    private long[] callHostFunction(Instance instance, long... args) {
        final long contextPtr = args[0];
        final long functionPtr = args[1];
        final long argsPtr = args[2];
        final long argsLen = args[3];

        final QuickJSContext context = contexts.get(contextPtr);
        if (context == null) {
            throw new RuntimeException("Context not found: " + contextPtr);
        }
        return context.callHostFunction(instance, functionPtr, argsPtr, argsLen);
    }

    public QuickJSContext createContext() {
        QuickJSContext context = new QuickJSContext(this);
        contexts.put(context.getContextPointer(), context);
        LOGGER.info("Created context with pointer: {}", context.getContextPointer());
        return context;
    }

    long getRuntimePointer() {
        return this.ptr;
    }

    Instance getInstance() {
        return this.instance;
    }

    MemoryLocation writeToMemory(byte[] data) {
        long ptr = alloc(data.length);
        getInstance().memory().write((int) ptr, data);
        return new MemoryLocation(ptr, data.length, this);
    }

    MemoryLocation writeToMemory(String data) {
        return writeToMemory(data.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Reads the string from memory
     * 
     * @param result
     * @return
     */
    String readStringFromMemory(long... result) {
        int resultLen = (int) (result[0] & 0xffffffff);
        int resultPtr = (int) ((result[0] >> 32) & 0xffffffff);
        return getInstance().memory().readString(resultPtr, resultLen);
    }

    /**
     * Reads the bytes from memory
     * 
     * @param result
     * @return
     */
    byte[] readBytesFromMemory(long... result) {
        int resultLen = (int) (result[0] & 0xffffffff);
        int resultPtr = (int) ((result[0] >> 32) & 0xffffffff);
        return getInstance().memory().readBytes(resultPtr, resultLen);
    }

    /**
     * Unpacks the bytes from memory into a MessageUnpacker
     * 
     * @param result
     * @return
     */
    MessageUnpacker unpackBytesFromMemory(long... result) {
        byte[] resultBytes = readBytesFromMemory(result);
        MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(resultBytes);
        return unpacker;
    }

    /**
     * Allocates memory
     * 
     * @param size
     * @return
     */
    long alloc(int size) {
        long[] ptr = alloc.apply(size);
        return ptr[0];
    }

    /**
     * Deallocates memory
     * 
     * @param ptr
     * @param size
     */
    void dealloc(long ptr, int size) {
        dealloc.apply(ptr, size);
    }

    @Override
    public void close() throws Exception {
        // TODO implement cleanup
    }
}
