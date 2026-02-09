use std::collections::HashMap;

use rquickjs::Atom;
use rquickjs::FromAtom;
use rquickjs::FromJs;
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

impl JSJavaProxy {
    pub fn convert<'js>(value: Value<'js>) -> JSJavaProxy {
        if value.is_null() {
            return JSJavaProxy::Null;
        } else if value.is_undefined() {
            return JSJavaProxy::Undefined;
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
    use rquickjs::{Context, Runtime};

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
}
