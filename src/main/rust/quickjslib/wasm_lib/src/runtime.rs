use log::debug;
use rquickjs::Runtime;
use wasm_macros::wasm_export;

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
