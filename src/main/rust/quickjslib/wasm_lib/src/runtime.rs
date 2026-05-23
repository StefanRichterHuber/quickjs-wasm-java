use log::debug;
use rquickjs::loader::{Loader, Resolver};
use rquickjs::{Ctx, Module, Runtime};
use rquickjs::module::Declared;
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

#[wasm_export]
pub fn set_memory_limit_runtime(runtime: &Runtime, limit: u64) {
    debug!("Setting QuickJSRuntime memory limit to {} bytes", limit);
    runtime.set_memory_limit(limit as usize);
}

#[repr(C)]
pub struct StringResult {
    pub ptr: u32,
    pub len: u32
}

#[link(wasm_import_module = "env")]
extern "C" {
    pub fn resolve_module(
        base_ptr: u32,
        base_length: u32,
        name_ptr: u32,
        name_length: u32,
        out_result: u32
    );

    pub fn load_module(
        name_ptr: u32,
        name_length: u32,
        out_result: u32
    );
}

pub struct JavaResolver;
impl Resolver for JavaResolver {
    fn resolve<'js>(&mut self, _: &Ctx<'js>, base: &str, name: &str) -> rquickjs::Result<String> {
        let mut string_result = StringResult { ptr: 0, len: 0 };
        unsafe { resolve_module(
            base.as_ptr() as u32,
            base.len() as u32,
            name.as_ptr() as u32,
            name.len() as u32,
            &mut string_result as *mut StringResult as u32
        ) };

        let bytes = unsafe {
            std::slice::from_raw_parts(string_result.ptr as *const u8, string_result.len as usize)
        };

        match str::from_utf8(bytes) {
            Ok(s) => Ok(s.to_owned()),
            Err(e) => Err(rquickjs::Error::from(e))
        }
    }
}

pub struct JavaLoader;
impl Loader for JavaLoader {
    fn load<'js>(&mut self, ctx: &Ctx<'js>, name: &str) -> rquickjs::Result<Module<'js, Declared>> {
        let mut string_result = StringResult { ptr: 0, len: 0 };
        unsafe { load_module(
            name.as_ptr() as u32,
            name.len() as u32,
            &mut string_result as *mut StringResult as u32
        ) };
        let bytes = unsafe {
            std::slice::from_raw_parts(string_result.ptr as *const u8, string_result.len as usize)
        };

        match str::from_utf8(bytes) {
            Ok(s) => Module::declare(ctx.clone(), name, s),
            Err(e) => Err(rquickjs::Error::from(e))
        }
    }
}

#[wasm_export]
pub fn set_loader(runtime: &Runtime) {
    runtime.set_loader(JavaResolver, JavaLoader);
}
