use std::collections::HashMap;

use log::debug;
use rquickjs::{
    function::{IntoJsFunc, ParamRequirement},
    prelude::This,
    promise::PromiseState,
    runtime::UserDataGuard,
    Context, Function, IntoJs, Persistent, Promise, Value,
};
use wasm_macros::wasm_export;

use crate::{context::ContextPtr, js_to_java_proxy::JSJavaProxy};

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

        // TODO how to handle This reference here?
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
    fn convert_value<'js>(val: Value<'js>) -> rquickjs::Result<JSJavaProxy> {
        if val.is_object() {
            let obj = val.as_object().unwrap();
            if let Ok(v) = obj.get::<_, Value>("value") {
                if !v.is_undefined() {
                    return JSJavaProxy::convert(v);
                }
            }
        }
        JSJavaProxy::convert(val)
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
        debug!("Calling JavaPromise.call() with reject {}", self.reject);
        let arg = params.arg(0).unwrap();

        let val = if arg.is_promise() {
            let promise = arg.clone().into_promise().unwrap();
            match promise.state() {
                PromiseState::Resolved => {
                    debug!(
                        "Calling JavaPromise.call() on promise value  with reject {} -> Promise resolved",
                        self.reject
                    );
                    Self::convert_value(promise.finish::<Value>().unwrap())?
                }
                PromiseState::Rejected => {
                    debug!(
                        "Calling JavaPromise.call() on promise value  with reject {} -> Promise rejected",
                        self.reject
                    );
                    match promise.finish::<Value>() {
                        Ok(v) => JSJavaProxy::convert(v)?,
                        Err(e) => JSJavaProxy::Exception(format!("{:?}", e), "".to_string()),
                    }
                }
                PromiseState::Pending => JSJavaProxy::Null,
            }
        } else {
            debug!(
                "Calling JavaPromise.call() on value with reject {} -> Promise resolved",
                self.reject
            );
            Self::convert_value(arg)?
        };

        // Serialize args
        let args = rmp_serde::to_vec(&val).expect("MsgPack encode failed");
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
        debug!("Calling JavaPromise.call() finished");

        Ok(Value::new_bool(params.ctx().clone(), true))
    }
}

pub(crate) fn convert_promise<'js>(promise: Promise<'js>) -> rquickjs::Result<JSJavaProxy> {
    let then_func = promise.then()?;
    let catch_func = promise.catch()?;
    let ctx = promise.ctx().clone();

    // Restore the context pointer, hopefully found in the ctx
    let ctx_pointer: UserDataGuard<ContextPtr> = ctx.userdata().unwrap();

    debug!("Found context pointer {}", ctx_pointer.ptr);

    // Call then func with callback to completablefutures
    // TODO: create promise on java side, get pointer to it
    // First check if this promise already references a completable future
    let completable_future_ptr = if let Ok(ptr) = promise.get("__completable_future_ptr") {
        debug!(
            "Retrieved CompletableFuture pointer {} from field {}",
            ptr, "__completable_future_ptr"
        );
        ptr
    } else {
        // Create new completablefuture and save pointer to it in the promise
        let completable_future_ptr = unsafe { create_completable_future(ctx_pointer.ptr) };
        debug!(
            "Generated new CompletableFuture pointer {}",
            completable_future_ptr
        );

        promise.set("__completable_future_ptr", completable_future_ptr)?;
        completable_future_ptr
    };

    let persistent_promise = Persistent::save(&ctx, promise.clone());
    let persistent_promise_ptr = Box::into_raw(Box::new(persistent_promise.clone())) as u64;

    let completable_future_complete = JavaPromise::new(
        ctx_pointer.ptr,
        completable_future_ptr.try_into().unwrap(),
        persistent_promise_ptr,
        false,
    );
    let completable_future_reject = JavaPromise::new(
        ctx_pointer.ptr,
        completable_future_ptr.try_into().unwrap(),
        persistent_promise_ptr,
        true,
    );

    let completable_future_complete =
        Function::new::<JSJavaProxy, JavaPromise>(ctx.clone(), completable_future_complete)?;

    let completable_future_reject =
        Function::new::<JSJavaProxy, JavaPromise>(ctx.clone(), completable_future_reject)?;

    let completable_future_complete = Value::from_function(completable_future_complete);
    let completable_future_reject = Value::from_function(completable_future_reject);

    // Use the same callback for onFulfilled and onRejected
    debug!("Calling .then() and .catch() on the promise");
    then_func.call::<_, ()>((
        This(promise.as_value()),
        completable_future_complete.clone().into_js(&ctx)?, // onFulfilled
        completable_future_reject.clone().into_js(&ctx)?,   // onRejected
    ))?;

    catch_func.call::<_, ()>((
        This(promise.as_value()),
        completable_future_reject.into_js(&ctx)?,
    ))?;
    debug!("Called .then() and .catch() on the promise");

    return Ok(JSJavaProxy::CompletableFuture(
        completable_future_ptr.try_into().unwrap(),
        persistent_promise_ptr,
    ));
}

#[cfg(test)]
mod tests {
    use rquickjs::{Context, Function, Runtime};

    use super::*;

    #[test]
    fn test_async() {
        let rt = Runtime::new().unwrap();
        let context = Context::full(&rt).unwrap();

        context.with(|ctx| {
            let promise = ctx.eval_promise("source");
        });
    }
}
