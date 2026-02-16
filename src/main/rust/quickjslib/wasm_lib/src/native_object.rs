use log::debug;
use rquickjs::{object::ObjectKeysIter, Context, Ctx, IntoAtom, Object, Persistent};
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
pub fn object_size(
    ctx: &Ctx<'_>,
    persistent_object: &Persistent<Object<'static>>,
) -> rquickjs::Result<i32> {
    let v = persistent_object.clone().restore(&ctx)?;
    Ok(v.len() as i32)
}

#[wasm_export]
pub fn object_contains_key(
    ctx: &Ctx<'_>,
    persistent_object: &Persistent<Object<'static>>,
    key: JSJavaProxy,
) -> rquickjs::Result<bool> {
    let v = persistent_object.clone().restore(&ctx)?;
    v.contains_key(key)
}

#[wasm_export]
pub fn object_get_value(
    ctx: &Ctx<'_>,
    persistent_object: &Persistent<Object<'static>>,
    key: JSJavaProxy,
) -> rquickjs::Result<JSJavaProxy> {
    let object = persistent_object.clone().restore(ctx)?;
    let key = key.into_atom(ctx)?;
    if object.contains_key(key.clone())? {
        debug!("Key {:?} exists in object", key.to_string()?);
        object.get(key.clone())?
    } else {
        debug!("Key {:?} does not exist in object", key.to_string()?);
        Ok(JSJavaProxy::Null)
    }
}

#[wasm_export]
pub fn object_remove_value(
    ctx: &Ctx<'_>,
    persistent_object: &Persistent<Object<'static>>,
    key: JSJavaProxy,
) -> rquickjs::Result<bool> {
    let v = persistent_object.clone().restore(&ctx)?;
    v.remove(key)?;
    Ok(true)
}

#[wasm_export]
pub fn object_set_value(
    ctx: &Ctx<'_>,
    persistent_object: &Persistent<Object<'static>>,
    key: JSJavaProxy,
    value: JSJavaProxy,
) -> rquickjs::Result<bool> {
    let v = persistent_object.clone().restore(&ctx)?;
    v.set(key, value)?;
    Ok(true)
}

#[wasm_export]
pub fn object_key_set(
    ctx: &Ctx<'_>,
    persistent_object: &Persistent<Object<'static>>,
) -> rquickjs::Result<JSJavaProxy> {
    let v = persistent_object.clone().restore(&ctx)?;

    let object_keys: ObjectKeysIter<'_, JSJavaProxy> = v.keys();

    let mut keys = Vec::new();
    for key in object_keys.into_iter() {
        keys.push(key?);
    }
    debug!("Keys: {:?}", keys);
    Ok(JSJavaProxy::Array(keys))
}
