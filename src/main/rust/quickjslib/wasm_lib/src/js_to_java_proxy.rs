use std::collections::HashMap;
use std::sync::LazyLock;
use std::sync::Mutex;

use rquickjs::function::Args;
use rquickjs::prelude::IntoArgs;
use rquickjs::Atom;
use rquickjs::FromAtom;
use rquickjs::FromJs;
use rquickjs::Function;
use rquickjs::IntoJs;
use rquickjs::Persistent;
use rquickjs::Value;
use serde::Deserialize;
use serde::Serialize;

#[derive(Serialize, Deserialize, Debug, PartialEq)]
#[serde(rename_all = "camelCase")]
pub enum JSJavaProxy {
    /// true if undefined, false if null
    Null,
    Undefined,
    String(String),
    Int(i32),
    Float(f64),
    Boolean(bool),
    Array(Vec<JSJavaProxy>),
    Object(HashMap<String, JSJavaProxy>),
    Function(String, u64),
}

impl<'js> FromJs<'js> for JSJavaProxy {
    fn from_js(_ctx: &rquickjs::Ctx<'js>, value: Value<'js>) -> rquickjs::Result<Self> {
        Ok(JSJavaProxy::convert(value))
    }
}

impl<'js> FromAtom<'js> for JSJavaProxy {
    fn from_atom(atom: Atom<'js>) -> rquickjs::Result<Self> {
        Ok(JSJavaProxy::convert(atom.to_value()?))
    }
}

impl<'js> IntoJs<'js> for JSJavaProxy {
    fn into_js(self, ctx: &rquickjs::Ctx<'js>) -> rquickjs::Result<Value<'js>> {
        let result = match self {
            JSJavaProxy::Null => Ok(Value::new_null(ctx.clone())),
            JSJavaProxy::Undefined => Ok(Value::new_undefined(ctx.clone())),
            JSJavaProxy::Float(value) => Ok(Value::new_float(ctx.clone(), value)),
            JSJavaProxy::Int(value) => Ok(Value::new_int(ctx.clone(), value)),
            JSJavaProxy::Boolean(value) => Ok(Value::new_bool(ctx.clone(), value)),
            JSJavaProxy::String(value) => Ok(Value::from_string(
                rquickjs::String::from_str(ctx.clone(), value.as_str()).unwrap(),
            )),
            JSJavaProxy::Array(values) => {
                let array = rquickjs::Array::new(ctx.clone()).unwrap();
                for (i, value) in values.into_iter().enumerate() {
                    array.set(i, value).unwrap();
                }
                Ok(array.into_value())
            }
            JSJavaProxy::Object(values) => {
                let obj = rquickjs::Object::new(ctx.clone()).unwrap();
                for (key, value) in values.into_iter() {
                    obj.set(key, value).unwrap();
                }
                Ok(obj.into_value())
            }
            // TODO implement function
            JSJavaProxy::Function(name, ptr) => Ok(Value::new_undefined(ctx.clone())),
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
                    args.push_arg(value).unwrap();
                }
                Ok(())
            }
            JSJavaProxy::Undefined => Ok(()),
            _ => {
                args.push_arg(self).unwrap();
                Ok(())
            }
        }
    }
}

impl JSJavaProxy {
    pub fn convert<'js>(value: Value<'js>) -> JSJavaProxy {
        if value.is_null() {
            return JSJavaProxy::Null;
        } else if value.is_undefined() {
            return JSJavaProxy::Undefined;
        } else if value.is_function() {
            let function = value.as_function().unwrap();

            let name: String = function.get("name").unwrap();

            let persistent_function = Persistent::save(function.ctx(), function.clone());
            let persistent_function_ptr = Box::into_raw(Box::new(persistent_function)) as u64;

            println!("Exported function: {} -> {}", name, persistent_function_ptr);
            return JSJavaProxy::Function(name, persistent_function_ptr);
        } else if value.is_string() {
            let string = value.as_string().unwrap();
            return JSJavaProxy::String(string.to_string().unwrap());
        } else if value.is_int() {
            let number = value.as_int().unwrap();
            return JSJavaProxy::Int(number);
        } else if value.is_float() {
            let number = value.as_float().unwrap();
            return JSJavaProxy::Float(number);
        } else if value.is_bool() {
            let boolean = value.as_bool().unwrap();
            return JSJavaProxy::Boolean(boolean);
        } else if value.is_array() {
            // TODO create reference to array instead of copying
            let array = value.as_array().unwrap();
            let mut vec = Vec::new();
            for i in 0..array.len() {
                vec.push(JSJavaProxy::convert(array.get(i).unwrap()));
            }
            return JSJavaProxy::Array(vec);
        } else if value.is_object() {
            // TODO create reference to object instead of copying
            let object = value.as_object().unwrap();
            let mut map = HashMap::new();

            for key in object.keys().into_iter() {
                let key_value: JSJavaProxy = key.unwrap();
                let key_string = match key_value {
                    JSJavaProxy::String(s) => s,
                    _ => panic!("Key is not a string"),
                };

                map.insert(
                    key_string.clone(),
                    JSJavaProxy::convert(object.get(key_string).unwrap()),
                );
            }

            return JSJavaProxy::Object(map);
        }

        JSJavaProxy::Undefined
    }
}

#[cfg(test)]
mod tests {
    use rquickjs::{function, Context, Runtime};

    use crate::quickjs_function::call_function;

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
            JSJavaProxy::Function(name, ptr) => {
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

        let result: JSJavaProxy =
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

        let result: JSJavaProxy =
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
