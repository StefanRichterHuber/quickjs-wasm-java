package io.github.stefanrichterhuber.quickjswasmjava;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

public class QuickJSContextTest {

    @SuppressWarnings("unchecked")
    @Test
    public void testReturnValuesFromEval() throws Exception {
        try (QuickJSRuntime runtime = new QuickJSRuntime();
                QuickJSContext context = runtime.createContext()) {
            // Test return of null
            {
                Object result = context.eval("let ra = null; ra");
                assertEquals(null, result);
            }
            // Test return of undefined
            {
                Object result = context.eval("let rb = undefined; rb");
                assertEquals(null, result);
            }
            // Test return of integer
            {
                Object result = context.eval("3 + 1");
                assertInstanceOf(Integer.class, result);
                assertEquals(4, result);
            }
            // Test return of string
            {
                Object result = context.eval(" 'Hello ' + 'World'");
                assertInstanceOf(String.class, result);
                assertEquals("Hello World", result);
            }
            // Test return of boolean
            {
                Object result = context.eval(" true");
                assertInstanceOf(Boolean.class, result);
                assertEquals(true, result);
            }
            // Test return of double
            {
                Object result = context.eval(" 3.14");
                assertInstanceOf(Double.class, result);
                assertEquals(3.14, result);
            }
            // Test return of (heterogeneous and nested) array
            {
                Object result = context.eval("[ 3.14, 42, 'hello', true, [1,2,3]]");
                assertInstanceOf(List.class, result);
                List<Object> list = (List<Object>) result;
                assertEquals(5, list.size());
                assertEquals(3.14, list.get(0));
                assertEquals(42, list.get(1));
                assertEquals("hello", list.get(2));
                assertEquals(true, list.get(3));
                assertInstanceOf(List.class, list.get(4));
                List<Object> nestedList = (List<Object>) list.get(4);
                assertEquals(3, nestedList.size());
                assertEquals(1, nestedList.get(0));
                assertEquals(2, nestedList.get(1));
                assertEquals(3, nestedList.get(2));
            }
            // Test return of (heterogeneous and nested) object
            {
                Object result = context
                        .eval("let r = { a: 3.14, b: 42, c: 'hello', d: true, e: [1,2,3], f: {g: 42}}; r");
                assertInstanceOf(Map.class, result);
                Map<String, Object> map = (Map<String, Object>) result;
                assertEquals(6, map.size());
                assertEquals(3.14, map.get("a"));
                assertEquals(42, map.get("b"));
                assertEquals("hello", map.get("c"));
                assertEquals(true, map.get("d"));
                assertInstanceOf(List.class, map.get("e"));
                List<Object> nestedList = (List<Object>) map.get("e");
                assertEquals(3, nestedList.size());
                assertEquals(1, nestedList.get(0));
                assertEquals(2, nestedList.get(1));
                assertEquals(3, nestedList.get(2));
                assertInstanceOf(Map.class, map.get("f"));
                Map<String, Object> nestedMap = (Map<String, Object>) map.get("f");
                assertEquals(1, nestedMap.size());
                assertEquals(42, nestedMap.get("g"));
            }
        }
    }

    @Test
    public void testReturnFunctionFromEval() throws Exception {
        try (QuickJSRuntime runtime = new QuickJSRuntime();
                QuickJSContext context = runtime.createContext()) {
            // Local function
            {
                Object result = context.eval("let r = function() { return 42; }; r");
                assertInstanceOf(QuickJSFunction.class, result);
                QuickJSFunction function = (QuickJSFunction) result;
                assertEquals(42, function.call());
                assertEquals(42, function.call());
                assertEquals(42, function.call());
                assertEquals(42, function.call());
            }

            // Global function
            {
                Object result = context.eval("function a() { return 1; };a");
                assertInstanceOf(QuickJSFunction.class, result);
                QuickJSFunction function = (QuickJSFunction) result;
                assertEquals(1, function.call());
                assertEquals(1, function.call());
                assertEquals(1, function.call());
                assertEquals(1, function.call());

            }
            // Function with arguments
            {
                Object result = context.eval("function a(b) { return b + 1; };a");
                assertInstanceOf(QuickJSFunction.class, result);
                QuickJSFunction function = (QuickJSFunction) result;
                assertEquals(42, function.call(41));
                assertEquals(1, function.call(0));
                assertEquals(2, function.call(1));
            }

        }
    }

    @Test
    public void testSetGlobal() throws Exception {
        try (QuickJSRuntime runtime = new QuickJSRuntime();
                QuickJSContext context = runtime.createContext()) {
            {
                context.setGlobal("a", 42);
                Object result = context.eval("a");
                assertEquals(42, result);
            }
            {
                context.setGlobal("a", "hello");
                Object result = context.eval("a");
                assertEquals("hello", result);
            }
            {
                context.setGlobal("a", true);
                Object result = context.eval("a");
                assertEquals(true, result);
            }
            {
                context.setGlobal("a", 3.14);
                Object result = context.eval("a");
                assertEquals(3.14, result);
            }
            {
                context.setGlobal("a", List.of(1, 2, 3));
                Object result = context.eval("a[0]");
                assertEquals(1, result);
            }
            {
                context.setGlobal("a", Map.of("b", 42));
                Object result = context.eval("a.b");
                assertEquals(42, result);
            }
        }
    }

    @Test
    public void testGetGlobal() throws Exception {
        try (QuickJSRuntime runtime = new QuickJSRuntime();
                QuickJSContext context = runtime.createContext()) {
            {
                context.setGlobal("a", 42);
                Object result = context.getGlobal("a");
                assertEquals(42, result);
            }
            {
                context.setGlobal("a", "hello");
                Object result = context.getGlobal("a");
                assertEquals("hello", result);
            }
            {
                context.setGlobal("a", true);
                Object result = context.getGlobal("a");
                assertEquals(true, result);
            }
            {
                context.setGlobal("a", 3.14);
                Object result = context.getGlobal("a");
                assertEquals(3.14, result);
            }
            {
                context.setGlobal("a", List.of(1, 2, 3));
                Object result = context.getGlobal("a");
                assertEquals(List.of(1, 2, 3), result);
            }
            {
                context.setGlobal("a", Map.of("b", 42));
                Object result = context.getGlobal("a");
                assertEquals(Map.of("b", 42), result);
            }
        }
    }

    @Test
    public void exportJavaFunctionsToJS() throws Exception {
        try (QuickJSRuntime runtime = new QuickJSRuntime();
                QuickJSContext context = runtime.createContext()) {

            BiFunction<Integer, Integer, Integer> add = (a, b) -> {
                return a + b;
            };

            context.setGlobal("add", add);
            Object result = context.eval("add(1, 2)");
            assertEquals(3, result);
        }
    }

    @Test
    public void testScriptRuntimeLimit() throws Exception {
        try (QuickJSRuntime runtime = new QuickJSRuntime().withScriptRuntimeLimit(1, TimeUnit.SECONDS);
                QuickJSContext context = runtime.createContext()) {

            try {
                context.eval("while(true){}");
                fail("Script runtime limit should have been reached");
            } catch (Exception e) {
                System.out.println(e.getMessage());
            }
        }
    }

    @Test
    public void testExceptionHandling() throws Exception {
        try (QuickJSRuntime runtime = new QuickJSRuntime();
                QuickJSContext context = runtime.createContext()) {
            try {
                context.eval("""
                        let a = 1;
                        let b = 0;
                        let c = a / b;
                        throw new Error('test');
                        """);
                fail("Exception should have been thrown");
            } catch (Exception e) {
                assertInstanceOf(QuickJSException.class, e);
                QuickJSException quickJSException = (QuickJSException) e;
                assertEquals("test", quickJSException.getRawMessage());
                assertEquals("    at <eval> (eval_script:4:11)\n",
                        quickJSException.getStack());
                System.out.println(e.getMessage());
            }
        }
    }
}
