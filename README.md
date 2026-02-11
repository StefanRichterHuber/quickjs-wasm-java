[![Maven CI](https://github.com/StefanRichterHuber/quickjs-wasm-java/actions/workflows/maven.yml/badge.svg)](https://github.com/StefanRichterHuber/quickjs-wasm-java/actions/workflows/maven.yml)

# QuickJS Java

This is a Java library to use [QuickJS from Fabrice Bellard](https://bellard.org/quickjs/) with Java. It uses a wasm library build with Rust which uses [rquickjs](https://github.com/DelSkayn/rquickjs) to interface QuickJS. To execute the wasm library, it uses [Chicory](https://chicory.dev/) a wasm runtime for Java without native dependencies.

## Why another JavaScript runtime for Java?

There are several (more) mature JavaScript runtimes for Java like

- [Nashorn](https://github.com/openjdk/nashorn)
- [GraalVM JS](https://www.graalvm.org/latest/reference-manual/js/), which also runs independently of GraalVM

All of these deeply integrate with the Java runtime and allow full access of JS scripts into the Java runtime. For some applications this might be a security or stability issue. This runtime, on the other hand, only has a very lean interface between Java and Javascript. Scripts can only access the objects explicitly passed into the runtime and have no other access to the outside world. Furthermore hard limits on time and memory consumption of the scripts can be easily set, to limit the impact of malicious or faulty scripts on your applications. This is especially great to implement some calculation or validation scripts, so very small scripts with a small, very well defined scope. Due to the safe nature of this runtime, you can pass writing this scripts to trusted users without compromising the integrity of the rest of your application.
A main goal of this implementation is to provide a very clean, yet efficient and type-safe interface.

There is already another take from me on this topic [quickjs-java](https://github.com/StefanRichterHuber/quickjs-java), which is also a Java Library wrapping QuickJS. It is, however, using a custom rust based JNI library to interface with QuickJS. This means a custom native library has to be build for each platform. One the other hand, due to direct interaction of rust code with java using JNI, no serializing of data between the native libraries and Java runtime is necessary. Java objects are directly created / accessed in the native code and only references to this objectes are transfered between Java and the native library. 

This library avoids native libraries by using wasm and [Chicory](https://chicory.dev/) (a wasm runtime for Java without native dependencies). This means zero native dependencies at runtime. On the other hand, this requires a wasm library to be build, which is one one hand less complex than building a native library for each plaform, but still requires Rust and Cargo to be installed for building the wasm library. In webassembly only primitives and memory can be shared, so any complex object must be serialized (with MessagePack), written to the shared memory and than read by the other side and deserialized. This could be a significant performance overhead for more complex objects. 

There are, however, other projects binding QuickJS to the JVM, which might be worth looking at:

- [Quack](https://github.com/koush/quack): "Quack provides Java (Android and desktop) bindings to JavaScript engines."s
- [QuickJS - KT](https://github.com/dokar3/quickjs-kt): "Run your JavaScript code in Kotlin, asynchronously."

## Build

You need Java 21 and Rust with `cargo` with the target `wasm32-wasip1` (`rustup target add wasm32-wasip1`) installed to build this project. Maven is pre-configured to build the wasmlibrary using the `exec-maven-plugin`. So a simple `mvn clean install` is enough to build and test the whole library. 

## How to use

Import library (the library is *not yet* available in maven central, so you need to build it first)

```xml
<dependency>
    <groupId>io.github.stefanrichterhuber</groupId>
    <artifactId>quickjs-wasm-java</artifactId>
    <version>[current version]</version>
</dependency>
```

Use it

```java
try (QuickJSRuntime runtime = new QuickJSRuntime();
        QuickJSContext context = runtime.createContext()) {

    BiFunction<Integer, Integer, Integer> add = (a, b) -> {
        return a + b;
    };

    context.setGlobal("add", add);
    Object result = context.eval("add(1, 2)");
    assertEquals(3, result);
}
```

See all tests in `io.github.stefanrichterhuber.quickjswasmjavaQuickJSContextTest` for more examples of all the proven capabilities of this script runtime.


### Supported types

The library seamlessly translates all supported Java types to JS types and vice versa. Most values are copied by value into the JS context, apart from the special objects `io.github.stefanrichterhuber.quickjswasmjava.QuickJSArray` and `io.github.stefanrichterhuber.quickjswasmjava.QuickJSObject`. These are thin wrappers over native JS arrays / objects. Any change to these object from either JS or java code are directly reflected on the other side. This allows lean interaction with data without huge objects serialized back and forth with each change. Both containers allow every other supported java object as value (including other lists / maps and functions) so deep nested structures are possible!

| Java Type | JS Type | Remark |
| --- | --- | --- |
| `null` | `null` / `undefined` | JS  `undefined` is translated to `null` |
| `java.lang.Boolean` | `boolean` | QuickJS only supports boxed booleans |
| `java.lang.Double` / `java.lang.Float` | `number` | QuickJS only supports boxed double values |
| `java.lang.Integer` | `number` | QuickJS only supports boxed integer values |
| `java.lang.String` | `string` | |
| `io.github.stefanrichterhuber.quickjswasmjava.QuickJSException` | `exception` | JS exceptions are translated to QuickJSException objects. Each exception contains a message and a stack trace (with exact line and column numbers)! Java exceptions thrown in callbacks are transformed into a js exception and returned to Java as QuickJSException objects. The original java stacktrace, is lost, however. |
| `io.github.stefanrichterhuber.quickjswasmjava.QuickJSArray<Object>` | `array` | JS arrays are wrapped as QuickJSArray objects. The Values can be any supported type, including mixed types and nested lists/maps. |
| `io.github.stefanrichterhuber.quickjswasmjava.QuickJSObject<String, Object>` | `object` | JS object are wrapped as QuickJSObject objects. Keys can be strings, numbers and boolean.  The Values can be any supported type, including mixed types and nested lists/maps. |
| `java.util.List<Object>` | `array` | Any java list which is not a `io.github.stefanrichterhuber.quickjswasmjava.QuickJSArray` is copied by value into the JS context. If returned to the java context, a `io.github.stefanrichterhuber.quickjswasmjava.QuickJSArray` is returned.  The Values can be any supported type, including mixed types and nested lists/maps. |
| `java.util.Map<String, Object>` | `object` | Any java map which is not a `io.github.stefanrichterhuber.quickjswasmjava.QuickJSObject` is copied by value into JS context. The Values can be any supported type, including mixed types and nested lists/maps. Keys can be only strings! If returned to the java context, a `io.github.stefanrichterhuber.quickjswasmjava.QuickJSObject` is returned. |
| `io.github.stefanrichterhuber.quickjswasmjava.QuickJsFunction` | `function` | native JS functions are exported to Java as QuickJsFunction objects.  |
| `java.util.function.Function<P, R>` | `function` | Java functions can be exported to JS as functions. If the function is transferred back from JS to Java, it is translated to a `java.util.function.Function<List<Object>, Object>` object. |
| `java.util.function.BiFunction<P, Q, R>` | `function` | Java functions can be exported to JS as functions. If the function is transferred back from JS to Java, it is translated to a `java.util.function.Function<List<Object>, List<Object>, Object>` object. |
| `java.util.function.Consumer<P>` | `function` | Java functions can be exported to JS as functions. If the function is transferred back from JS to Java, it is translated to a `java.util.function.Function<List<Object>, Object>` object. |
| `java.util.function.BiConsumer<P, Q>` | `function` | Java functions can be exported to JS as functions. If the function is transferred back from JS to Java, it is translated to a `java.util.function.Function<List<Object>, List<Object>>` object. |
| `java.util.function.Supplier<R>` | `function` | Java functions can be exported to JS as functions. If the function is transferred back from JS to Java, it is translated to a `java.util.function.Function<List<Object>, Object>` object. |

### Logging

This library uses log4j2 for logging on the java side and the `log` crate on the Rust side. Log messages from the native library are passed into the JVM and logged using log4j2 with the logger name `io.github.stefanrichterhuber.quickjswasmjava.native.WasmLib`.

## Issues

 - [ ] Pre-compile the wasm library to chicory byte-code at build-time for best perfomance. Currently it is not possible pre-compile the wasm library to byte code due to size restrictions, because a single quickjs function is too large to become a bytecode function: `using interpreted mode for WASM function index: 2587 (name: JS_CallInternal)`

## Architecture

### Java library

The java library is bascally a wrapper over quicks with mainly typesafe interaction points exposed. The main entrypoint is `QuickJSRuntime` for the main runtime, also managing resource constraints and `QuickJSContext` for a unique the execution context. There are dependencies to log4j2 for logging, Chicory as wasm runtime and MessagePack for serializing data between Java and Rust.

### Wasm library

A wasm library build with Rust which uses rquickjs to interface QuickJS. It uses wasip1 (at the time of writing the only supported target for Chicory) to interface with the JVM. A central struct `JSJavaProxy` is used to convert between Java and JS types and vice versa. It represents all types that can be passed between Java and JS and is used to transfer data between the two runtimes, by serializing to MessagePack and serde. A huge macro `wasm_export` in the `wasm_macros` crate is used to define the exported functions from rust to Java. It both conveniently hides the serialization and deserialization of JSJavaProxy objects and the pointer-magic necessary to address native objects (QuickJS runtime and context but also JS arrays and objects) outside the rust lifecyle and lifetime-model. This way one can define functions in rust with a lean and clean signature.
The `log` crate is used for logging on the rust side. All log messages are passed to the java side. 

## License

Licensed under MIT License ([LICENSE](LICENSE) or <http://opensource.org/licenses/MIT>)
