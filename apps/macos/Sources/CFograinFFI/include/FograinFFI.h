// C contract between the Fograin Rust core and the macOS client.
// The implementation lives in `libfg_ffi.a`; see bindings/fg-ffi.
#ifndef FOGRAIN_FFI_H
#define FOGRAIN_FFI_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/// Version of the C ABI. The client checks this at startup and refuses to run
/// against an unexpected core build.
uint32_t fg_abi_version(void);

/// Returns a JSON settings snapshot:
///
///   { "abiVersion": 1, "appVersion": "0.1.0", "platform": "macos",
///     "theme": "system|light|dark", "dataRoot": "...", "configPath": "...",
///     "configError": { "kind": "...", "messageKey": "...", "detail": "..." }? }
///
/// The caller owns the string and must release it with fg_string_free().
/// Returns NULL if the core cannot allocate or serialize the snapshot.
char *fg_settings_snapshot(void);

/// Writes `appearance.theme` in launcher.toml and returns a JSON result:
///
///   { "ok": true,  "theme": "light", "snapshot": { ... } }
///   { "ok": false, "error": { "kind": "...", "messageKey": "...", "detail": "..." } }
///
/// `theme` must be "system", "light" or "dark"; NULL is reported as invalid
/// input. The caller owns the returned string and must release it with
/// fg_string_free().
char *fg_settings_set_theme(const char *theme);

/// Releases a string returned by this library. NULL is a no-op.
void fg_string_free(char *value);

#ifdef __cplusplus
}
#endif

#endif // FOGRAIN_FFI_H
