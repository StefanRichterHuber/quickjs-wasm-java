use std::collections::HashMap;

use log::info;
use rquickjs::{
    function::{IntoJsFunc, ParamRequirement},
    promise::PromiseState,
    Context, Ctx, Object, Persistent, Promise, Value,
};
use wasm_macros::wasm_export;

use crate::js_to_java_proxy::JSJavaProxy;

#[link(wasm_import_module = "env")]
extern "C" {
    pub fn create_completable_future(context_ptr: u64) -> i64;
}

#[link(wasm_import_module = "env")]
extern "C" {
    pub fn complete_completable_future(
        context_ptr: u64,
        reject: i32,
        future_index: i32,
        args_ptr: *const u8,
        args_len: usize,
    ) -> i64;
}

#[wasm_export]
pub fn promise_create(context: &Context) -> JSJavaProxy {
    let map = context.with(|ctx| {
        let (promise, resolve, reject) = ctx.promise().unwrap();
        let persistent = Persistent::save(&ctx, promise);

        let persistent = Box::new(persistent);
        let promise_ptr = Box::into_raw(persistent);
        let mut map = HashMap::new();
        map.insert(
            "promise_ptr".to_string(),
            JSJavaProxy::convert(Value::new_int(ctx, promise_ptr as i32)).unwrap(),
        );
        map.insert(
            "resolve".to_string(),
            JSJavaProxy::convert(resolve.into_value()).unwrap(),
        );
        map.insert(
            "reject".to_string(),
            JSJavaProxy::convert(reject.into_value()).unwrap(),
        );
        map
    });

    JSJavaProxy::Object(map)
}

pub struct JavaPromise {
    context_ptr: u64,
    completable_future_ptr: i32,
    persistent_promise_ptr: u64,
    reject: bool,
}

impl JavaPromise {
    pub fn new(
        context_ptr: u64,
        completable_future_ptr: i32,
        persistent_promise_ptr: u64,
        reject: bool,
    ) -> Self {
        Self {
            context_ptr,
            completable_future_ptr,
            persistent_promise_ptr,
            reject,
        }
    }
}

impl JavaPromise {
    pub fn call_for_promise<'js>(
        &self,
        arg: rquickjs::Promise<'js>,
    ) -> rquickjs::Result<Value<'js>> {
        info!("Calling JavaPromise.call_for_promise()");
        let ctx = arg.ctx().clone();
        // Object with one property: value
        let value = arg.as_object().unwrap().get("value").unwrap();

        let arg = JSJavaProxy::convert(value)?;

        // Serialize args
        let args = rmp_serde::to_vec(&arg).expect("MsgPack encode failed");
        let args_len = args.len();
        let args_ptr = args.as_ptr();
        std::mem::forget(args); // Prevent drop

        // Call Java function
        let _result = unsafe {
            complete_completable_future(
                self.context_ptr,
                if self.reject { 1 } else { 0 },
                self.completable_future_ptr,
                args_ptr,
                args_len,
            )
        };

        Ok(Value::new_bool(ctx, true))
    }
}

impl<'js, P> IntoJsFunc<'js, P> for JavaPromise {
    fn param_requirements() -> ParamRequirement {
        ParamRequirement::single()
    }

    fn call<'a>(
        &self,
        params: rquickjs::function::Params<'a, 'js>,
    ) -> rquickjs::Result<Value<'js>> {
        info!("Calling JavaPromise.call() with reject {}", self.reject);
        let arg = params.arg(0).unwrap();
        let mut reject = self.reject;

        if arg.is_promise() {
            info!("Call parameter is a promise");
            let promise = arg.clone().into_promise().unwrap();
        } else {
            info!("Call parameter is not a promise");
        }

        // Object with one property: value
        let value: Value = arg.as_object().unwrap().get("value").unwrap();

        let arg = if value.is_promise() {
            info!("Value of call parameter is a promise");
            let promise = value.clone().into_promise().unwrap();

            match promise.state() {
                PromiseState::Pending => {
                    info!("Called value Promise still pending!");
                    let value = promise.finish()?;
                    JSJavaProxy::convert(value)?
                }
                PromiseState::Resolved => {
                    // Promise already resolved -> complete completable future
                    info!("Called value Promise already resolved");
                    let value = promise.finish()?;
                    info!("Resolved called value Promise finished");
                    JSJavaProxy::convert(value)?
                }
                PromiseState::Rejected => {
                    info!("Called value Promise already rejected");
                    reject = true;
                    JSJavaProxy::Exception(
                        "Promise rejected".to_string(),
                        "Promise rejected".to_string(),
                    )
                }
            }
        } else {
            info!("Value of call parameter is not a promise");
            JSJavaProxy::convert(value)?
        };

        // Serialize args
        let args = rmp_serde::to_vec(&arg).expect("MsgPack encode failed");
        let args_len = args.len();
        let args_ptr = args.as_ptr();
        std::mem::forget(args); // Prevent drop

        // Call Java function
        let _result = unsafe {
            complete_completable_future(
                self.context_ptr,
                if reject { 1 } else { 0 },
                self.completable_future_ptr,
                args_ptr,
                args_len,
            )
        };
        info!("Calling JavaPromise.call() finished");

        Ok(Value::new_bool(params.ctx().clone(), true))
    }
}
