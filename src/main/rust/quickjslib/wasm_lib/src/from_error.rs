use log::error;
use rquickjs::Ctx;

use crate::js_to_java_proxy::JSJavaProxy;

pub trait FromError<'js>: Sized {
    fn from_err(ctx: &Ctx<'js>, err: rquickjs::Error) -> Self;
}

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
