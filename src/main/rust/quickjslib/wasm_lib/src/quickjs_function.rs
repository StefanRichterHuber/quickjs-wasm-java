use rquickjs::{Context, Function, Persistent};
use wasm_macros::wasm_export;

use crate::js_to_java_proxy::JSJavaProxy;

#[wasm_export]
pub fn call_function<'js>(
    context: &Context,
    persistent_function: &Persistent<Function<'static>>,
    args: JSJavaProxy,
) -> JSJavaProxy {
    let result = context.with(|ctx| {
        let function = persistent_function.clone().restore(&ctx).unwrap();

        let result = match function.call(args) {
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
