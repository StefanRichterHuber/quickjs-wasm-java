package io.github.stefanrichterhuber.quickjswasmjava;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.msgpack.core.MessagePack;
import org.msgpack.core.MessageUnpacker;

import com.dylibso.chicory.runtime.ExportFunction;
import com.dylibso.chicory.runtime.Instance;
import com.dylibso.chicory.runtime.Store;
import com.dylibso.chicory.wasi.WasiOptions;
import com.dylibso.chicory.wasi.WasiPreview1;
import com.dylibso.chicory.wasm.Parser;

public class QuickJSRuntime implements AutoCloseable {
    private final long ptr;
    private final Store store;
    private final Instance instance;
    private final ExportFunction alloc;
    private final ExportFunction dealloc;

    public QuickJSRuntime() throws IOException {
        try (InputStream is = this.getClass()
                .getResourceAsStream("libs/wasm_lib.wasm")) {

            var options = WasiOptions.builder().withStdout(System.out).build();
            var wasi = WasiPreview1.builder().withOptions(options).build();
            this.store = new Store().addFunction(wasi.toHostFunctions());
            // instantiate and execute the main entry point
            this.instance = this.store.instantiate("quickjslib", Parser.parse(is));

            this.alloc = instance.export("alloc");
            this.dealloc = instance.export("dealloc");

            long[] result = this.instance.export("create_runtime_wasm").apply();
            this.ptr = result[0];
        }
    }

    public QuickJSContext createContext() {
        return new QuickJSContext(this);
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
