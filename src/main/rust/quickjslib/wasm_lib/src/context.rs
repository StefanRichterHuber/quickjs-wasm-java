use log::info;
use rquickjs::Context;
use rquickjs::Runtime;
use wasm_macros::wasm_export;

use crate::js_to_java_proxy::JSJavaProxy;

#[wasm_export]
pub fn create_context(runtime: &Runtime) -> Box<Context> {
    println!("Created new QuickJS context");

    let context = Context::full(runtime).unwrap();
    Box::new(context)
}

#[wasm_export]
pub fn close_context(context: Box<Context>) {
    drop(context);
}

#[wasm_export]
pub fn eval_script(context: &Context, script: String) -> JSJavaProxy {
    println!("Evaluating script: {}", script);
    let result = context.with(|ctx| {
        let result: JSJavaProxy = ctx.eval(script).unwrap();
        result
    });
    println!("Script evaluated: {:?}", result);

    result
}

