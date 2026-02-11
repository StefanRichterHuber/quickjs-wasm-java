use log::{debug, error};
use rquickjs::{prelude::This, Array, Context, Function, Persistent};
use wasm_macros::wasm_export;

use crate::{context::handle_error, js_to_java_proxy::JSJavaProxy};

#[wasm_export]
pub fn array_create(context: &Context) -> Box<Persistent<Array<'static>>> {
    let result = context.with(|ctx| {
        let js_array = rquickjs::Array::new(ctx.clone()).unwrap();
        let persistent = Persistent::save(&ctx, js_array);
        persistent
    });

    let result = Box::new(result);

    result
}

#[wasm_export]
pub fn array_close(_context: &Context, object: Box<Persistent<Array<'static>>>) -> bool {
    drop(object);
    true
}

#[wasm_export]
pub fn array_size(context: &Context, persistent_array: &Persistent<Array<'static>>) -> i32 {
    let result = context.with(|ctx| match persistent_array.clone().restore(&ctx) {
        Ok(v) => v.len() as i32,
        Err(err) => {
            error!("Failed to restore persitent array: {}", err);
            -1
        }
    });
    debug!("Size of the native array {}", result);
    result as i32
}

#[wasm_export]
pub fn array_add(
    context: &Context,
    persistent_array: &Persistent<Array<'static>>,
    index: i32,
    value: JSJavaProxy,
) -> bool {
    let result = context.with(|ctx| {
        let array = persistent_array.clone().restore(&ctx).unwrap();

        match splice_array(array, index, 0, Some(value)) {
            Ok(_) => true,
            Err(err) => {
                error!("Failed to add element at index {} to array: {}", index, err);
                false
            }
        }
    });

    result
}

#[wasm_export]
pub fn array_set(
    context: &Context,
    persistent_array: &Persistent<Array<'static>>,
    index: i32,
    value: JSJavaProxy,
) -> bool {
    let result = context.with(|ctx| {
        let array = persistent_array.clone().restore(&ctx).unwrap();

        match array.set(index as usize, value) {
            Ok(_) => true,
            Err(err) => {
                error!("Failed to set element at index {} in array: {}", index, err);
                false
            }
        }
    });

    result
}

#[wasm_export]
pub fn array_get(
    context: &Context,
    persistent_array: &Persistent<Array<'static>>,
    index: i32,
) -> JSJavaProxy {
    let result = context.with(|ctx| {
        let array = persistent_array.clone().restore(&ctx).unwrap();

        let result: JSJavaProxy = match array.get(index as usize) {
            Ok(v) => v,
            Err(err) => {
                error!("Failed to get element from array: {}", err);
                handle_error(err, ctx)
            }
        };
        result
    });

    result
}

#[wasm_export]
pub fn array_remove(
    context: &Context,
    persistent_array: &Persistent<Array<'static>>,
    index: i32,
) -> bool {
    let result = context.with(|ctx| {
        let array = persistent_array.clone().restore(&ctx).unwrap();

        match splice_array(array, index, 1, None) {
            Ok(_) => true,
            Err(err) => {
                error!(
                    "Failed to remove element at index {} from array: {}",
                    index, err
                );
                false
            }
        }
    });

    result
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
) -> Result<(), rquickjs::Error> {
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
