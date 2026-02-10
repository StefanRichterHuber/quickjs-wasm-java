use log::debug;
use rquickjs::{Context, Function, Persistent};
use wasm_macros::wasm_export;

use crate::{context::handle_error, js_to_java_proxy::JSJavaProxy};

#[wasm_export]
pub fn call_function<'js>(
    context: &Context,
    persistent_function: &Persistent<Function<'static>>,
    args: JSJavaProxy,
) -> JSJavaProxy {
    let result = context.with(|ctx| {
        let function = persistent_function.clone().restore(&ctx).unwrap();

        debug!("Calling function with args: {:?}", args);
        let result = match function.call(args) {
            Ok(value) => value,
            Err(err) => handle_error(err, ctx),
        };
        result
    });
    debug!("Function call result: {:?}", result);

    result
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
