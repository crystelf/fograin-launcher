# Legacy Android client (migration source)

This is the original Android client. Its UI is being moved into
[`apps/compose`](../compose) (R1/R2); once that migration is complete this
module is deleted.

- Keep the app buildable while migrating: it is on the same AGP 9 / Kotlin 2.4 /
  compileSdk 37 toolchain as `apps/compose`.
- The JNI library is shared with the Compose client and read from
  `apps/compose/android/src/main/jniLibs`.
- The design-token generator now writes to
  `apps/compose/shared/src/commonMain/.../generated/Tokens.kt`; the copy under
  `app/src/main/java/.../generated/Tokens.kt` is frozen.

```bash
./scripts/build-android.sh
JAVA_HOME=<jdk-21> ANDROID_HOME=<sdk> ./apps/android/gradlew -p apps/android assembleDebug
```
