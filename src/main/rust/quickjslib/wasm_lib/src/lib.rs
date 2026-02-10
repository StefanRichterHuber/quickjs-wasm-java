use std::mem;
use std::slice;

use wasm_macros::wasm_export;
mod context;
mod java_log;
mod js_to_java_proxy;
mod quickjs_function;
mod runtime;

/// Give the host a way to free memory to prevent leaks
#[no_mangle]
pub extern "C" fn dealloc(ptr: *mut u8, size: usize) {
    unsafe {
        let _ = Vec::from_raw_parts(ptr, 0, size);
    }
}

/// Give the host a way to allocate memory inside the Wasm module
#[no_mangle]
pub extern "C" fn alloc(size: usize) -> *mut u8 {
    let mut buf = Vec::with_capacity(size);
    let ptr = buf.as_mut_ptr();
    mem::forget(buf); // Prevent Rust from freeing the memory
    ptr
}

#[no_mangle]
pub extern "C" fn greet(ptr: *mut u8, len: usize) {
    // Safety: In a real app, ensure the pointer and len are valid!
    let slice = unsafe { slice::from_raw_parts(ptr, len) };
    let msg = String::from_utf8_lossy(slice);

    println!("Wasm received: {}", msg);
}

#[wasm_export]
pub fn greet2(msg: String) -> String {
    println!("Wasm received by macro: {}", msg);
    format!("Hello {}", msg)
}
