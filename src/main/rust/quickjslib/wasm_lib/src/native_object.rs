use log::{debug, error, info, warn};
use rquickjs::{object::ObjectKeysIter, Atom, Context, Ctx, IntoAtom, Object, Persistent};
use wasm_macros::wasm_export;

use crate::{context::handle_error, js_to_java_proxy::JSJavaProxy};

#[wasm_export]
pub fn object_create(context: &Context) -> Box<Persistent<Object<'static>>> {
    let result = context.with(|ctx| {
        let js_object = rquickjs::Object::new(ctx.clone()).unwrap();
        let persistent = Persistent::save(&ctx, js_object);
        persistent
    });

    let result = Box::new(result);

    result
}

#[wasm_export]
pub fn object_close(_context: &Context, object: Box<Persistent<Object<'static>>>) -> bool {
    drop(object);
    true
}

#[wasm_export]
pub fn object_size(context: &Context, persistent_object: &Persistent<Object<'static>>) -> i32 {
    let result = context.with(|ctx| match persistent_object.clone().restore(&ctx) {
        Ok(v) => v.len() as i32,
        Err(err) => {
            error!("Failed to restore persistent object: {}", err);
            -1
        }
    });
    debug!("Size of the native object {}", result);
    result as i32
}

#[wasm_export]
pub fn object_contains_key(
    context: &Context,
    persistent_object: &Persistent<Object<'static>>,
    key: JSJavaProxy,
) -> bool {
    let result = context.with(|ctx| match persistent_object.clone().restore(&ctx) {
        Ok(v) => match v.contains_key(key) {
            Ok(v) => v,
            Err(err) => {
                error!("Failed to check if key exists in object: {}", err);
                false
            }
        },
        Err(err) => {
            error!("Failed to restore persistent object: {}", err);
            false
        }
    });
    if result {
        debug!("Key exists in the native object");
    } else {
        debug!("Key does not exist in the native object");
    }
    result
}

#[wasm_export]
pub fn object_get_value(
    context: &Context,
    persistent_object: &Persistent<Object<'static>>,
    key: JSJavaProxy,
) -> JSJavaProxy {
    let result = context.with(|ctx| match get_value(&ctx, persistent_object, key) {
        Ok(v) => v,
        Err(err) => {
            error!("Failed to get value from object: {}", err);
            handle_error(err, ctx)
        }
    });
    result
}

fn get_value<'js>(
    ctx: &Ctx<'js>,
    persistent_object: &Persistent<Object<'static>>,
    key: JSJavaProxy,
) -> rquickjs::Result<JSJavaProxy> {
    let object = persistent_object.clone().restore(ctx)?;
    let key: Atom<'js> = key.into_atom(ctx)?;
    if object.contains_key(key.clone())? {
        info!("Key {:?} exists in object", key.to_string()?);
        object.get(key.clone())?
    } else {
        info!("Key {:?} does not exist in object", key.to_string()?);
        Ok(JSJavaProxy::Null)
    }
}

#[wasm_export]
pub fn object_remove_value(
    context: &Context,
    persistent_object: &Persistent<Object<'static>>,
    key: JSJavaProxy,
) -> bool {
    let result = context.with(|ctx| match persistent_object.clone().restore(&ctx) {
        Ok(v) => match v.remove(key) {
            Ok(_) => true,
            Err(err) => {
                error!("Failed to remove value from object: {}", err);
                false
            }
        },
        Err(err) => {
            error!("Failed to restore persistent object: {}", err);
            false
        }
    });
    if result {
        debug!("Value removed from the native object");
    } else {
        debug!("Value not removed from the native object");
    }
    result
}

#[wasm_export]
pub fn object_set_value(
    context: &Context,
    persistent_object: &Persistent<Object<'static>>,
    key: JSJavaProxy,
    value: JSJavaProxy,
) -> bool {
    let result = context.with(|ctx| match persistent_object.clone().restore(&ctx) {
        Ok(v) => match v.set(key, value) {
            Ok(_) => true,
            Err(err) => {
                error!("Failed to set value in object: {}", err);
                false
            }
        },
        Err(err) => {
            error!("Failed to restore persistent object: {}", err);
            false
        }
    });
    if result {
        info!("Value set in the native object");
    } else {
        warn!("Value not set in the native object");
    }
    result
}

#[wasm_export]
pub fn object_key_set(
    context: &Context,
    persistent_object: &Persistent<Object<'static>>,
) -> JSJavaProxy {
    let result = context.with(|ctx| match persistent_object.clone().restore(&ctx) {
        Ok(v) => {
            let object_keys: ObjectKeysIter<'_, JSJavaProxy> = v.keys();

            let mut keys = Vec::new();
            for key in object_keys.into_iter() {
                match key {
                    Ok(key) => {
                        keys.push(key);
                    }
                    Err(err) => {
                        error!("Failed to get key from object: {}", err);
                        keys.push(handle_error(err, ctx.clone()));
                    }
                }
            }
            info!("Keys: {:?}", keys);
            JSJavaProxy::Array(keys)
        }
        Err(err) => {
            error!("Failed to restore persistent object: {}", err);
            handle_error(err, ctx)
        }
    });

    result
}
