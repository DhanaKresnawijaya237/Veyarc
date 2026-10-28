# Android development

Requires JDK 17 and the Android SDK. Run commands from `apps/android`.
For app setup and usage, open **Settings → Guide** or read the [project README](../../README.md).

## Build and check

```powershell
.\gradlew.bat :app:assembleDebug
```

- For routine changes, run the closest affected tests. Run `verifyLocal` once per release candidate for formatting, Android lint, and JVM tests.
- `spotlessApply`: apply formatting fixes.
- Select relevant instrumentation tests on an approved emulator or device. `verifyDevice` runs the full suite.
- Debug APK: `app/build/outputs/apk/debug/app-debug.apk`.

On macOS/Linux, use `./gradlew` instead of `.\gradlew.bat`.
Recording, replay, and screen-capture checks need manual device testing; see [acceptance](acceptance/README.md).

## Release signing

Keep the keystore outside the repository and back it up. Set these process environment variables without committing or logging their values:

- `VEYARC_RELEASE_STORE_FILE`: absolute keystore path.
- `VEYARC_RELEASE_STORE_PASSWORD`: keystore password.
- `VEYARC_RELEASE_KEY_ALIAS`: key alias.
- `VEYARC_RELEASE_KEY_PASSWORD`: key password.

```powershell
.\gradlew.bat --no-configuration-cache --no-build-cache :app:assembleRelease
apksigner verify --verbose --print-certs app\build\outputs\apk\release\app-release.apk
Get-FileHash app\build\outputs\apk\release\app-release.apk -Algorithm SHA256
```

Use `apksigner` from Android SDK Build Tools. Release APK: `app/build/outputs/apk/release/app-release.apk`.
Updates must use the same signing key. Debug and release builds have different signatures; uninstalling to switch between them deletes local app data.
