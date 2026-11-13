# Legacy Android client (migration source)

This is the original Android client. Its models, `AppModel`, theme, components
and all pages were moved into [`apps/compose`](../compose) by R1/R2
(2026-11-13); the shared client is now the only maintained implementation.

- The module stays buildable as a reference until R6 deletes it.
- Do not add features here: shared code lives in `apps/compose/shared`.
- Its model/wire unit tests are mirrored in
  `apps/compose/shared/src/commonTest`; the copies here will disappear with the
  module.
- The design-token generator writes only to
  `apps/compose/shared/src/commonMain/.../generated/Tokens.kt`; the copy under
  `app/src/main/java/.../generated/Tokens.kt` is frozen.
- The JNI library is shared with the Compose client and read from
  `apps/compose/android/src/main/jniLibs`.

```bash
./scripts/build-android.sh
JAVA_HOME=<jdk-21> ANDROID_HOME=<sdk> ./apps/android/gradlew -p apps/android assembleDebug
```
