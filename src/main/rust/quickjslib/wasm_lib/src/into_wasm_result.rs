use crate::js_to_java_proxy::JSJavaProxy;

/// Trait for converting a value into a u64 that can be returned to Java
pub trait IntoWasmResult {
    fn into_wasm(self) -> u64;
}

/// Converts a JSJavaProxy into a u64 that can be returned to Java (by serializing it to a byte array with MsgPack)
impl IntoWasmResult for JSJavaProxy {
    fn into_wasm(self) -> u64 {
        let bytes = rmp_serde::to_vec(&self).expect("MsgPack encode failed");
        bytes.into_wasm()
    }
}

/// Converts a generic byte array into a u64 that can be returned to java (by returning the pointer and the length of array packed into a single u64)
impl IntoWasmResult for Vec<u8> {
    fn into_wasm(self) -> u64 {
        let ptr = self.as_ptr();
        let len = self.len();
        std::mem::forget(self); // Prevent drop
        (ptr, len).into_wasm()
    }
}

/// Converts a Box<T> into a u64 that can be returned to Java (by returning the pointer to the object)
impl<T> IntoWasmResult for Box<T> {
    #[inline]
    fn into_wasm(self) -> u64 {
        // return the pointer to the object
        let ptr = Box::into_raw(self);
        ptr as u64
    }
}

/// Converts an Option<IntoWasmResult> into a u64 that can be returned to Java (by returning the pointer to the object).
/// For None 0 is returned
impl<T> IntoWasmResult for Option<T>
where
    T: IntoWasmResult,
{
    #[inline]
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
        let ptr = bytes.as_ptr();
        let len = bytes.len();
        std::mem::forget(self); // Prevent drop
        (ptr, len).into_wasm()
    }
}

/**
 * Converts a pointer and a length to single u64
 */
impl IntoWasmResult for (*const u8, usize) {
    #[inline]
    fn into_wasm(self) -> u64 {
        ((self.0 as u64) << 32) | (self.1 as u64)
    }
}

/// Converts a bool into a u64 that can be returned to Java (by returning 1 for true and 0 for false)
impl IntoWasmResult for bool {
    #[inline]
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
    #[inline]
    fn into_wasm(self) -> u64 {
        self as u64
    }
}

/// Converts a u32 into a u64 that can be returned to Java (by returning the value as is)
impl IntoWasmResult for u32 {
    #[inline]
    fn into_wasm(self) -> u64 {
        self as u64
    }
}

/// Converts an i64 into a u64 that can be returned to Java (by returning the value as is)
impl IntoWasmResult for i64 {
    #[inline]
    fn into_wasm(self) -> u64 {
        self as u64
    }
}

/// Converts a u64 into a u64 that can be returned to Java (by returning the value as is)
impl IntoWasmResult for u64 {
    #[inline]
    fn into_wasm(self) -> u64 {
        self
    }
}

/// Converts a f32 into a u64 (by bit representation not by value)
impl IntoWasmResult for f32 {
    #[inline]
    fn into_wasm(self) -> u64 {
        self.to_bits().into_wasm()
    }
}

/// Converts a f64 into a u64 (by bit representation not by value)
impl IntoWasmResult for f64 {
    #[inline]
    fn into_wasm(self) -> u64 {
        self.to_bits().into_wasm()
    }
}
