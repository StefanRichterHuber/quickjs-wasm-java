package io.github.stefanrichterhuber.quickjswasmjava;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

import org.junit.jupiter.api.Test;

import com.dylibso.chicory.log.SystemLogger;
import com.dylibso.chicory.runtime.ExportFunction;
import com.dylibso.chicory.runtime.ImportFunction;
import com.dylibso.chicory.runtime.Instance;
import com.dylibso.chicory.runtime.Memory;
import com.dylibso.chicory.runtime.Store;
import com.dylibso.chicory.wasi.WasiOptions;
import com.dylibso.chicory.wasi.WasiPreview1;
import com.dylibso.chicory.wasm.Parser;

public class LoadTest {
    @Test
    public void testLoad() throws IOException {
        try (InputStream is = this.getClass()
                .getResourceAsStream("libs/wasm_lib.wasm")) {

            var logger = new SystemLogger();

            // We will create two output streams to capture stdout and stderr
            var fakeStdout = new ByteArrayOutputStream();

            // let's just use the default options for now
            var options = WasiOptions.builder().withStdout(System.out).build();
            // create our instance of wasip1
            var wasi = WasiPreview1.builder().withOptions(options).build();
            // create the module and connect the host functions
            var store = new Store().addFunction(wasi.toHostFunctions());
            // instantiate and execute the main entry point
            Instance instance = store.instantiate("quickjslib", Parser.parse(is));

            ExportFunction alloc = instance.export("alloc");
            ExportFunction dealloc = instance.export("dealloc");
            ExportFunction greet = instance.export("greet");

            String hello = "Hello World";
            int size = hello.getBytes().length;
            long[] ptr = alloc.apply(size);
            Memory mem = instance.memory();
            mem.writeString((int) ptr[0], hello);

            long[] result = greet.apply(ptr[0], size);

            long[] result2 = instance.export("greet2_wasm").apply(ptr[0], size);

            // TODO fix this
            // Rust code: ((ptr as u64) << 32) | (len as u64)
            int len2 = (int) (result2[0] & 0xffffffff);
            int ptr2 = (int) ((result2[0] >> 32) & 0xffffffff);

            String result2String = mem.readString(ptr2, len2);

            System.out.println(result2String);
        }
    }
}
