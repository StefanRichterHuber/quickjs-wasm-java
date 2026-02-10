# QuickJS Java

This is a Java library to use [QuickJS from Fabrice Bellard](https://bellard.org/quickjs/) with Java. It uses a wasm library build with Rust which uses [rquickjs](https://github.com/DelSkayn/rquickjs) to interface QuickJS. To execute the wasm library, it uses [Chicory](https://chicory.dev/) a wasm runtime for Java without native dependencies.

## Why another JavaScript runtime for Java?

There are several (more) mature JavaScript runtimes for Java like

- [Nashorn](https://github.com/openjdk/nashorn)
- [GraalVM JS](https://www.graalvm.org/latest/reference-manual/js/), which also runs independently of GraalVM

All of these deeply integrate with the Java runtime and allow full access of JS scripts into the Java runtime. For some applications this might be a security or stability issue. This runtime, on the other hand, only has a very lean interface between Java and Javascript. Scripts can only access the objects explicitly passed into the runtime and have no other access to the outside world. Furthermore hard limits on time and memory consumption of the scripts can be easily set, to limit the impact of malicious or faulty scripts on your applications. This is especially great to implement some calculation or validation scripts, so very small scripts with a small, very well defined scope. Due to the safe nature of this runtime, you can pass writing this scripts to trusted users without compromising the integrity of the rest of your application.
A main goal of this implementation is to provide a very clean, yet efficient and type-safe interface.

There is already another take on this topic [quickjs-java](https://github.com/StefanRichterHuber/quickjs-java), which is also a Java Library wrapping QuickJS. It is, however, using a custom rust based JNI library to interface with QuickJS. This means a custom native library has to be build for each platform. This approach avoids this by using wasm and chicory - zero native dependencies at runtime. On the other hand, this requires a wasm library to be build, which adds some build complexity (requires Rust with cargo).


There are, however, other projects binding QuickJS to the JVM, which might be worth looking at:

- [Quack](https://github.com/koush/quack): "Quack provides Java (Android and desktop) bindings to JavaScript engines."s
- [QuickJS - KT](https://github.com/dokar3/quickjs-kt): "Run your JavaScript code in Kotlin, asynchronously."

## Build

You need Java 21 and Rust with `cargo` with the target `wasm32-wasip1` (`rustup target add wasm32-wasip1`) installed to build this project. Maven is pre-configured to build the wasmlibrary using the `exec-maven-plugin`. So a simple `mvn clean install` is enough to build and test the whole library. 

## How to use

Import library

```xml
<dependency>
    <groupId>io.github.stefanrichterhuber</groupId>
    <artifactId>quickjs-wasm-java</artifactId>
    <version>[current version]</version>
</dependency>
```

### Supported types

The library seamlessly translates all supported Java types to JS types and vice versa. A JS Functions are exported to Java as QuickJsFunction objects.

| Java Type | JS Type | Remark |
| --- | --- | --- |
| `null` | `null` / `undefined` | JS  `undefined` is translated to `null` |
| `java.lang.Boolean` | `boolean` | QuickJS only supports boxed booleans |
| `java.lang.Double` / `java.lang.Float` | `number` | QuickJS only supports boxed double values |
| `java.lang.Integer` | `number` | QuickJS only supports boxed integer values |
| `java.lang.String` | `string` | |
| `QuickJSException` | `exception` | JS exceptions are translated to QuickJSException objects. |
| `java.util.List<Object>` | `array` | As of now java lists are copied to JS and vice versa. Values can be any supported type, including mixed types and nested lists/maps |
| `java.util.Map<String, Object>` | `object` | As of now java maps are copied to JS and vice versa. Values can be any supported type, including mixed types and nested lists/maps. Keys must be strings |
| `QuickJsFunction` | `function` | native JS functions are exported to Java as QuickJsFunction objects.  |
| `java.util.function.Function<P, R>` | `function` | Java functions can be exported to JS as functions. If the function is transferred back from JS to Java, it is translated to a `java.util.function.Function<List<Object>, Object>` object. |
| `java.util.function.BiFunction<P, Q, R>` | `function` | Java functions can be exported to JS as functions. If the function is transferred back from JS to Java, it is translated to a `java.util.function.Function<List<Object>, List<Object>, Object>` object. |
| `java.util.function.Consumer<P>` | `function` | Java functions can be exported to JS as functions. If the function is transferred back from JS to Java, it is translated to a `java.util.function.Function<List<Object>, Object>` object. |
| `java.util.function.BiConsumer<P, Q>` | `function` | Java functions can be exported to JS as functions. If the function is transferred back from JS to Java, it is translated to a `java.util.function.Function<List<Object>, List<Object>>` object. |
| `java.util.function.Supplier<R>` | `function` | Java functions can be exported to JS as functions. If the function is transferred back from JS to Java, it is translated to a `java.util.function.Function<List<Object>, Object>` object. |



### Logging

This library uses log4j2 for logging on the java side and the `log` crate on the Rust side. Log messages from the native library are passed into the JVM and logged using log4j2 with the logger name `io.github.stefanrichterhuber.quickjswasmjava.native.WasmLib`.


## Architecture

1. A java library with dependencies to log4j2 and chicory. MessagePack is used to serialize data between Java and Rust.
2. A wasm library build with Rust which uses rquickjs to interface QuickJS. It uses wasip1 to interface with the JVM. A central struct `JSJavaProxy` is used to convert between Java and JS types and vice versa. It represents all types that can be passed between Java and JS.
3 `QuickJS` runtime
