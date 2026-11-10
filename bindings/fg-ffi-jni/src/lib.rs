//! JNI surface for the Android client.
//!
//! Kotlin cannot call the C ABI without a hand-written bridge, so this crate
//! exposes the same JSON payloads as `fg-ffi` through `java.lang.String`
//! returns. Keeping it separate means desktop clients still link a plain C ABI
//! and the Android artifact is one small `.so` named `libfg_ffi_jni.so`.
//!
//! Every function catches panics before returning to the JVM: unwinding across
//! a JNI frame is undefined behaviour. The Kotlin side owns the returned
//! strings; the JVM copies them when `JNIEnv::new_string` allocates.

use std::{
    panic::{AssertUnwindSafe, catch_unwind},
    ptr,
};

use jni::{
    JNIEnv,
    objects::{JObject, JString},
    sys::{jint, jstring},
};

/// Fallback envelope for a panic inside the Rust core.
const PANIC_PAYLOAD: &str =
    r#"{"ok":false,"error":{"kind":"internal","messageKey":"error.jni.panic"}}"#;

fn read_string(env: &mut JNIEnv<'_>, value: &JString<'_>) -> Option<String> {
    env.get_string(value).ok().map(String::from)
}

fn into_jstring(env: &mut JNIEnv<'_>, value: String) -> jstring {
    match env.new_string(value) {
        Ok(output) => output.into_raw(),
        Err(_) => ptr::null_mut(),
    }
}

fn guarded_string<F>(mut env: JNIEnv<'_>, work: F) -> jstring
where
    F: FnOnce() -> String + std::panic::UnwindSafe,
{
    let value = catch_unwind(work).unwrap_or_else(|_| PANIC_PAYLOAD.to_string());
    into_jstring(&mut env, value)
}

/// `fg_abi_version()` for Kotlin startup checks.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_fograin_launcher_platform_CoreNative_nativeAbiVersion(
    _env: JNIEnv<'_>,
    _this: JObject<'_>,
) -> jint {
    jint::try_from(fg_ffi::ABI_VERSION).unwrap_or(0)
}

/// `fg_set_data_root(path)`; Android passes its app-private external files dir.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_fograin_launcher_platform_CoreNative_nativeSetDataRoot(
    mut env: JNIEnv<'_>,
    _this: JObject<'_>,
    path: JString<'_>,
) -> jstring {
    let path = read_string(&mut env, &path);
    guarded_string(
        env,
        AssertUnwindSafe(|| fg_ffi::set_data_root_json(path.as_deref())),
    )
}

/// `fg_settings_snapshot()`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_fograin_launcher_platform_CoreNative_nativeSettingsSnapshot(
    env: JNIEnv<'_>,
    _this: JObject<'_>,
) -> jstring {
    guarded_string(env, fg_ffi::settings_snapshot_json)
}

/// `fg_settings_set_theme(theme)`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_fograin_launcher_platform_CoreNative_nativeSetTheme(
    mut env: JNIEnv<'_>,
    _this: JObject<'_>,
    theme: JString<'_>,
) -> jstring {
    let theme = read_string(&mut env, &theme);
    guarded_string(
        env,
        AssertUnwindSafe(|| fg_ffi::settings_set_theme_json(theme.as_deref())),
    )
}

/// `fg_service_current()`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_fograin_launcher_platform_CoreNative_nativeServiceCurrent(
    env: JNIEnv<'_>,
    _this: JObject<'_>,
) -> jstring {
    guarded_string(env, fg_ffi::service_current_json)
}

/// `fg_rpc_call(request)`; the request and response are JSON strings.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_fograin_launcher_platform_CoreNative_nativeRpcCall(
    mut env: JNIEnv<'_>,
    _this: JObject<'_>,
    request: JString<'_>,
) -> jstring {
    let request = read_string(&mut env, &request);
    guarded_string(
        env,
        AssertUnwindSafe(|| fg_ffi::rpc_call_json(request.as_deref())),
    )
}

/// `fg_events_next()`; returns a JSON array, `[]` when the queue is empty.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_fograin_launcher_platform_CoreNative_nativeEventsNext(
    env: JNIEnv<'_>,
    _this: JObject<'_>,
) -> jstring {
    guarded_string(env, fg_ffi::events_next_json)
}
