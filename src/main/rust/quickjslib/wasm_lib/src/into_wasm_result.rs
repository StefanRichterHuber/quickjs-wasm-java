use crate::js_to_java_proxy::JSJavaProxy;

/// Trait for converting a value into a u64 that can be returned to Java
pub trait IntoWasmResult {
    fn into_wasm(self) -> u64;
}

/// Converts a JSJavaProxy into a u64 that can be returned to Java (by serializing it to a byte array with MsgPack)
impl IntoWasmResult for JSJavaProxy {
    fn into_wasm(self) -> u64 {
        let bytes = rmp_serde::to_vec(&self).expect("MsgPack encode failed");
        let len = bytes.len();
        let ptr = bytes.as_ptr();
        std::mem::forget(bytes); // Prevent drop
        ((ptr as u64) << 32) | (len as u64)
    }
}

/// Converts a Box<T> into a u64 that can be returned to Java (by returning the pointer to the object)
impl<T> IntoWasmResult for Box<T> {
    fn into_wasm(self) -> u64 {
        // return the pointer to the object
        let ptr = Box::into_raw(self);
        ptr as u64
    }
}

/// Converts a Option<Box<T>> into a u64 that can be returned to Java (by returning the pointer to the object).
///  For None 0 is returned
impl<T> IntoWasmResult for Option<Box<T>> {
    fn into_wasm(self) -> u64 {
        match self {
            Some(v) => v.into_wasm(),
            None => 0,
        }
    }
}

/// Converts a String into a u64 that can be returned to Java (by returning the pointer to the string)
impl IntoWasmResult for String {
    fn into_wasm(self) -> u64 {
        let bytes = self.as_bytes();
        let len = bytes.len();
        let ptr = bytes.as_ptr();
        std::mem::forget(self); // Prevent drop
        ((ptr as u64) << 32) | (len as u64)
    }
}

/// Converts a bool into a u64 that can be returned to Java (by returning 1 for true and 0 for false)
impl IntoWasmResult for bool {
    fn into_wasm(self) -> u64 {
        if self {
            1
        } else {
            0
        }
    }
}

/// Converts an i32 into a u64 that can be returned to Java (by returning the value as is)
impl IntoWasmResult for i32 {
    fn into_wasm(self) -> u64 {
        self as u64
    }
}

/// Converts a u32 into a u64 that can be returned to Java (by returning the value as is)
impl IntoWasmResult for u32 {
    fn into_wasm(self) -> u64 {
        self as u64
    }
}

/// Converts an i64 into a u64 that can be returned to Java (by returning the value as is)
///
impl IntoWasmResult for i64 {
    fn into_wasm(self) -> u64 {
        self as u64
    }
}

/// Converts a u64 into a u64 that can be returned to Java (by returning the value as is)
impl IntoWasmResult for u64 {
    fn into_wasm(self) -> u64 {
        self as u64
    }
}
