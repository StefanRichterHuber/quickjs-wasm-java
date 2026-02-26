package io.github.stefanrichterhuber.quickjswasmjava;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.Test;

public class QuickJSContextTest {
    private static final Logger LOGGER = LogManager.getLogger();

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
                @SuppressWarnings("unchecked")
                List<Object> list = (List<Object>) result;
                assertEquals(5, list.size());
                assertEquals(3.14, list.get(0));
                assertEquals(42, list.get(1));
                assertEquals("hello", list.get(2));
                assertEquals(true, list.get(3));
                assertInstanceOf(List.class, list.get(4));
                @SuppressWarnings("unchecked")
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
                @SuppressWarnings("unchecked")
                Map<String, Object> map = (Map<String, Object>) result;
                assertEquals(6, map.size());
                assertEquals(3.14, map.get("a"));
                assertEquals(42, map.get("b"));
                assertEquals("hello", map.get("c"));
                assertEquals(true, map.get("d"));
                assertInstanceOf(List.class, map.get("e"));
                @SuppressWarnings("unchecked")
                List<Object> nestedList = (List<Object>) map.get("e");
                assertEquals(3, nestedList.size());
                assertEquals(1, nestedList.get(0));
                assertEquals(2, nestedList.get(1));
                assertEquals(3, nestedList.get(2));
                assertInstanceOf(Map.class, map.get("f"));
                @SuppressWarnings("unchecked")
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
                // QuickJSFunction implements java.util.function.Function<List<Object>, Object>
                assertEquals(42, function.apply(List.of()));

                // JS Function can be added back to the js context with a different name
                context.setGlobal("f1", function);
                Object r2 = context.eval("f1()");
                assertEquals(42, r2);
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
                assertEquals("a", function.getName());
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

            @SuppressWarnings("unchecked")
            Map<String, Object> obj = (Map<String, Object>) result;
            assertFalse(obj.isEmpty());
            assertTrue(obj.containsValue(1));
            assertTrue(obj.containsValue("Hello"));

            assertTrue(obj.containsKey("a"));
            assertTrue(obj.containsKey("b"));
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

    /**
     * One can directly create native JS object on the java side using QuickJSObject
     * 
     * @throws Exception
     */
    @Test
    public void testNativeObjectFromJavaSide() throws Exception {
        try (QuickJSRuntime runtime = new QuickJSRuntime();
                QuickJSContext context = runtime.createContext()) {

            Map<String, Object> map = new QuickJSObject<>(context);
            map.put("a", "Hello");
            map.put("b", "World");
            context.setGlobal("a", map);
            assertEquals(2, map.keySet().size());

            Object result = context.eval("a.a");
            assertEquals("Hello", result);

            result = context.eval("a.b");
            assertEquals("World", result);

            result = context.eval("a.c");
            assertEquals(null, result);

            // One can add a value on JS side, and its visible on Java
            context.eval("a.c = 'test_value'");
            assertEquals("test_value", map.get("c"));

            // One can modify a value on JS side, and its visible on Java
            context.eval("a.a = 'test_value'");
            assertEquals("test_value", map.get("a"));

            // One can remove a value on JS side, and its visible on Java
            context.eval("delete a.b");
            assertEquals(null, map.get("b"));

        }
    }

    /**
     * JS Arrays will be just wrapped as QuickJSArrays. All modifications on the
     * array will be visible on both the Java and the JS side.
     */
    @Test
    public void testNativeArrays() throws Exception {
        try (QuickJSRuntime runtime = new QuickJSRuntime();
                QuickJSContext context = runtime.createContext()) {

            context.eval("var a = [1, 2, 3];");
            Object result = context.getGlobal("a");
            assertInstanceOf(QuickJSArray.class, result);
            @SuppressWarnings("unchecked")
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

            // Even add inbetween
            array.add(1, 9);
            assertEquals(5, array.size());
            assertEquals(1, array.get(0));
            assertEquals(9, array.get(1));
            assertEquals(2, array.get(2));

            // One can modify a value on java side and its visible on JS
            array.set(0, 10);
            assertEquals(10, array.get(0));
            Object r2 = context.eval("a[0]");
            assertEquals(10, r2);

            // One can remove a value on java side and its visible on JS
            array.remove(0);
            assertEquals(4, array.size());
            Object r3 = context.eval("a[0]");
            assertEquals(9, r3);

        }
    }

    /**
     * One can create native JS arrays directly on the java side using QuickJSArrays
     * 
     * @throws Exception
     */
    @Test
    public void testNativeArraysFromJavaSide() throws Exception {
        try (QuickJSRuntime runtime = new QuickJSRuntime();
                QuickJSContext context = runtime.createContext()) {

            List<Object> list = new QuickJSArray<>(context);
            list.add("a");
            list.add("b");
            assertEquals(2, list.size());

            context.setGlobal("a", list);
            assertEquals("a", context.eval("a[0]"));
            assertEquals("b", context.eval("a[1]"));

            // Modification on the js side are visible in the java object

            // Add element
            context.eval("a[2]='c';");
            assertEquals(3, list.size());
            assertEquals("c", list.get(2));

            // Reassign element
            context.eval("a[0]='d'");
            assertEquals(3, list.size());
            assertEquals("d", list.get(0));

            // Delete elements
            context.eval(" a.splice(2, 1);  a.splice(1, 1);");
            assertEquals(1, list.size());
            assertEquals("d", list.get(0));

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

            Function<Integer, Integer> square = (a) -> {
                return a * a;
            };

            Supplier<Integer> random = () -> {
                return 42;
            };

            AtomicInteger counter = new AtomicInteger();
            Consumer<Integer> count = (a) -> {
                counter.set(a);
            };

            AtomicInteger adder = new AtomicInteger();
            BiConsumer<Integer, Integer> combine = (a, b) -> {
                adder.set(a + b);
            };

            context.setGlobal("add", add);
            context.setGlobal("square", square);
            context.setGlobal("random", random);
            context.setGlobal("count", count);
            context.setGlobal("combine", combine);

            Object result = context.eval("add(1, 2)");
            assertEquals(3, result);

            result = context.eval("square(2)");
            assertEquals(4, result);

            result = context.eval("random()");
            assertEquals(42, result);

            result = context.eval("count(1)");
            assertEquals(1, counter.get());

            result = context.eval("combine(3 , 4)");
            assertEquals(7, adder.get());

            // If one retrieves any javafunction from js it is returned as QuickJSFUnction
            Object addBack = context.getGlobal("add");
            assertInstanceOf(QuickJSFunction.class, addBack);
        }
    }

    /**
     * The runtime of the script can be limited in the Runtime object
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
     * The memory consumption of scripts can be limited by the runtime object to
     * prevent faulty scripts to overload the host
     */
    @Test
    public void testScriptMemoryLimit() throws Exception {
        try (@SuppressWarnings("resource")
        QuickJSRuntime runtime = new QuickJSRuntime().withScriptMemoryLimit(10000);
                QuickJSContext context = runtime.createContext()) {

            try {
                context.eval(
                        "const memoryHog = [];\nconst chunk = \"M_E_M_O_R_Y_\".repeat(100000);\nwhile (true) {memoryHog.push(chunk); }");
            } catch (QuickJSException e) {
                assertEquals("out of memory", e.getMessage());
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

    /**
     * Invoking a JS function directly from java is possible. Even nested calls to
     * existing objects are supported
     * 
     * @throws Exception
     */
    @Test
    public void testInvokeJSFunction() throws Exception {
        try (QuickJSRuntime runtime = new QuickJSRuntime();
                QuickJSContext context = runtime.createContext()) {

            {
                context.eval("function a(x, y) { return x + y; };");
                Object result = context.invoke("a", 1, 2);
                assertEquals(3, result);
            }
            {
                context.eval("var g = {f: function(x, y) { return x + y; }};");
                Object result = context.invoke("g.f", 1, 2);
                assertEquals(3, result);
            }
            {
                context.eval("var c = {b: {f: function(x, y) { return x + y; }}};");
                Object result = context.invoke("c.b.f", 1, 2);
                assertEquals(3, result);
            }
        }
    }

    public interface TestInterface {
        int add(int a, int b);

        int substract(int a, int b);
    }

    /**
     * The global js context can be used as if it implements a java interface
     * 
     * @throws Exception
     */
    @Test
    public void mapJSContextToJavaInterface() throws Exception {
        try (QuickJSRuntime runtime = new QuickJSRuntime();
                QuickJSContext context = runtime.createContext()) {

            context.eval("function add(a, b) { return a + b; }; function substract(a, b) { return a - b; }; ");
            TestInterface testInterface = context.getInterface(TestInterface.class);
            assertEquals(3, testInterface.add(1, 2));
            assertEquals(1, testInterface.substract(2, 1));
        }
    }

    /**
     * JS objects can be retrieved from the QuickJS context and mapped to java
     * interfaces. This way the JS object can be used as if it implements a java
     * interface.
     * 
     * @throws Exception
     */
    @Test
    public void mapJSObjectToJavaInterface() throws Exception {
        try (QuickJSRuntime runtime = new QuickJSRuntime();
                QuickJSContext context = runtime.createContext()) {

            QuickJSObject<String, Object> obj = (QuickJSObject<String, Object>) context.eval(
                    "let obj = {add: function(a, b) { return a + b; }, substract: function(a, b) { return a - b; }}; obj");
            assertNotNull(obj);
            TestInterface testInterface = context.getInterface(obj, TestInterface.class);
            assertEquals(3, testInterface.add(1, 2));
            assertEquals(1, testInterface.substract(2, 1));
        }
    }

    @Test
    public void simplePromiseSupport() throws Exception {
        try (QuickJSRuntime runtime = new QuickJSRuntime();
                QuickJSContext context = runtime.createContext()) {

            CompletableFuture<Object> r1 = context.evalAsync("await \"Classic resolve\" ");

            assertNotNull(r1);
            assertFalse(r1.isDone());
            while (context.poll()) {
                Thread.sleep(10);
            }
            assertTrue(r1.isDone());
            assertEquals("Classic resolve", r1.join());
        }
    }

    @Test
    public void simplePromiseErrSupport() throws Exception {
        try (QuickJSRuntime runtime = new QuickJSRuntime();
                QuickJSContext context = runtime.createContext()) {

            CompletableFuture<Object> r1 = context.evalAsync("throw Err('hello') ");

            assertNotNull(r1);
            assertFalse(r1.isDone());
            while (context.poll()) {
                Thread.sleep(10);
            }
            assertTrue(r1.isCompletedExceptionally());
        }
    }

    @Test
    public void promiseSupport() throws Exception {
        try (QuickJSRuntime runtime = new QuickJSRuntime();
                QuickJSContext context = runtime.createContext()) {

            CompletableFuture<Object> r1 = context.evalAsync("let trigger;\n" + //
                    "const manualPromise = new Promise((resolve, reject) => {\n" + //
                    "  // Assign the internal resolve function to our outside variable\n" + //
                    "  trigger = resolve; \n" + //
                    "});\n" + //
                    "manualPromise\n");

            assertFalse(r1.isDone());
            CompletableFuture<Object> r2 = context.evalAsync("await trigger(\"Classic resolve\");");
            while (context.poll()) {
                Thread.sleep(10);
            }
            assertTrue(r1.isDone());
        }
    }

    @Test
    public void completableFutureSupport() throws Exception {
        try (QuickJSRuntime runtime = new QuickJSRuntime();
                QuickJSContext context = runtime.createContext()) {
            {
                QuickJSPromise promise = new QuickJSPromise(context);
                context.setGlobal("p0", promise);

                CompletableFuture<Object> r1 = context.evalAsync("await p0");
                LOGGER.debug("Entering first poll before completing the future");
                while (context.poll()) {
                    Thread.sleep(10);
                }
                promise.complete(53);
                LOGGER.debug("Entering second poll after completing the future");
                while (context.poll()) {
                    Thread.sleep(10);
                }
                assertEquals(53, promise.join());
            }
            {
                QuickJSPromise promise = new QuickJSPromise(context);
                //
                context.setGlobal("p", promise);
                CompletableFuture result = context.evalAsync("await p.then((v) => { return v * 3; });");
                assertInstanceOf(CompletableFuture.class, result);
                System.out.println(result);

                while (context.poll()) {
                    Thread.sleep(10);
                }
                assertFalse(((CompletableFuture) result).isDone());
                promise.complete(54);
                while (context.poll()) {
                    Thread.sleep(10);
                }
                assertTrue(((CompletableFuture) result).isDone());
                assertEquals(162, ((CompletableFuture) result).join());
            }
        }

    }

}
