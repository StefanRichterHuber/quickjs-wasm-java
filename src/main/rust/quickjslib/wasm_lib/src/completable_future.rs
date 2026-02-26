use log::debug;
use rquickjs::{
    function::{IntoJsFunc, ParamRequirement},
    prelude::This,
    promise::PromiseState,
    runtime::UserDataGuard,
    Context, Ctx, Function, IntoJs, Persistent, Promise, Value,
};
use wasm_macros::wasm_export;

use crate::{context::ContextPtr, js_to_java_proxy::JSJavaProxy};

/**
 * In this private field in any promise, we store a reference to the corresponding java completable future
 */
pub static JAVA_COMPLETABLE_FUTURE_PTR_FIELD: &str = "__completable_future_ptr";
pub static JS_PROMISE_CONTAINER_PTR_FIELD: &str = "___js_promise_container_ptr";

#[link(wasm_import_module = "env")]
extern "C" {
    pub fn create_completable_future(context_ptr: u64, promise_ptr: u64) -> i64;
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

pub struct PromiseContainer {
    pub promise: Persistent<Promise<'static>>,
    pub resolve: Option<Persistent<Function<'static>>>,
    pub reject: Option<Persistent<Function<'static>>>,
}

impl PromiseContainer {
    pub(crate) fn new<'js>(
        ctx: &Ctx<'js>,
        promise: Promise<'js>,
        resolve: Option<Function<'js>>,
        reject: Option<Function<'js>>,
    ) -> Self {
        let promise = Persistent::save(ctx, promise);
        let resolve = resolve.map(|f| Persistent::save(ctx, f));
        let reject = reject.map(|f| Persistent::save(ctx, f));

        PromiseContainer {
            promise,
            resolve,
            reject,
        }
    }

    pub(crate) fn resolve<'js>(&self, ctx: &Ctx<'js>, value: JSJavaProxy) -> rquickjs::Result<()> {
        let promise = Persistent::restore(self.promise.clone(), ctx)?;
        let resolve = Persistent::restore(self.resolve.clone().unwrap(), ctx)?;
        debug!("Calling resolve with value from java completable future");
        let _result: JSJavaProxy = resolve.call((This(promise), value))?;
        debug!("Successfully called resolve with value from java completable future");

        Ok(())
    }

    pub(crate) fn reject<'js>(&self, ctx: &Ctx<'js>, value: JSJavaProxy) -> rquickjs::Result<()> {
        let promise = Persistent::restore(self.promise.clone(), ctx)?;
        let reject = Persistent::restore(self.reject.clone().unwrap(), ctx)?;
        debug!("Calling reject with value from java completable future");
        let _result: JSJavaProxy = reject.call((This(promise), value))?;
        debug!("Successfully called reject with value from java completable future");

        Ok(())
    }
}

#[wasm_export]
pub fn promise_resolve(
    ctx: &Ctx<'_>,
    cf: &PromiseContainer,
    value: JSJavaProxy,
) -> rquickjs::Result<bool> {
    cf.resolve(ctx, value)?;
    Ok(true)
}

#[wasm_export]
pub fn promise_reject(
    ctx: &Ctx<'_>,
    cf: &PromiseContainer,
    value: JSJavaProxy,
) -> rquickjs::Result<bool> {
    cf.reject(ctx, value)?;
    Ok(true)
}

#[wasm_export]
pub fn promise_create(context: &Context, cf_ptr: i32) -> Box<PromiseContainer> {
    let result = context.with(|ctx| {
        let (promise, resolve, reject) = ctx.promise().unwrap();

        promise
            .set(JAVA_COMPLETABLE_FUTURE_PTR_FIELD, cf_ptr)
            .unwrap();
        let cf = Box::new(PromiseContainer::new(
            &ctx,
            promise,
            Some(resolve),
            Some(reject),
        ));
        cf
    });

    result
}

pub struct JavaPromise {
    context_ptr: u64,
    completable_future_ptr: i32,
    reject: bool,
}

impl JavaPromise {
    pub fn new(context_ptr: u64, completable_future_ptr: i32, reject: bool) -> Self {
        Self {
            context_ptr,
            completable_future_ptr,
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
    debug!("Trying to restore context pointer from context");

    let ctx_pointer: UserDataGuard<ContextPtr> = ctx.userdata().unwrap();

    debug!("Found context pointer {}", ctx_pointer.ptr);

    let promise_ptr: u64 = if let Ok(ptr) = promise.get(JS_PROMISE_CONTAINER_PTR_FIELD) {
        debug!(
            "Retrieved pointer to JS Promise container {} from field {}",
            ptr, JS_PROMISE_CONTAINER_PTR_FIELD
        );
        ptr
    } else {
        let promise_container = PromiseContainer::new(&ctx, promise.clone(), None, None);
        let promise_ptr = Box::into_raw(Box::new(promise_container)) as u64;

        promise.set(JS_PROMISE_CONTAINER_PTR_FIELD, promise_ptr)?;
        debug!(
            "Generated new to JS Promise container {} and saved to field {}",
            promise_ptr, JS_PROMISE_CONTAINER_PTR_FIELD
        );

        promise_ptr
    };

    // Call then func with callback to completablefutures
    // TODO: create promise on java side, get pointer to it
    // First check if this promise already references a completable future
    let completable_future_ptr: i32 =
        if let Ok(ptr) = promise.get(JAVA_COMPLETABLE_FUTURE_PTR_FIELD) {
            debug!(
                "Retrieved CompletableFuture pointer {} from field {}",
                ptr, JAVA_COMPLETABLE_FUTURE_PTR_FIELD
            );
            ptr
        } else {
            // Create new completablefuture and save pointer to it in the promise
            let completable_future_ptr =
                unsafe { create_completable_future(ctx_pointer.ptr, promise_ptr) };
            debug!(
                "Generated new CompletableFuture pointer {}",
                completable_future_ptr
            );

            promise.set(JAVA_COMPLETABLE_FUTURE_PTR_FIELD, completable_future_ptr)?;
            completable_future_ptr as i32
        };

    let completable_future_complete = JavaPromise::new(
        ctx_pointer.ptr,
        completable_future_ptr.try_into().unwrap(),
        false,
    );
    let completable_future_reject = JavaPromise::new(
        ctx_pointer.ptr,
        completable_future_ptr.try_into().unwrap(),
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
        promise_ptr,
    ));
}
