use log::info;
use rquickjs::Runtime;
use wasm_macros::wasm_export;

#[wasm_export]
pub fn create_runtime() -> Box<Runtime> {
    info!("Created new QuickJS runtime");
    Box::new(Runtime::new().unwrap())
}

#[wasm_export]
pub fn close_runtime(runtime: Box<Runtime>) {
    info!("Closing QuickJS runtime");
    drop(runtime);
}
