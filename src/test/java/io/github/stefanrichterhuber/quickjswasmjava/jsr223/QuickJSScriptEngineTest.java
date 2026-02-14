package io.github.stefanrichterhuber.quickjswasmjava.jsr223;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import javax.script.Bindings;
import javax.script.Invocable;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;

import org.junit.jupiter.api.Test;

public class QuickJSScriptEngineTest {

    @Test
    public void testEngineDiscovery() {
        ScriptEngineManager manager = new ScriptEngineManager();
        ScriptEngine engine = manager.getEngineByName("QuickJS");
        assertNotNull(engine);
        assertTrue(engine instanceof QuickJSScriptEngine);
    }

    @Test
    public void testSimpleEval() throws Exception {
        ScriptEngineManager manager = new ScriptEngineManager();
        ScriptEngine engine = manager.getEngineByName("QuickJS");
        Object result = engine.eval("1 + 1");
        assertEquals(2, result);
    }

    @Test
    public void testBindings() throws Exception {
        ScriptEngineManager manager = new ScriptEngineManager();
        ScriptEngine engine = manager.getEngineByName("QuickJS");
        Bindings bindings = engine.createBindings();
        bindings.put("a", 10);
        bindings.put("b", 20);
        Object result = engine.eval("a + b", bindings);
        assertEquals(30, result);

        bindings.put("c", 0);
        engine.eval("c = a + b", bindings);
        assertEquals(30, bindings.get("c"));
    }

    @Test
    public void testGlobalBindings() throws Exception {
        ScriptEngineManager manager = new ScriptEngineManager();
        ScriptEngine engine = manager.getEngineByName("QuickJS");

        Bindings globalBindings = manager.getBindings();
        globalBindings.put("globalVar", "hello");

        // QuickJSScriptEngine should ideally pick up global bindings from ScriptContext
        Object result = engine.eval("globalVar");
        assertEquals("hello", result);
    }

    @Test
    public void testInvocable() throws Exception {
        ScriptEngineManager manager = new ScriptEngineManager();
        ScriptEngine engine = manager.getEngineByName("QuickJS");
        engine.eval("function add(a, b) { return a + b; }");

        Invocable invocable = (Invocable) engine;
        Object result = invocable.invokeFunction("add", 10, 20);
        assertEquals(30, result);
    }

    @Test
    public void testInvokeMethod() throws Exception {
        ScriptEngineManager manager = new ScriptEngineManager();
        ScriptEngine engine = manager.getEngineByName("QuickJS");
        Object obj = engine.eval("({ add: function(a, b) { return a + b; } })");

        Invocable invocable = (Invocable) engine;
        Object result = invocable.invokeMethod(obj, "add", 10, 20);
        assertEquals(30, result);
    }
}
