//! Direct Rex transport and native Ghostty replica used by Rexling.
pub mod control;
pub mod data;
pub mod ghostty;
pub mod snapshot;
pub mod transport;

pub mod upload;

#[cfg(target_os = "android")]
pub fn android_log(msg: &str) {
    let msg_c = std::ffi::CString::new(msg).unwrap_or_default();
    let tag_c = std::ffi::CString::new("RexEngine").unwrap_or_default();
    unsafe extern "C" {
        fn __android_log_print(prio: i32, tag: *const std::ffi::c_char, fmt: *const std::ffi::c_char, ...) -> i32;
    }
    unsafe {
        __android_log_print(6, tag_c.as_ptr(), c"%s".as_ptr(), msg_c.as_ptr());
    }
}
#[cfg(not(target_os = "android"))]
pub fn android_log(msg: &str) {
    eprintln!("[RexEngine] {msg}");
}


