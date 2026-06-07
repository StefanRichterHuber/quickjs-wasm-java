use log::debug;
use rquickjs::Runtime;
use rquickjs::runtime::UserDataGuard;
use wasm_macros::wasm_export;
use crate::context::ContextPtr;
use crate::js_to_java_proxy::JSJavaProxy;

#[wasm_export]
pub fn create_runtime() -> Box<Runtime> {
    debug!("Created new QuickJS runtime");

    let runtime = Runtime::new().unwrap();
    let interrrupt_handler = move || {
        let result = unsafe { js_interrupt_handler() };
        // False lets continue the flow, true stops the execution
        result == 1
    };

    runtime.set_interrupt_handler(Some(Box::new(interrrupt_handler)));

    Box::new(runtime)
}

#[link(wasm_import_module = "env")]
extern "C" {
    pub fn js_interrupt_handler() -> i32;
}

#[wasm_export]
pub fn close_runtime(runtime: Box<Runtime>) {
    debug!("Closing QuickJS runtime");
    drop(runtime);
}

#[wasm_export]
pub fn set_memory_limit_runtime(runtime: &Runtime, limit: u64) {
    debug!("Setting QuickJSRuntime memory limit to {} bytes", limit);
    runtime.set_memory_limit(limit as usize);
}


#[link(wasm_import_module = "env")]
extern "C" {
    pub fn handle_rejected_promise(
        context_ptr: u64,
        promise_ptr: u64,
        reason_ptr: u32,
        reason_len: u32,
        is_handled: u32
    );
}

#[wasm_export]
pub fn set_promise_rejection_tracker(runtime: &Runtime) {
    runtime.set_host_promise_rejection_tracker(Some(Box::new(|ctx, promise, reason, is_handled| {
        debug!(
                "Calling promise rejection with reason: {:?}",
                reason
            );

        let exception = reason.as_exception().unwrap();
        let message = exception.message().unwrap();
        let stack = exception.stack().unwrap();
        // Serialize
        let serialized = rmp_serde::to_vec(&JSJavaProxy::Exception(message, stack)).expect("MsgPack encode failed");

        let ctx_pointer: UserDataGuard<ContextPtr> = ctx.userdata().unwrap();
        unsafe { handle_rejected_promise(
            ctx_pointer.ptr,
            promise.as_promise().unwrap() as *const _ as u64,
            //&reason_proxy as *const _ as u64,
            serialized.as_ptr() as u32,
            serialized.len() as u32,
            is_handled as u32
        ); }

        std::mem::forget(serialized); // Prevent drop
    })));
}
