package io.github.stefanrichterhuber.quickjswasmjava.jsr223;

import java.util.Arrays;
import java.util.List;

import javax.script.ScriptEngine;
import javax.script.ScriptEngineFactory;

public class QuickJSScriptEngineFactory implements ScriptEngineFactory {

    @Override
    public String getEngineName() {
        return "QuickJS Wasm Java";
    }

    @Override
    public String getEngineVersion() {
        return "1.0";
    }

    @Override
    public List<String> getExtensions() {
        return Arrays.asList("js");
    }

    @Override
    public List<String> getMimeTypes() {
        return Arrays.asList("application/javascript", "text/javascript");
    }

    @Override
    public List<String> getNames() {
        return Arrays.asList("QuickJS", "quickjs", "js", "JavaScript", "javascript", "ECMAScript", "ecmascript");
    }

    @Override
    public String getLanguageName() {
        return "JavaScript";
    }

    @Override
    public String getLanguageVersion() {
        return "ES2023"; // QuickJS is fairly up to date
    }

    @Override
    public Object getParameter(String key) {
        if (key.equals(ScriptEngine.ENGINE)) {
            return getEngineName();
        } else if (key.equals(ScriptEngine.ENGINE_VERSION)) {
            return getEngineVersion();
        } else if (key.equals(ScriptEngine.NAME)) {
            return getNames().get(0);
        } else if (key.equals(ScriptEngine.LANGUAGE)) {
            return getLanguageName();
        } else if (key.equals(ScriptEngine.LANGUAGE_VERSION)) {
            return getLanguageVersion();
        }
        return null;
    }

    @Override
    public String getMethodCallSyntax(String obj, String m, String... args) {
        StringBuilder sb = new StringBuilder();
        sb.append(obj).append(".").append(m).append("(");
        for (int i = 0; i < args.length; i++) {
            sb.append(args[i]);
            if (i < args.length - 1) {
                sb.append(",");
            }
        }
        sb.append(")");
        return sb.toString();
    }

    @Override
    public String getOutputStatement(String toDisplay) {
        return "console.log(" + toDisplay + ")";
    }

    @Override
    public String getProgram(String... statements) {
        StringBuilder sb = new StringBuilder();
        for (String statement : statements) {
            sb.append(statement).append(";\n");
        }
        return sb.toString();
    }

    @Override
    public ScriptEngine getScriptEngine() {
        return new QuickJSScriptEngine(this);
    }

}
