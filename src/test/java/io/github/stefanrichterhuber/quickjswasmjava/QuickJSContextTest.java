package io.github.stefanrichterhuber.quickjswasmjava;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;

import org.junit.jupiter.api.Test;

public class QuickJSContextTest {

    /**
     * All supported java types can be returned from the eval function
     * 
     * @throws Exception
     */
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

    /**
     * Even functions can be returned form the eval function and will be represented
     * as QuickJSFunction in java
     * 
     * @throws Exception
     */
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

    /**
     * All supported java types can be set as global values in the QuickJS context
     * 
     * @throws Exception
     */
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

    /**
     * All supported java types can be retrieved from the global quickjs context
     * 
     * @throws Exception
     */
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

    /**
     * JS Object will be just wrapped as QuickJSObject. All modifications on the
     * object will be visible on both the Java and the JS Side
     * 
     * @throws Exception
     */
    @Test
    public void testNativeObjects() throws Exception {
        try (QuickJSRuntime runtime = new QuickJSRuntime();
                QuickJSContext context = runtime.createContext()) {

            context.eval("var a = {a: 1, b: 'Hello'};");
            Object result = context.getGlobal("a");
            assertInstanceOf(QuickJSObject.class, result);

            Map<String, Object> obj = (Map<String, Object>) result;
            assertEquals(2, obj.size());
            assertEquals(1, obj.get("a"));
            assertEquals("Hello", obj.get("b"));

            Set<String> keys = obj.keySet();
            assertEquals(2, keys.size());
            assertTrue(keys.contains("a"));
            assertTrue(keys.contains("b"));

            assertNull(obj.get("c"));
            Object r0 = context.eval("a.c");
            assertNull(r0);

            // One can add a value on Java side, and its visible on JS
            obj.put("c", 42);
            assertEquals(3, obj.size());
            assertEquals(1, obj.get("a"));
            assertEquals("Hello", obj.get("b"));
            assertEquals(42, obj.get("c"));
            Object r1 = context.eval("a.c");
            assertEquals(42, r1);

            // One can modify a value on java side and its visible on JS
            obj.put("a", 10);
            assertEquals(10, obj.get("a"));
            Object r2 = context.eval("a.a");
            assertEquals(10, r2);

            // One can remove a value on java side and its visible on JS
            obj.remove("b");
            assertEquals(2, obj.size()); // a and c
            assertEquals(10, obj.get("a"));
            Object r3 = context.eval("a.b");
            assertEquals(null, r3);
        }
    }

    @Test
    public void testNativeObjectFromJavaSide() throws Exception {
        try (QuickJSRuntime runtime = new QuickJSRuntime();
                QuickJSContext context = runtime.createContext()) {

            Map<String, Object> map = new QuickJSObject<>(context);
            map.put("a", 1);
            map.put("b", "Hello");
            context.setGlobal("a", map);
            assertEquals(2, map.keySet().size());

            Object result = context.eval("a.a");
            assertEquals(1, result);

            result = context.eval("a.b");
            assertEquals("Hello", result);

            result = context.eval("a.c");
            assertEquals(null, result);

            // One can add a value on JS side, and its visible on Java
            context.eval("a.c = 'test_value'");
            assertEquals("test_value", map.get("c"));

            // One can modify a value on JS side, and its visible on Java
            context.eval("a.a = 10");
            assertEquals(10, map.get("a"));

            // One can remove a value on JS side, and its visible on Java
            context.eval("delete a.b");
            assertEquals(null, map.get("b"));

        }
    }

    /**
     * JS Arrays will be just wrapped as QuickJSArrays. All modifications on the
     * array
     * will be visible on both the Java and the JS side.
     */
    @Test
    public void testNativeArrays() throws Exception {
        try (QuickJSRuntime runtime = new QuickJSRuntime();
                QuickJSContext context = runtime.createContext()) {

            context.eval("var a = [1, 2, 3];");
            Object result = context.getGlobal("a");
            assertInstanceOf(QuickJSArray.class, result);
            List<Object> array = (List<Object>) result;
            assertEquals(3, array.size());
            assertEquals(1, array.get(0));
            assertEquals(2, array.get(1));
            assertEquals(3, array.get(2));

            // One can add a value on Java side, and its visible on JS
            array.add(4);
            assertEquals(4, array.size());
            assertEquals(1, array.get(0));
            assertEquals(2, array.get(1));
            assertEquals(3, array.get(2));
            assertEquals(4, array.get(3));
            Object r1 = context.eval("a[3]");
            assertEquals(4, r1);

            // One can modify a value on java side and its visible on JS
            array.set(0, 10);
            assertEquals(10, array.get(0));
            Object r2 = context.eval("a[0]");
            assertEquals(10, r2);

            // One can remove a value on java side and its visible on JS
            array.remove(0);
            assertEquals(3, array.size());
            Object r3 = context.eval("a[0]");
            assertEquals(2, r3);

        }
    }

    /**
     * Several java functions can be put into the quickjs context and called as if
     * they are native js functions
     * 
     * @throws Exception
     */
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

    /**
     * The runtime of the script can be limited
     * 
     * @throws Exception
     */
    @Test
    public void testScriptRuntimeLimit() throws Exception {
        try (@SuppressWarnings("resource")
        QuickJSRuntime runtime = new QuickJSRuntime().withScriptRuntimeLimit(1, TimeUnit.SECONDS);
                QuickJSContext context = runtime.createContext()) {

            try {
                context.eval("while(true){}");
                fail("Script runtime limit should have been reached");
            } catch (Exception e) {
                System.out.println(e.getMessage());
            }
        }
    }

    /**
     * JS exceptions thrown in the script are wrapped as QuickJSException
     * 
     * @throws Exception
     */

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

    /**
     * Java exceptions thrown in java callbacks, are wrapped as js exceptions and
     * then wrapped as QuickJSException. The message remains, the call stack is,
     * however, replaced with the JS Callstack.
     * 
     * @throws Exception
     */
    @Test
    public void testJavaExceptionHandling() throws Exception {
        try (QuickJSRuntime runtime = new QuickJSRuntime();
                QuickJSContext context = runtime.createContext()) {

            BiFunction<Integer, Integer, Integer> add = (a, b) -> {
                throw new RuntimeException("test");
            };

            context.setGlobal("add", add);
            // This calls the java function add which throws an exception
            context.eval("add(1, 2)");

            fail("Exception should have been thrown");
        } catch (Exception e) {
            assertInstanceOf(QuickJSException.class, e);
            QuickJSException quickJSException = (QuickJSException) e;
            assertEquals("test", quickJSException.getRawMessage());
            System.out.println(e.getMessage());
        }
    }
}
