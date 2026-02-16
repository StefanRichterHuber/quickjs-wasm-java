use log::debug;
use rquickjs::{Context, Ctx, Function, Persistent};
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
