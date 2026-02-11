use log::{Level, LevelFilter};
use wasm_macros::wasm_export;

struct JavaLog {
    level: Level,
}
/// Implementation of the Log trait for JavaLog. Delegate log messages to Java.
///
/// # Arguments
///
/// * `level` - The log level to set
///
/// # Levels
///
/// * `1` - Error
/// * `2` - Warn
/// * `3` - Info
/// * `4` - Debug
/// * `5` - Trace
impl log::Log for JavaLog {
    fn enabled(&self, metadata: &log::Metadata) -> bool {
        metadata.level() <= self.level
    }

    fn log(&self, record: &log::Record) {
        if self.enabled(record.metadata()) {
            let level_int = record.level() as i32;
            let message = format!("{} {}", record.metadata().target(), record.args());

            let bytes = message.as_bytes();
            let len = bytes.len();
            let ptr = bytes.as_ptr();
            std::mem::forget(message); // Prevent drop

            unsafe {
                log_java(level_int, ptr, len);
            }
        }
    }

    fn flush(&self) {
        // Do nothing
    }
}

#[link(wasm_import_module = "env")]
extern "C" {
    pub fn log_java(level: i32, message: *const u8, message_len: usize);
}

/// Initialize the logger with the given log level.
///
/// # Arguments
///
/// * `lvl` - The log level to set
///
/// # Levels
///
/// * `1` - Error
/// * `2` - Warn
/// * `3` - Info
/// * `4` - Debug
/// * `5` - Trace
#[wasm_export]
pub fn init_logger(lvl: i32) {
    let level = match lvl {
        1 => Level::Error,
        2 => Level::Warn,
        3 => Level::Info,
        4 => Level::Debug,
        5 => Level::Trace,
        _ => Level::Error,
    };
    let filter = match lvl {
        1 => LevelFilter::Error,
        2 => LevelFilter::Warn,
        3 => LevelFilter::Info,
        4 => LevelFilter::Debug,
        5 => LevelFilter::Trace,
        _ => LevelFilter::Error,
    };
    let logger = JavaLog { level };
    log::set_boxed_logger(Box::new(logger)).unwrap();
    log::set_max_level(filter);
}
