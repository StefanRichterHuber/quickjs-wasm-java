use log::debug;
use log::error;
use rquickjs::Context;
use rquickjs::Ctx;
use rquickjs::Error;
use rquickjs::Runtime;
use wasm_macros::wasm_export;

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
pub fn handle_error(err: Error, ctx: Ctx<'_>) -> JSJavaProxy {
    match err {
        rquickjs::Error::Exception => {
            let catch = ctx.catch();
            if let Some(exception) = catch.as_exception() {
                let message = exception.message().unwrap();
                let stacktrace = exception.stack().unwrap();
                JSJavaProxy::Exception(message, stacktrace)
            } else {
                JSJavaProxy::Exception(err.to_string(), String::new())
            }
        }
        _ => JSJavaProxy::Exception(err.to_string(), String::new()),
    }
}

#[wasm_export]
pub fn eval_script(context: &Context, script: String) -> JSJavaProxy {
    debug!("Evaluating script: {}", script);
    let result = context.with(|ctx| {
        let result: JSJavaProxy = match ctx.eval(script) {
            Ok(value) => match JSJavaProxy::convert(&value) {
                Ok(value) => value,
                Err(err) => handle_error(err, ctx),
            },
            Err(err) => {
                error!("Error evaluating script: {:?}", err);
                handle_error(err, ctx)
            }
        };
        result
    });
    debug!("Script evaluated: {:?}", result);

    result
}

#[wasm_export]
pub fn set_global(context: &Context, name: String, value: JSJavaProxy) -> JSJavaProxy {
    debug!("Setting global: {} = {:?}", name, value);
    let result = context.with(|ctx| {
        let global = ctx.globals();
        let result: JSJavaProxy = match global.set(name, value) {
            Ok(_) => JSJavaProxy::Null,
            Err(err) => handle_error(err, ctx),
        };
        result
    });
    result
}

#[wasm_export]
pub fn get_global(context: &Context, name: String) -> JSJavaProxy {
    debug!("Getting global: {}", name);
    let result = context.with(|ctx| {
        let global = ctx.globals();
        let result: JSJavaProxy = match global.get(name) {
            Ok(value) => match JSJavaProxy::convert(&value) {
                Ok(value) => value,
                Err(err) => handle_error(err, ctx),
            },
            Err(err) => handle_error(err, ctx),
        };
        result
    });
    result
}
