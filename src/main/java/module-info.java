module io.github.stefanrichterhuber.quickjswasmjava {
    requires java.scripting;
    requires org.apache.logging.log4j;
    requires transitive msgpack.core;
    requires com.dylibso.chicory.runtime;
    requires com.dylibso.chicory.wasi;
    requires com.dylibso.chicory.wasm;

    exports io.github.stefanrichterhuber.quickjswasmjava;
    exports io.github.stefanrichterhuber.quickjswasmjava.jsr223;

    provides javax.script.ScriptEngineFactory with io.github.stefanrichterhuber.quickjswasmjava.jsr223.QuickJSScriptEngineFactory;
}
