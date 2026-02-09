use rquickjs::{Context, Function, Persistent};
use wasm_macros::wasm_export;

use crate::js_to_java_proxy::JSJavaProxy;

#[wasm_export]
pub fn call_function<'js>(
    context: &Context,
    persistent_function: &Persistent<Function<'static>>,
) -> JSJavaProxy {
    let result = context.with(|ctx| {
        let function = persistent_function.clone().restore(&ctx).unwrap();
        // TODO pass arguments
        let result = match function.call(()) {
            Ok(value) => value,
            Err(err) => {
                println!("Error calling function: {}", err);
                return JSJavaProxy::Undefined;
            }
        };
        result
    });
    println!("Function called result: {:?}", result);

    result
}
