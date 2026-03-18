use std::mem;
mod completable_future;
mod context;
mod from_error;
mod into_wasm_result;
mod java_log;
mod js_to_java_proxy;
mod native_array;
mod native_object;
mod quickjs_function;
mod runtime;

/// Give the wasm host a way to free memory to prevent leaks
/// 
/// # Safety
///
/// * `ptr` must have been allocated by `alloc`.
/// * `size` must match the exact same size used during allocation.
/// * The memory must not be accessed or used after being deallocated.
#[no_mangle]
pub unsafe extern "C" fn dealloc(ptr: *mut u8, size: usize) {
    unsafe {
        let _ = Vec::from_raw_parts(ptr, 0, size);
    }
}

/// Give the wasm host a way to allocate memory inside the Wasm module
/// 
/// # Safety
///
/// * The caller must ensure that the returned pointer is properly deallocated using `dealloc` with the exact same size to prevent memory leaks.
/// * The allocated memory is uninitialized and must be initialized before being read.
#[no_mangle]
pub unsafe extern "C" fn alloc(size: usize) -> *mut u8 {
    let mut buf = Vec::with_capacity(size);
    let ptr = buf.as_mut_ptr();
    mem::forget(buf); // Prevent Rust from freeing the memory
    ptr
}
