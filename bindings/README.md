# Native bindings

One Rust core, four native UI clients. This folder owns the boundary between
them.

## Contract

- Rust crates expose domain operations through `fg-ffi`; UI clients never link
  `fg-*` crates directly except on Linux, where GTK and the core are both Rust
  (the Linux client may depend on the crates directly to avoid an FFI hop).
- Swift and Kotlin use UniFFI-generated bindings.
- C# uses a stable C ABI with P/Invoke (generated with `csbindgen` once the
  command surface exists).
- Long-running operations return an opaque task id and push progress through a
  callback/event sink; no UI client blocks on a network or install call.
- Errors cross the boundary as `{ kind, messageKey, detail }`. UI clients map
  `messageKey` to localized copy.

## Current state

`fg-ffi` only exports `fg_abi_version()`. The real command surface is added
after the four shells are approved. Generated bindings will live next to each
client:

```text
apps/macos/Sources/Fograin/Generated/
apps/windows/Fograin.Windows/Generated/
apps/android/app/src/main/java/com/fograin/launcher/generated/
```

Do not hand-edit generated files.
