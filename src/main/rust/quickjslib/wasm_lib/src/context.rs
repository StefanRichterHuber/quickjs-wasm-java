use log::debug;
use log::error;
use rquickjs::Context;
use rquickjs::Ctx;
use rquickjs::Error;
use rquickjs::Runtime;
use wasm_macros::wasm_export;

use crate::from_error::FromError;
use crate::js_to_java_proxy::JSJavaProxy;

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

/// Handles errors that occur during QuickJS operations.
///
/// This function converts `rquickjs::Error` into `JSJavaProxy::Exception`.
/// If the error is an `Exception`, it attempts to extract the message and stack trace.
/// Otherwise, it falls back to the standard error string representation.
///
/// # Arguments
///
/// * `err` - The error to handle.
/// * `ctx` - The QuickJS context, used to catch and inspect exceptions.
///
/// # Returns
///
/// A `JSJavaProxy::Exception` containing the error message and stack trace.
pub fn handle_error<'js, V: FromError<'js>>(err: Error, ctx: &Ctx<'js>) -> V {
    V::from_err(ctx, err)
}

#[wasm_export]
pub fn eval_script(ctx: &Ctx<'_>, script: String) -> rquickjs::Result<JSJavaProxy> {
    debug!("Evaluating script: {}", script);
    ctx.eval(script)
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
