use log::debug;
use log::error;
use rquickjs::Context;
use rquickjs::Ctx;
use rquickjs::FromJs;
use rquickjs::JsLifetime;
use rquickjs::Runtime;
use wasm_macros::wasm_export;

use crate::js_to_java_proxy::JSJavaProxy;

pub struct ContextPtr {
    pub ptr: u64,
}

impl ContextPtr {
    pub fn new(ptr: u64) -> Self {
        ContextPtr { ptr }
    }
}

unsafe impl<'js> JsLifetime<'js> for ContextPtr {
    type Changed<'to> = ContextPtr;
}

#[wasm_export]
pub fn create_context(runtime: &Runtime) -> Box<Context> {
    let context = Context::full(runtime).unwrap();
    debug!("Created new QuickJS context");
    Box::new(context)
}

#[wasm_export]
pub fn close_context(context: Box<Context>) {
    debug!("Closing QuickJS context");
    drop(context);
}

#[wasm_export]
pub fn eval_script(ctx: &Ctx<'_>, script: String) -> rquickjs::Result<JSJavaProxy> {
    debug!("Evaluating script: {}", script);
    ctx.eval(script)
}

#[wasm_export]
pub fn eval_script_async(ctx: &Ctx<'_>, script: String) -> rquickjs::Result<JSJavaProxy> {
    debug!("Evaluating async script: {}", script);
    let promise = ctx.eval_promise(script)?;
    let result = JSJavaProxy::from_js(&ctx, promise.into_value())?;
    Ok(result)
}

#[wasm_export]
pub fn poll(ctx: &Ctx<'_>) -> rquickjs::Result<bool> {
    debug!("Polling async script");
    Ok(ctx.execute_pending_job())
}

/// Invokes a function in the QuickJS context.
#[wasm_export]
pub fn invoke(ctx: &Ctx<'_>, name: String, args: JSJavaProxy) -> rquickjs::Result<JSJavaProxy> {
    let f: rquickjs::Value = ctx.globals().get(&name)?;

    let result = if f.is_function() {
        let function = f.as_function().unwrap();
        let result: JSJavaProxy = function.call(args)?;
        Ok(result)
    } else {
        error!("Function {} is not a function", &name);
        Err(rquickjs::Error::Exception)
    };
    result
}

#[wasm_export]
pub fn set_global(
    ctx: &Ctx<'_>,
    name: String,
    value: JSJavaProxy,
) -> rquickjs::Result<JSJavaProxy> {
    debug!("Setting global: {} = {:?}", name, value);
    let global = ctx.globals();
    global.set(name, value)?;
    Ok(JSJavaProxy::Null)
}

#[wasm_export]
pub fn get_global(ctx: &Ctx<'_>, name: String) -> rquickjs::Result<JSJavaProxy> {
    let global = ctx.globals();
    global.get(name)?
}
