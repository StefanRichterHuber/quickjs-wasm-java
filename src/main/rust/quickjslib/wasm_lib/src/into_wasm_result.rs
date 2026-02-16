use crate::js_to_java_proxy::JSJavaProxy;

pub trait IntoWasmResult {
    fn into_wasm(self) -> u64;
}

impl IntoWasmResult for JSJavaProxy {
    fn into_wasm(self) -> u64 {
        let bytes = rmp_serde::to_vec(&self).expect("MsgPack encode failed");
        let len = bytes.len();
        let ptr = bytes.as_ptr();
        std::mem::forget(bytes); // Prevent drop
        ((ptr as u64) << 32) | (len as u64)
    }
}

impl<T> IntoWasmResult for Box<T> {
    fn into_wasm(self) -> u64 {
        // return the pointer to the object
        let ptr = Box::into_raw(self);
        ptr as u64
    }
}

impl IntoWasmResult for String {
    fn into_wasm(self) -> u64 {
        let bytes = self.as_bytes();
        let len = bytes.len();
        let ptr = bytes.as_ptr();
        std::mem::forget(self); // Prevent drop
        ((ptr as u64) << 32) | (len as u64)
    }
}

impl IntoWasmResult for bool {
    fn into_wasm(self) -> u64 {
        if self {
            1
        } else {
            0
        }
    }
}

impl IntoWasmResult for i32 {
    fn into_wasm(self) -> u64 {
        self as u64
    }
}

impl IntoWasmResult for u32 {
    fn into_wasm(self) -> u64 {
        self as u64
    }
}

impl IntoWasmResult for i64 {
    fn into_wasm(self) -> u64 {
        self as u64
    }
}

impl IntoWasmResult for u64 {
    fn into_wasm(self) -> u64 {
        self as u64
    }
}
