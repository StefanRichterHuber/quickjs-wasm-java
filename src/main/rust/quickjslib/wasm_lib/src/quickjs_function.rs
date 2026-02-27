use log::debug;
use log::error;
use rquickjs::IntoJs;
use rquickjs::Value;
use rquickjs::{
    function::{IntoJsFunc, ParamRequirement},
    Context, Ctx, Function, Persistent,
};
use wasm_macros::wasm_export;

use crate::js_to_java_proxy::JSJavaProxy;

#[wasm_export]
pub fn call_function<'js>(
    ctx: &Ctx<'_>,
    persistent_function: &Persistent<Function<'static>>,
    args: JSJavaProxy,
) -> rquickjs::Result<JSJavaProxy> {
    let function = persistent_function.clone().restore(&ctx)?;
    debug!("Calling function with args: {:?}", args);
    function.call(args)?
}

#[wasm_export]
pub fn close_function(_context: &Context, object: Box<Persistent<Function<'static>>>) -> bool {
    debug!("Closing js function wrapper");
    drop(object);
    true
}

#[link(wasm_import_module = "env")]
extern "C" {
    pub fn call_java_function(
        context: i32,
        function: i32,
        args_ptr: *const u8,
        args_len: usize,
    ) -> i64;
}

pub struct JavaFunction {
    call: Box<dyn Fn(JSJavaProxy) -> JSJavaProxy>,
}

impl JavaFunction {
    pub fn new(context: i32, func: i32) -> Self {
        debug!("Creating Java function: {} on context {}", func, context);
        let call = move |arg: JSJavaProxy| {
            debug!(
                "Calling Java function: {} on context {} with arg: {:?}",
                func, context, arg
            );

            // Serialize args
            let args = rmp_serde::to_vec(&arg).expect("MsgPack encode failed");
            let args_len = args.len();
            let args_ptr = args.as_ptr();
            std::mem::forget(args); // Prevent drop

            // Call Java function
            let result = unsafe { call_java_function(context, func, args_ptr, args_len) } as u64;

            // Deserialize result, result is a packed pointer and length
            let result_ptr = (result >> 32) as usize;
            let result_len = (result & 0xFFFFFFFF) as usize;
            let result_bytes =
                unsafe { std::slice::from_raw_parts(result_ptr as *const u8, result_len) };
            let result: JSJavaProxy = match rmp_serde::from_slice(result_bytes) {
                Ok(result) => result,
                Err(e) => {
                    error!(
                        "MsgPack decode of return value (type JSJavaProxy) from java context failed: {}",
                        e
                    );
                    JSJavaProxy::Undefined
                }
            };
            crate::dealloc(result_ptr as *mut u8, result_len);

            debug!(
                "Calling Java function: {} on context {} with arg: {:?} -> {:?}",
                func, context, arg, result
            );

            result
        };
        Self {
            call: Box::new(call),
        }
    }
}

impl<'js, P> IntoJsFunc<'js, P> for JavaFunction {
    fn param_requirements() -> rquickjs::function::ParamRequirement {
        // We cannot give any hint on the number of expected parameters
        ParamRequirement::any()
    }

    fn call<'a>(
        &self,
        params: rquickjs::function::Params<'a, 'js>,
    ) -> rquickjs::Result<Value<'js>> {
        let mut args: Vec<JSJavaProxy> = Vec::new();
        for i in 0..params.len() {
            let value = params.arg(i);
            if let Some(v) = value {
                args.push(JSJavaProxy::convert(v)?);
            }
        }

        let arg = JSJavaProxy::Array(args);
        let result = (self.call)(arg);

        // If the result is an exception, throw it
        if let JSJavaProxy::Exception(message, _stacktrace) = &result {
            let exception = rquickjs::Exception::from_message(params.ctx().clone(), &message)?;
            Err(params.ctx().throw(exception.into_value()))
        } else {
            result.into_js(params.ctx())
        }
    }
}
