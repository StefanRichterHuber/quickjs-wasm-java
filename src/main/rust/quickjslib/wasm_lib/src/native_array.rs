use rquickjs::{prelude::This, Array, Context, Ctx, Function, Persistent};
use wasm_macros::wasm_export;

use crate::js_to_java_proxy::JSJavaProxy;

#[wasm_export]
pub fn array_create(ctx: &Ctx<'_>) -> rquickjs::Result<Option<Box<Persistent<Array<'static>>>>> {
    let js_array = rquickjs::Array::new(ctx.clone()).unwrap();
    let persistent = Persistent::save(&ctx, js_array);
    let result = Box::new(persistent);
    Ok(Some(result))
}

#[wasm_export]
pub fn array_close(_context: &Context, object: Box<Persistent<Array<'static>>>) -> bool {
    drop(object);
    true
}

#[wasm_export]
pub fn array_size(
    ctx: &Ctx<'_>,
    persistent_array: &Persistent<Array<'static>>,
) -> rquickjs::Result<i32> {
    let v = persistent_array.clone().restore(&ctx)?;
    Ok(v.len() as i32)
}

#[wasm_export]
pub fn array_add(
    ctx: &Ctx<'_>,
    persistent_array: &Persistent<Array<'static>>,
    index: i32,
    value: JSJavaProxy,
) -> rquickjs::Result<bool> {
    let array = persistent_array.clone().restore(&ctx)?;
    splice_array(array, index, 0, Some(value))?;
    Ok(true)
}

#[wasm_export]
pub fn array_set(
    ctx: &Ctx<'_>,
    persistent_array: &Persistent<Array<'static>>,
    index: i32,
    value: JSJavaProxy,
) -> rquickjs::Result<bool> {
    let array = persistent_array.clone().restore(&ctx)?;

    array.set(index as usize, value)?;
    Ok(true)
}

#[wasm_export]
pub fn array_get(
    ctx: &Ctx<'_>,
    persistent_array: &Persistent<Array<'static>>,
    index: i32,
) -> rquickjs::Result<JSJavaProxy> {
    let array = persistent_array.clone().restore(&ctx)?;

    array.get(index as usize)?
}

#[wasm_export]
pub fn array_remove(
    ctx: &Ctx<'_>,
    persistent_array: &Persistent<Array<'static>>,
    index: i32,
) -> rquickjs::Result<bool> {
    let array = persistent_array.clone().restore(&ctx)?;

    splice_array(array, index, 1, None)?;

    Ok(true)
}

/// Helper function to splice an array, by calling the splice method on the array.
///
/// # Arguments
///
/// * `array` - The array to splice
/// * `index` - The index to start splicing from
/// * `delete_count` - The number of elements to delete
/// * `value` - The value to insert
///
/// # Returns
///
/// `Ok(())` if the array was spliced successfully, `Err(e)` if the array was not spliced successfully
fn splice_array<'js>(
    array: Array<'js>,
    index: i32,
    delete_count: i32,
    value: Option<JSJavaProxy>,
) -> rquickjs::Result<()> {
    let obj = rquickjs::Value::from(array).into_object().unwrap();
    let splice: Function = obj.get("splice")?;
    match value {
        Some(v) => {
            let _s: rquickjs::Value = splice.call((This(obj), index, delete_count, v))?;
        }
        None => {
            let _s: rquickjs::Value = splice.call((This(obj), index, delete_count))?;
        }
    };
    Ok(())
}
