use std::cell::RefCell;

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
    global.set(name.clone(), value)?;
    Ok(JSJavaProxy::Null)
}

#[wasm_export]
pub fn get_global(ctx: &Ctx<'_>, name: String) -> rquickjs::Result<JSJavaProxy> {
    let global = ctx.globals();
    global.get(name.clone())?
}

thread_local! {
    static CONTEXT_STACK: RefCell<Vec<(u64, Ctx<'static>)>> = RefCell::new(Vec::new());
}

/// Helper function to get a Ctx from a Context, handling re-entrancy.
/// Why is this necessary: calling eval() creates a Ctx object,
/// when script calls a java function and the java function calls some
/// native function ( to create an Object, Array or Promise, for example)
/// another Ctx object is created -> error. Therefore we wrap any context.with
/// with with_context and check if there is some Ctx already open
/// Usually no need to invoke manually, since the wasm_export macro does all the magic around it
pub(crate) fn with_context<F, R>(context: &Context, f: F) -> R
where
    F: FnOnce(Ctx) -> R,
{
    let context_ptr = context as *const _ as u64;

    // Check if context is already in stack (search reverse)
    let existing_ctx = CONTEXT_STACK.with(|stack| {
        stack
            .borrow()
            .iter()
            .rev()
            .find(|(ptr, _)| *ptr == context_ptr)
            .map(|(_, ctx)| ctx.clone())
    });

    if let Some(ctx) = existing_ctx {
        // Transmute 'static Ctx to 'current Ctx
        // This is safe because we know the context is alive (we have &Context) and we are on the same thread.
        // And if we are re-entering, the original scope that created the Ctx is still active on the stack.
        let ctx: Ctx = unsafe { std::mem::transmute(ctx) };
        return f(ctx);
    }

    context.with(|ctx| {
        let static_ctx: Ctx<'static> = unsafe { std::mem::transmute(ctx.clone()) };
        CONTEXT_STACK.with(|stack| stack.borrow_mut().push((context_ptr, static_ctx)));

        // Use a guard to ensure pop happens even if f panics
        struct ContextGuard;
        impl Drop for ContextGuard {
            fn drop(&mut self) {
                CONTEXT_STACK.with(|stack| stack.borrow_mut().pop());
            }
        }
        let _guard = ContextGuard;

        f(ctx)
    })
}
