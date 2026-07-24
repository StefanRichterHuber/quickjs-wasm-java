package io.github.stefanrichterhuber.quickjswasmjava.jsr223;

import java.io.IOException;
import java.io.Reader;
import java.io.StringWriter;

import javax.script.AbstractScriptEngine;
import javax.script.Bindings;
import javax.script.Invocable;
import javax.script.ScriptContext;
import javax.script.ScriptEngineFactory;
import javax.script.ScriptException;
import javax.script.SimpleBindings;

import io.github.stefanrichterhuber.quickjswasmjava.QuickJSContext;
import io.github.stefanrichterhuber.quickjswasmjava.QuickJSFunction;
import io.github.stefanrichterhuber.quickjswasmjava.QuickJSObject;
import io.github.stefanrichterhuber.quickjswasmjava.QuickJSRuntime;

public class QuickJSScriptEngine extends AbstractScriptEngine implements Invocable, AutoCloseable {

    private final QuickJSScriptEngineFactory factory;
    private final QuickJSRuntime runtime;
    private final QuickJSContext context;

    public QuickJSScriptEngine(QuickJSScriptEngineFactory factory) {
        this.factory = factory;
        this.runtime = new QuickJSRuntime();
        this.context = this.runtime.createContext();
    }

    @Override
    public Object eval(String script, ScriptContext context) throws ScriptException {
        // Sync bindings to JS global scope
        // First sync GLOBAL_SCOPE, then ENGINE_SCOPE so ENGINE_SCOPE can shadow
        // GLOBAL_SCOPE

        final Bindings globalBindings = context.getBindings(ScriptContext.GLOBAL_SCOPE);
        if (globalBindings != null) {
            for (var entry : globalBindings.entrySet()) {
                this.context.setGlobal(entry.getKey(), entry.getValue());
            }
        }

        final Bindings engineBindings = context.getBindings(ScriptContext.ENGINE_SCOPE);
        if (engineBindings != null) {
            for (var entry : engineBindings.entrySet()) {
                this.context.setGlobal(entry.getKey(), entry.getValue());
            }
        }

        try {
            final Object result = this.context.eval(script);
            return result;
        } catch (Exception e) {
            throw new ScriptException(e);
        } finally {
            // Sync JS global scope back to bindings (JSR 223 usually only expects
            // ENGINE_SCOPE updates)
            if (engineBindings != null) {
                for (String key : engineBindings.keySet()) {
                    engineBindings.put(key, this.context.getGlobal(key));
                }
            }
        }
    }

    @Override
    public Object eval(Reader reader, ScriptContext context) throws ScriptException {
        final StringWriter sw = new StringWriter();
        try {
            reader.transferTo(sw);
        } catch (IOException e) {
            throw new ScriptException(e);
        }
        final String script = sw.toString();
        return eval(script, context);
    }

    @Override
    public Bindings createBindings() {
        return new SimpleBindings();
    }

    @Override
    public ScriptEngineFactory getFactory() {
        return factory;
    }

    @Override
    public Object invokeMethod(Object thiz, String name, Object... args) throws ScriptException, NoSuchMethodException {
        if (thiz instanceof QuickJSObject obj) {
            return obj.invokeFunction(name, args);
        } else {
            throw new IllegalArgumentException("Target thiz must be a QuickJSObject");
        }
    }

    @Override
    public Object invokeFunction(String name, Object... args) throws ScriptException, NoSuchMethodException {
        final Object member = this.context.getGlobal(name);
        if (member instanceof QuickJSFunction func) {
            return func.call(args);
        }
        throw new NoSuchMethodException("Function " + name + " not found or not a function");
    }

    @Override
    public <T> T getInterface(Class<T> clasz) {
        return this.context.getInterface(clasz);
    }

    @Override
    public <T> T getInterface(Object thiz, Class<T> clasz) {
        if (thiz instanceof QuickJSObject obj) {
            return this.context.getInterface(obj, clasz);
        }
        throw new IllegalArgumentException("Target object must be a QuickJSObject");
    }

    /**
     * Closes the underlying QuickJS runtime and context.
     */
    @Override
    public void close() {
        try {
            this.context.close();
            this.runtime.close();
        } catch (Exception e) {
            // Ignore
        }
    }

}
