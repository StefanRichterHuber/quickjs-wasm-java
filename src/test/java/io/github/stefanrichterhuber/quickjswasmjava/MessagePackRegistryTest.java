package io.github.stefanrichterhuber.quickjswasmjava;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

public class MessagePackRegistryTest {

    private void testMapping(Object obj, MessagePackRegistry r) {
        byte[] packed = r.pack(obj);
        assertNotNull(packed);
        Object result = r.unpack(packed);

        if (obj instanceof Double) {
            assertEquals((Double) obj, (Double) result, 0.1d);
        } else if (obj instanceof Float) {
            assertEquals((Float) obj, (Double) result, 0.1f);
        } else {
            assertEquals(obj, result);
        }
    }

    @Test
    public void testMappings() throws Exception {
        try (QuickJSRuntime runtime = new QuickJSRuntime();
                QuickJSContext context = runtime.createContext()) {

            MessagePackRegistry r = new MessagePackRegistry(context);

            testMapping("String content", r);
            testMapping(49, r);
            testMapping(49.0f, r);
            testMapping(49.0d, r);
            testMapping(true, r);
            testMapping(false, r);
            testMapping(null, r);
            testMapping(List.of(1, 2, 3), r);
            testMapping(Map.of("a", 1, "b", 2), r);
        }
    }
}
