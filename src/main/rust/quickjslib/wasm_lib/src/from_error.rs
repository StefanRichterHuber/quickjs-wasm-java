use log::error;
use rquickjs::Ctx;

use crate::js_to_java_proxy::JSJavaProxy;

/// Trait for converting a rquickjs::Error into a value that can be returned to Java
///
pub trait FromError<'js>: Sized {
    fn from_err(ctx: &Ctx<'js>, err: rquickjs::Error) -> Self;
}

/// Converts a rquickjs::Error into a JSJavaProxy that can be returned to Java
///
impl<'js> FromError<'js> for JSJavaProxy {
    fn from_err(ctx: &Ctx<'js>, err: rquickjs::Error) -> Self {
        match err {
            rquickjs::Error::Exception => {
                let catch = ctx.catch();
                if let Some(exception) = catch.as_exception() {
                    let message = exception.message().unwrap();
                    let stacktrace = exception.stack().unwrap();
                    JSJavaProxy::Exception(message, stacktrace)
                } else {
                    JSJavaProxy::Exception(err.to_string(), String::new())
                }
            }
            _ => JSJavaProxy::Exception(err.to_string(), String::new()),
        }
    }
}

impl<'js, T> FromError<'js> for Option<Box<T>> {
    fn from_err(ctx: &Ctx<'js>, err: rquickjs::Error) -> Self {
        match err {
            rquickjs::Error::Exception => {
                let catch = ctx.catch();
                if let Some(exception) = catch.as_exception() {
                    let message = exception.message().unwrap();
                    let stacktrace = exception.stack().unwrap();
                    error!("Failed to call js {}: {}", message, stacktrace);
                    None
                } else {
                    error!("Failed to call js {}", err.to_string());
                    None
                }
            }
            _ => {
                error!("Failed to call js {}", err.to_string());
                None
            }
        }
    }
}

/// Converts a rquickjs::Error into a bool that can be returned to Java
///
impl<'js> FromError<'js> for bool {
    fn from_err(ctx: &Ctx<'js>, err: rquickjs::Error) -> Self {
        match err {
            rquickjs::Error::Exception => {
                let catch = ctx.catch();
                if let Some(exception) = catch.as_exception() {
                    let message = exception.message().unwrap();
                    let stacktrace = exception.stack().unwrap();
                    error!("Failed to call js {}: {}", message, stacktrace);
                    false
                } else {
                    error!("Failed to call js {}", err.to_string());
                    false
                }
            }
            _ => {
                error!("Failed to call js {}", err.to_string());
                false
            }
        }
    }
}

/// Converts a rquickjs::Error into a i32 that can be returned to Java
///
impl<'js> FromError<'js> for i32 {
    fn from_err(ctx: &Ctx<'js>, err: rquickjs::Error) -> Self {
        match err {
            rquickjs::Error::Exception => {
                let catch = ctx.catch();
                if let Some(exception) = catch.as_exception() {
                    let message = exception.message().unwrap();
                    let stacktrace = exception.stack().unwrap();
                    error!("Failed to call js {}: {}", message, stacktrace);
                    -1
                } else {
                    error!("Failed to call js {}", err.to_string());
                    -1
                }
            }
            _ => {
                error!("Failed to call js {}", err.to_string());
                -1
            }
        }
    }
}
