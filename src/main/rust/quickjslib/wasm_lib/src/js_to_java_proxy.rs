use log::debug;
use log::error;
use rquickjs::function::Args;
use rquickjs::prelude::IntoArgs;
use rquickjs::Array;
use rquickjs::Atom;
use rquickjs::FromAtom;
use rquickjs::FromJs;
use rquickjs::Function;
use rquickjs::IntoAtom;
use rquickjs::IntoJs;
use rquickjs::Object;
use rquickjs::Persistent;
use rquickjs::Value;
use serde::Deserialize;
use serde::Serialize;
use std::collections::HashMap;

use crate::quickjs_function::JavaFunction;

/// This the central conversion point between JS types and java types. It implements FromJs, IntoJs, FromAtom and IntoAtom as well as IntoArgs.
/// This way it can be both argument as well as result of all rquickjs calls. Its also serializable with serde (and message pack) so it can be transfered to / from the java host context.
#[derive(Serialize, Deserialize, Debug, PartialEq)]
#[serde(rename_all = "camelCase")]
pub enum JSJavaProxy {
    Null,
    Undefined,
    String(String),
    Int(i32),
    Float(f64),
    Boolean(bool),
    /// Fields: Vec of JSJavaProxy
    Array(Vec<JSJavaProxy>),
    /// Fields: Array Pointer
    NativeArray(u64),
    Object(HashMap<String, JSJavaProxy>),
    /// Fields: Object Pointer
    NativeObject(u64),
    /// Fields: Function Name, function pointer
    Function(String, u64),
    /// Fields: Context, function_ptr
    JavaFunction(i32, i32),
    /// Fields: Message, Stacktrace
    Exception(String, String),
}

impl<'js> FromJs<'js> for JSJavaProxy {
    fn from_js(_ctx: &rquickjs::Ctx<'js>, value: Value<'js>) -> rquickjs::Result<Self> {
        JSJavaProxy::convert(value)
    }
}

impl<'js> FromAtom<'js> for JSJavaProxy {
    fn from_atom(atom: Atom<'js>) -> rquickjs::Result<Self> {
        JSJavaProxy::convert(atom.to_value()?)
    }
}

impl<'js> IntoAtom<'js> for JSJavaProxy {
    fn into_atom(self, ctx: &rquickjs::Ctx<'js>) -> rquickjs::Result<Atom<'js>> {
        debug!("Calling into_atom(...) on JSJavaProxy");
        match self {
            JSJavaProxy::String(value) => Atom::from_str(ctx.clone(), value.as_str()),
            JSJavaProxy::Int(value) => Atom::from_i32(ctx.clone(), value),
            JSJavaProxy::Float(value) => Atom::from_f64(ctx.clone(), value),
            JSJavaProxy::Boolean(value) => Atom::from_bool(ctx.clone(), value),
            _ => Err(rquickjs::Error::Exception),
        }
    }
}

impl<'js> IntoJs<'js> for JSJavaProxy {
    fn into_js(self, ctx: &rquickjs::Ctx<'js>) -> rquickjs::Result<Value<'js>> {
        debug!("Calling into_js(...) on JSJavaProxy");
        let result = match self {
            JSJavaProxy::Null => Ok(Value::new_null(ctx.clone())),
            JSJavaProxy::Undefined => Ok(Value::new_undefined(ctx.clone())),
            JSJavaProxy::Float(value) => Ok(Value::new_float(ctx.clone(), value)),
            JSJavaProxy::Int(value) => Ok(Value::new_int(ctx.clone(), value)),
            JSJavaProxy::Boolean(value) => Ok(Value::new_bool(ctx.clone(), value)),
            JSJavaProxy::String(value) => Ok(Value::from_string(rquickjs::String::from_str(
                ctx.clone(),
                value.as_str(),
            )?)),
            JSJavaProxy::Array(values) => {
                let array = rquickjs::Array::new(ctx.clone())?;
                for (i, value) in values.into_iter().enumerate() {
                    array.set(i, value)?;
                }
                Ok(array.into_value())
            }
            JSJavaProxy::Object(values) => {
                let obj = rquickjs::Object::new(ctx.clone())?;
                for (key, value) in values.into_iter() {
                    obj.set(key, value)?;
                }
                Ok(obj.into_value())
            }
            JSJavaProxy::Function(_name, ptr) => {
                let function = unsafe { &*(ptr as *mut Persistent<Function>) };
                let restored_function = function.clone().restore(ctx)?;
                Ok(restored_function.into_value())
            }
            JSJavaProxy::JavaFunction(ctx_ptr, function_ptr) => {
                debug!(
                    "Imported Java function: {} at context {}",
                    function_ptr, ctx_ptr
                );
                let f = JavaFunction::new(ctx_ptr, function_ptr);
                let func = Function::new::<JSJavaProxy, JavaFunction>(ctx.clone(), f)?;
                let s = Value::from_function(func);
                Ok(s)
            }
            JSJavaProxy::Exception(msg, _stacktrace) => {
                let exception = rquickjs::Exception::from_message(ctx.clone(), &msg)?;
                Ok(exception.into_value())
            }
            JSJavaProxy::NativeArray(pointer) => {
                let persistent_array = unsafe { &*(pointer as *mut Persistent<Array>) };
                let array = persistent_array.clone().restore(ctx)?;
                Ok(array.into_value())
            }
            JSJavaProxy::NativeObject(pointer) => {
                let persistent_object = unsafe { &*(pointer as *mut Persistent<Object>) };
                let object = persistent_object.clone().restore(ctx)?;
                Ok(object.into_value())
            }
        };
        result
    }
}

impl<'js> IntoArgs<'js> for JSJavaProxy {
    fn num_args(&self) -> usize {
        match self {
            JSJavaProxy::Array(values) => values.len(),
            JSJavaProxy::Undefined => 0,
            _ => 1,
        }
    }

    fn into_args(self, args: &mut Args<'js>) -> rquickjs::Result<()> {
        match self {
            JSJavaProxy::Array(values) => {
                for value in values {
                    args.push_arg(value)?;
                }
                Ok(())
            }
            JSJavaProxy::Undefined => Ok(()),
            _ => {
                args.push_arg(self)?;
                Ok(())
            }
        }
    }
}

impl JSJavaProxy {
    pub fn convert<'js>(value: Value<'js>) -> rquickjs::Result<JSJavaProxy> {
        if value.is_null() {
            return Ok(JSJavaProxy::Null);
        } else if value.is_undefined() {
            return Ok(JSJavaProxy::Undefined);
        } else if value.is_function() {
            let function = value.into_function().unwrap();
            let ctx = function.ctx().clone();

            let name: String = function.get("name")?;

            let persistent_function = Persistent::save(&ctx, function);
            let persistent_function_ptr = Box::into_raw(Box::new(persistent_function)) as u64;

            debug!("Exported function: {} -> {}", name, persistent_function_ptr);
            return Ok(JSJavaProxy::Function(name, persistent_function_ptr));
        } else if value.is_string() {
            let string = value.into_string().unwrap();
            return Ok(JSJavaProxy::String(string.to_string()?));
        } else if value.is_int() {
            let number = value.as_int().unwrap();
            return Ok(JSJavaProxy::Int(number));
        } else if value.is_float() {
            let number = value.as_float().unwrap();
            return Ok(JSJavaProxy::Float(number));
        } else if value.is_bool() {
            let boolean = value.as_bool().unwrap();
            return Ok(JSJavaProxy::Boolean(boolean));
        } else if value.is_exception() {
            debug!("Converting js exception to java exception");
            let exception = value.into_exception().unwrap();
            let message = exception
                .message()
                .unwrap_or("<No exception message>".to_string());
            let stacktrace = exception.stack().unwrap_or("<No stacktrace>".to_string());
            return Ok(JSJavaProxy::Exception(message, stacktrace));
        } else if value.is_array() {
            debug!("Converting js array to java array");
            let array = value.into_array().unwrap();
            let ctx = array.ctx().clone();

            let persistent_array = Persistent::save(&ctx, array);
            let persistent_array_ptr = Box::into_raw(Box::new(persistent_array)) as u64;
            debug!("Created pointer to native array: {}", persistent_array_ptr);
            return Ok(JSJavaProxy::NativeArray(persistent_array_ptr));
        } else if value.is_object() {
            debug!("Converting js object to java map");
            let object = value.into_object().unwrap();
            let ctx = object.ctx().clone();

            let persistent_object = Persistent::save(&ctx, object);
            let persistent_object_ptr = Box::into_raw(Box::new(persistent_object)) as u64;
            debug!(
                "Created pointer to native object: {}",
                persistent_object_ptr
            );
            return Ok(JSJavaProxy::NativeObject(persistent_object_ptr));
        }

        error!(
            "Unknown value type for conversion to JSJavaProxy: {:?}",
            value
        );
        Err(rquickjs::Error::Unknown)
    }
}

#[cfg(test)]
mod tests {
    use rquickjs::{Context, Function, Runtime};

    use super::*;

    #[test]
    fn test_serde_int() {
        let value = JSJavaProxy::Int(7);
        let bytes = rmp_serde::to_vec(&value).unwrap();
        let deserialized: JSJavaProxy = rmp_serde::from_slice(&bytes).unwrap();
        assert_eq!(value, deserialized);
    }

    #[test]
    fn test_serde_float() {
        let value = JSJavaProxy::Float(7.0);
        let bytes = rmp_serde::to_vec(&value).unwrap();
        let deserialized: JSJavaProxy = rmp_serde::from_slice(&bytes).unwrap();
        assert_eq!(value, deserialized);
    }

    #[test]
    fn test_serde_string() {
        let value = JSJavaProxy::String("hello".to_string());
        let bytes = rmp_serde::to_vec(&value).unwrap();
        let deserialized: JSJavaProxy = rmp_serde::from_slice(&bytes).unwrap();
        assert_eq!(value, deserialized);
    }

    #[test]
    fn test_serde_boolean() {
        let value = JSJavaProxy::Boolean(true);
        let bytes = rmp_serde::to_vec(&value).unwrap();
        let deserialized: JSJavaProxy = rmp_serde::from_slice(&bytes).unwrap();
        assert_eq!(value, deserialized);
    }

    #[test]
    fn test_serde_array() {
        let value = JSJavaProxy::Array(vec![
            JSJavaProxy::Int(7),
            JSJavaProxy::String("hello".to_string()),
        ]);
        let bytes = rmp_serde::to_vec(&value).unwrap();
        let deserialized: JSJavaProxy = rmp_serde::from_slice(&bytes).unwrap();
        assert_eq!(value, deserialized);
    }

    #[test]
    fn test_serde_null() {
        let value = JSJavaProxy::Null;
        let bytes = rmp_serde::to_vec(&value).unwrap();
        let deserialized: JSJavaProxy = rmp_serde::from_slice(&bytes).unwrap();
        assert_eq!(value, deserialized);
    }

    #[test]
    fn test_serde_undefined() {
        let value = JSJavaProxy::Undefined;
        let bytes = rmp_serde::to_vec(&value).unwrap();
        let deserialized: JSJavaProxy = rmp_serde::from_slice(&bytes).unwrap();
        assert_eq!(value, deserialized);
    }

    #[test]
    fn test_serde_object() {
        let mut map = HashMap::new();
        map.insert("a".to_string(), JSJavaProxy::Int(7));
        map.insert("b".to_string(), JSJavaProxy::String("hello".to_string()));
        let value = JSJavaProxy::Object(map);
        let bytes = rmp_serde::to_vec(&value).unwrap();
        let deserialized: JSJavaProxy = rmp_serde::from_slice(&bytes).unwrap();
        assert_eq!(value, deserialized);
    }

    #[test]
    fn test_js_java_proxy_int() {
        let rt = Runtime::new().unwrap();
        let context = Context::full(&rt).unwrap();

        let result = context.with(|ctx| {
            let value: JSJavaProxy = ctx.eval("1").unwrap();
            value
        });

        match result {
            JSJavaProxy::Int(i) => assert_eq!(i, 1),
            _ => panic!("Expected an integer"),
        }
    }

    #[test]
    fn test_js_java_proxy_float() {
        let rt = Runtime::new().unwrap();
        let context = Context::full(&rt).unwrap();

        let result = context.with(|ctx| {
            let value: JSJavaProxy = ctx.eval("3.14").unwrap();
            value
        });

        match result {
            JSJavaProxy::Float(f) => assert_eq!(f, 3.14),
            _ => panic!("Expected a float"),
        }
    }

    #[test]
    fn test_js_java_proxy_string() {
        let rt = Runtime::new().unwrap();
        let context = Context::full(&rt).unwrap();

        let result = context.with(|ctx| {
            let value: JSJavaProxy = ctx.eval("'hello'").unwrap();
            value
        });

        match result {
            JSJavaProxy::String(s) => assert_eq!(s, "hello"),
            _ => panic!("Expected a string"),
        }
    }

    #[test]
    fn test_js_java_proxy_boolean() {
        let rt = Runtime::new().unwrap();
        let context = Context::full(&rt).unwrap();

        let result = context.with(|ctx| {
            let value: JSJavaProxy = ctx.eval("true").unwrap();
            value
        });

        match result {
            JSJavaProxy::Boolean(b) => assert_eq!(b, true),
            _ => panic!("Expected a boolean"),
        }
    }

    #[test]
    fn test_js_java_proxy_null() {
        let rt = Runtime::new().unwrap();
        let context = Context::full(&rt).unwrap();

        let result = context.with(|ctx| {
            let value: JSJavaProxy = ctx.eval("let r = null; r").unwrap();
            value
        });

        match result {
            JSJavaProxy::Null => {}
            _ => panic!("Expected null"),
        }
    }

    #[test]
    fn test_js_java_proxy_undefined() {
        let rt = Runtime::new().unwrap();
        let context = Context::full(&rt).unwrap();

        let result = context.with(|ctx| {
            let value: JSJavaProxy = ctx.eval("let r = undefined; r").unwrap();
            value
        });

        match result {
            JSJavaProxy::Undefined => {}
            _ => panic!("Expected undefined"),
        }
    }

    #[test]
    fn test_js_java_proxy_array() {
        let rt = Runtime::new().unwrap();
        let context = Context::full(&rt).unwrap();

        let result = context.with(|ctx| {
            let value: JSJavaProxy = ctx.eval("[1, 2, 3]").unwrap();
            value
        });

        match result {
            JSJavaProxy::Array(v) => assert_eq!(
                v,
                vec![
                    JSJavaProxy::Int(1),
                    JSJavaProxy::Int(2),
                    JSJavaProxy::Int(3)
                ]
            ),
            _ => panic!("Expected an array"),
        }
    }

    #[test]
    fn test_js_java_proxy_object() {
        let rt = Runtime::new().unwrap();
        let context = Context::full(&rt).unwrap();

        let result = context.with(|ctx| match ctx.eval("let a = {a: 1, b: 2}; a") {
            Ok(value) => value,
            Err(e) => panic!("Error evaluating script: {}", e),
        });

        match result {
            JSJavaProxy::Object(m) => {
                assert_eq!(m.get("a"), Some(&JSJavaProxy::Int(1)));
                assert_eq!(m.get("b"), Some(&JSJavaProxy::Int(2)));
            }
            _ => panic!("Expected an object"),
        }
    }

    #[test]
    fn test_js_java_proxy_function() {
        let rt = Runtime::new().unwrap();
        let context = Context::full(&rt).unwrap();

        let result: JSJavaProxy =
            context.with(|ctx| match ctx.eval("function a() { return 1; };a") {
                Ok(value) => value,
                Err(e) => panic!("Error evaluating script: {}", e),
            });

        // Try to restorce peristent function from result
        match result {
            JSJavaProxy::Function(_name, ptr) => {
                let persistent_function =
                    unsafe { Box::from_raw(ptr as *mut Persistent<Function>) };

                let result = context.with(|ctx| {
                    let function = persistent_function.clone().restore(&ctx).unwrap();
                    let result: JSJavaProxy = function.call(()).unwrap();
                    result
                });
                assert_eq!(result, JSJavaProxy::Int(1));
            }
            _ => panic!("Expected a function"),
        }
    }

    #[test]
    fn test_js_java_proxy_function_with_call() {
        let rt = Runtime::new().unwrap();
        let context = Context::full(&rt).unwrap();

        let _result: JSJavaProxy =
            context.with(|ctx| match ctx.eval("function a() { return 1; };a") {
                Ok(value) => value,
                Err(e) => panic!("Error evaluating script: {}", e),
            });

        // Try to restorce peristent function from result
        // match result {
        //     JSJavaProxy::Function(name, ptr) => {
        //         let result = call_function(&context, ptr);
        //         assert_eq!(result, JSJavaProxy::Int(1));
        //     }
        //     _ => panic!("Expected a function"),
        // }
    }

    #[test]
    fn test_js_java_proxy_local_function() {
        let rt = Runtime::new().unwrap();
        let context = Context::full(&rt).unwrap();

        let _result: JSJavaProxy =
            context.with(
                |ctx| match ctx.eval("let r = function() { return 42; }; r") {
                    Ok(value) => value,
                    Err(e) => panic!("Error evaluating script: {}", e),
                },
            );
    }

    // Try to restorce peristent function from result
    // match result {
    //     JSJavaProxy::Function(name, ptr) => {
    //         let persistent_function =
    //             unsafe { Box::from_raw(ptr as *mut Persistent<Function>) };

    //         let result = context.with(|ctx| {
    //             let function = persistent_function.clone().restore(&ctx).unwrap();
    //             let result: JSJavaProxy = function.call(()).unwrap();
    //             result
    //         });
    //         assert_eq!(result, JSJavaProxy::Int(42));
    //     }
    //     _ => panic!("Expected a function"),
    // }
}
