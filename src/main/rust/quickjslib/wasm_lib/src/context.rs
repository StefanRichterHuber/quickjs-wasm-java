use log::{debug, info};
use rquickjs::Context;
use rquickjs::Runtime;
use wasm_macros::wasm_export;

use crate::js_to_java_proxy::JSJavaProxy;

#[wasm_export]
pub fn create_context(runtime: &Runtime) -> Box<Context> {
    let context = Context::full(runtime).unwrap();
    info!("Created new QuickJS context");
    Box::new(context)
}

#[wasm_export]
pub fn close_context(context: Box<Context>) {
    info!("Closing QuickJS context");
    drop(context);
}

#[wasm_export]
pub fn eval_script(context: &Context, script: String) -> JSJavaProxy {
    debug!("Evaluating script: {}", script);
    let result = context.with(|ctx| {
        let result: JSJavaProxy = ctx.eval(script).unwrap();
        result
    });
    debug!("Script evaluated: {:?}", result);

    result
}

#[wasm_export]
pub fn set_global(context: &Context, name: String, value: JSJavaProxy) {
    debug!("Setting global: {} = {:?}", name, value);
    context.with(|ctx| {
        let global = ctx.globals();
        global.set(name, value).unwrap();
    });
}
