# QuickJS Java

[![Maven CI](https://github.com/StefanRichterHuber/quickjs-wasm-java/actions/workflows/maven.yml/badge.svg)](https://github.com/StefanRichterHuber/quickjs-wasm-java/actions/workflows/maven.yml)

**QuickJS Java** is a Java library that allows you to embed and execute JavaScript code using the highly efficient [QuickJS engine by Fabrice Bellard](https://bellard.org/quickjs/). It leverages a Rust-built WebAssembly (Wasm) library, which in turn uses [rquickjs](https://github.com/DelSkayn/rquickjs), and executes it on the Java Virtual Machine (JVM) using [Chicory](https://chicory.dev/), a pure-Java WebAssembly runtime without native dependencies.

## Why QuickJS Java?

While several mature JavaScript runtimes exist for Java, such as [Nashorn](https://github.com/openjdk/nashorn) and [GraalVM JS](https://www.graalvm.org/latest/reference-manual/js/), they often integrate deeply with the Java runtime, potentially posing security or stability concerns by allowing extensive access to the JVM.

**QuickJS Java offers a distinct alternative with a focus on security, stability, and ease of deployment:**

*   **Secure Isolation:** Provides a lean, well-defined, and type-safe interface. JavaScript scripts can only access objects explicitly passed into the runtime, offering a sandboxed environment with no inherent access to the broader Java application.
*   **Resource Control:** Easily impose hard limits on script execution time, mitigating the impact of malicious or faulty scripts.
*   **Ideal for Scripting:** Perfect for integrating small, well-defined calculation or validation scripts. Its safe nature allows trusted users to write scripts without compromising application integrity.
*   **Zero Native Dependencies:** Unlike traditional approaches that require platform-specific JNI libraries, this library uses WebAssembly and Chicory, eliminating native dependencies at runtime. This simplifies deployment across different environments.

**Comparison with quickjs-java:**
This project is a successor to [quickjs-java](https://github.com/StefanRichterHuber/quickjs-java). The key difference is the approach to interfacing with QuickJS:
*   `quickjs-java` uses custom Rust-based JNI libraries, offering direct interaction without serialization overhead but requiring platform-specific builds.
*   `quickjs-wasm-java` employs WebAssembly, removing native library requirements but introducing serialization (MessagePack) overhead for complex data types. This is partly mitigated by native wrappers for JS objects and arrays, allowing direct manipulation without constant serialization/deserialization.

**Other JVM-based QuickJS projects:**
*   [Quack](https://github.com/koush/quack): "Quack provides Java (Android and desktop) bindings to JavaScript engines."
*   [QuickJS - KT](https://github.com/dokar3/quickjs-kt): "Run your JavaScript code in Kotlin, asynchronously."


## Getting Started

### Build Requirements

To build the project, you need:

*   Java 21 or newer
*   Rust with `cargo`
*   The `wasm32-wasip1` target for Rust. Install it using:
    ```bash
    rustup target add wasm32-wasip1
    ```

### Building the Project

The project uses Maven and is pre-configured to build the WebAssembly library using the `exec-maven-plugin`.

*   **Standard Debug Build:** A simple `mvn clean install` will build and test the entire library. This uses a faster debug build of the Wasm library, suitable for development, but with lower runtime performance. The `chicory-compiler-maven-plugin` will operate with interpreter fallback enabled.
*   **Optimized Release Build:** For optimal performance, use the `release` Maven profile. This triggers a fully optimized release build of the Rust Wasm library:
    ```bash
    mvn -P release clean install
    ```
    The `chicory-compiler-maven-plugin` then compiles the Wasm bytecode to native code. This approach offers superior performance compared to interpreter mode, faster startup times, and fewer dependencies than Chicory's runtime compiler, making it ideal for GraalVM native images.


### Usage

To use the library, add the following Maven dependency to your `pom.xml`. Replace `[current version]` with the appropriate version number.

```xml
<dependency>
    <groupId>io.github.stefanrichterhuber</groupId>
    <artifactId>quickjs-wasm-java</artifactId>
    <version>[current version]</version>
</dependency>
```

Here's a basic example demonstrating how to initialize the runtime, create a context, and execute JavaScript:

```java
import io.github.stefanrichterhuber.quickjswasmjava.QuickJSContext;
import io.github.stefanrichterhuber.quickjswasmjava.QuickJSRuntime;

import java.util.function.BiFunction;

public class QuickJSExample {
    public static void main(String[] args) {
        try (QuickJSRuntime runtime = new QuickJSRuntime();
             QuickJSContext context = runtime.createContext()) {

            // Export a Java function to JavaScript
            BiFunction<Integer, Integer, Integer> add = (a, b) -> {
                System.out.println("Java function 'add' called with: " + a + ", " + b);
                return a + b;
            };
            context.setGlobal("add", add);

            // Evaluate JavaScript code
            Object result = context.eval("add(1, 2)");
            System.out.println("Result from JavaScript: " + result); // Expected: 3

            // Accessing global variables from Java
            context.eval("let message = 'Hello from QuickJS!';");
            Object message = context.getGlobal("message");
            System.out.println("Message from JavaScript: " + message); // Expected: "Hello from QuickJS!"

            // Error handling
            try {
                context.eval("throw new Error('Something went wrong in JS!');");
            } catch (Exception e) {
                System.err.println("JavaScript error caught: " + e.getMessage());
            }

        }
    }
}
```

For more comprehensive examples and detailed usage patterns, refer to the unit tests: [`io.github.stefanrichterhuber.quickjswasmjava.QuickJSContextTest`](src/test/java/io/github/stefanrichterhuber/quickjswasmjava/QuickJSContextTest.java).


## Type Mapping

The library handles seamless translation between supported Java and JavaScript types. Most Java values are copied by value into the JavaScript context. However, for efficient interaction with complex data structures, `io.github.stefanrichterhuber.quickjswasmjava.QuickJSArray` and `io.github.stefanrichterhuber.quickjswasmjava.QuickJSObject` act as thin wrappers over native QuickJS arrays and objects. Changes made to these wrapper objects from either Java or JavaScript are directly reflected on the other side, avoiding costly serialization/deserialization for large structures. Both `QuickJSArray` and `QuickJSObject` can contain any other supported Java object, allowing for deeply nested structures.

| Java Type | JS Type | Remark |
| :-------- | :------ | :----- |
| `null` | `null` / `undefined` | JavaScript `undefined` is translated to Java `null`. |
| `java.lang.Boolean` | `boolean` | Internally handles boxing/unboxing for boolean values. |
| `java.lang.Double` / `java.lang.Float` | `number` | Internally handles boxing/unboxing for floating-point numbers. |
| `java.lang.Integer` | `number` | Internally handles boxing/unboxing for integer numbers. |
| `java.lang.String` | `string` | |
| `io.github.stefanrichterhuber.quickjswasmjava.QuickJSException` | `Error` (exception) | JavaScript exceptions are translated to `QuickJSException` objects in Java. Each exception includes a message and a stack trace (with exact line and column numbers). Java exceptions thrown within callbacks are transformed into JavaScript exceptions and then returned to Java as `QuickJSException`. Note that the original Java stack trace is lost in this process. |
| `io.github.stefanrichterhuber.quickjswasmjava.QuickJSArray<Object>` | `array` | Wraps native JavaScript arrays. Values can be any supported type, including mixed types and nested lists/maps. Changes are reflected bi-directionally. |
| `io.github.stefanrichterhuber.quickjswasmjava.QuickJSObject<String, Object>` | `object` | Wraps native JavaScript objects. Keys can be strings, numbers, or booleans. Values can be any supported type, including mixed types and nested lists/maps. Changes are reflected bi-directionally. |
| `java.util.List<Object>` | `array` | Any `java.util.List` (not a `QuickJSArray`) is copied by value to the JavaScript context. If returned to Java, it is translated into a `QuickJSArray`. |
| `java.util.Map<String, Object>` | `object` | Any `java.util.Map` (not a `QuickJSObject`) is copied by value to the JavaScript context. Keys must be strings. If returned to Java, it is translated into a `QuickJSObject`. |
| `io.github.stefanrichterhuber.quickjswasmjava.QuickJSFunction` | `function` | Native JavaScript functions are exported to Java as `QuickJSFunction` objects. |
| `java.util.function.Function<P, R>` | `function` | Java `Function` objects can be exported to JavaScript. If a JavaScript function is transferred back to Java that originated from a `Function<P, R>`, it is translated to a `java.util.function.Function<java.util.List<Object>, Object>` where the `List` contains the JavaScript arguments. |
| `java.util.function.BiFunction<P, Q, R>` | `function` | Java `BiFunction` objects can be exported to JavaScript. If a JavaScript function is transferred back to Java that originated from a `BiFunction<P, Q, R>`, it is translated to a `java.util.function.Function<java.util.List<Object>, Object>` where the `List` contains the JavaScript arguments. |
| `java.util.function.Consumer<P>` | `function` | Java `Consumer` objects can be exported to JavaScript. If a JavaScript function is transferred back to Java that originated from a `Consumer<P>`, it is translated to a `java.util.function.Function<java.util.List<Object>, Object>` (returning `null`). |
| `java.util.function.BiConsumer<P, Q>` | `function` | Java `BiConsumer` objects can be exported to JavaScript. If a JavaScript function is transferred back to Java that originated from a `BiConsumer<P, Q>`, it is translated to a `java.util.function.Function<java.util.List<Object>, Object>` (returning `null`). |
| `java.util.function.Supplier<R>` | `function` | Java `Supplier` objects can be exported to JavaScript. If a JavaScript function is transferred back to Java that originated from a `Supplier<R>`, it is translated to a `java.util.function.Function<java.util.List<Object>, Object>` (with an empty argument `List`). |

## Technical Details

### Architecture Overview

The library is fundamentally composed of two main components:

1.  **Java Library:** Provides a type-safe and user-friendly interface for interacting with JavaScript.
2.  **Wasm Library:** Implements the actual QuickJS runtime using Rust and `rquickjs`, compiled to WebAssembly.

### Java Library Internals

The Java library acts as a wrapper, exposing typesafe interaction points. The core entry point is `io.github.stefanrichterhuber.quickjswasmjava.QuickJSRuntime`, which manages the WebAssembly instance and resource constraints. It creates `io.github.stefanrichterhuber.quickjswasmjava.QuickJSContext` objects, each representing a unique JavaScript execution context.

**Key Dependencies:**
*   `log4j2`: For unified logging (Java and Rust).
*   `Chicory`: The WebAssembly runtime for executing the Wasm module.
*   `MessagePack`: Used for efficient serialization of data between Java and Rust.

**Resource Management:**
To prevent memory leaks, native QuickJS objects (contexts, runtimes, functions, objects, and arrays) must be properly managed. While `QuickJSContext` manages the lifecycle of functions, objects, and arrays created within it (closing them when the context is closed), and `QuickJSRuntime` manages all its created contexts (closing them when the runtime is closed), it is **highly recommended** to explicitly close both `QuickJSRuntime` and `QuickJSContext` instances when they are no longer needed, ideally using Java's try-with-resources statement.

### WebAssembly Library Internals

The Wasm library is built with Rust, leveraging `rquickjs` to interface with QuickJS. It targets `wasm32-wasip1`, currently the only supported target for Chicory, to communicate with the JVM.

A central `JSJavaProxy` struct facilitates type conversion between Java and JavaScript. This struct represents all transferable types and handles data serialization (using MessagePack and `serde`) for cross-runtime communication.

The `wasm_macros` crate, specifically its `wasm_export` macro, is crucial. It simplifies the definition of exported Rust functions by:
*   Hiding the serialization/deserialization of `JSJavaProxy` objects.
*   Managing the pointer logic necessary to address native QuickJS objects (runtime, context, arrays, objects) outside of Rust's standard lifetime model.
This allows for clean and lean function signatures in Rust.

**Logging:**
The `log` crate is used on the Rust side. All Rust log messages are forwarded to the Java side and processed by `log4j2` under the logger name `io.github.stefanrichterhuber.quickjswasmjava.native.WasmLib`.

## Issues

Please report any issues or feature requests on the [GitHub Issues page](https://github.com/StefanRichterHuber/quickjs-wasm-java/issues).

## License

Licensed under MIT License ([LICENSE](LICENSE) or <http://opensource.org/licenses/MIT>)

