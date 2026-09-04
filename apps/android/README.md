# Veyarc Android

Run commands from this directory with JDK 17 and the Android SDK configured.

## Local verification

The local gate does not require an emulator or physical device:

```powershell
.\gradlew.bat verifyLocal
```

On macOS or Linux, run `./gradlew verifyLocal` instead. The gate checks Kotlin and Gradle Kotlin formatting, runs Android lint, and executes local JVM tests.

To apply Kotlin formatting fixes:

```powershell
.\gradlew.bat spotlessApply
```

## Device verification

Start an emulator or connect an authorized Android device, then run:

```powershell
.\gradlew.bat verifyDevice
```

On macOS or Linux, run `./gradlew verifyDevice` instead. This builds and runs the Android instrumentation tests on every Android module.

Check the device API level before using the result as cross-version evidence:

```powershell
adb shell getprop ro.build.version.sdk
```

Run the suite once on API 24 or 25 and once on API 33 or newer. API 24 and 25 cover short-tap playback only; Veyarc capability-gates long presses and swipe paths on those releases because Android cannot safely interrupt a continued gesture there. The API 33+ suite checks recording permissions and foreground-service declarations without starting recording or changing device settings.

Physical recording, replay, and MediaProjection acceptance remains manual because Android requires user-controlled Accessibility, Shizuku, and screen-capture consent. Use the fixture and cleanup procedure in [`acceptance/README.md`](acceptance/README.md). The instrumentation suite never enables Accessibility or injects input by itself.

## Signed sideload release

Release APKs fail closed unless all signing values are supplied through the process environment. Keep the keystore outside this repository and back it up securely: sideloaded updates must use the same signing key as the installed app.

Set these environment variables without committing or printing their values:

- `VEYARC_RELEASE_STORE_FILE`: absolute path to an external JKS, PKCS12, or compatible keystore
- `VEYARC_RELEASE_STORE_PASSWORD`: keystore password
- `VEYARC_RELEASE_KEY_ALIAS`: signing-key alias
- `VEYARC_RELEASE_KEY_PASSWORD`: signing-key password

Build with Gradle caches disabled so passwords are not retained in project caches:

```powershell
.\gradlew.bat --no-configuration-cache --no-build-cache :app:assembleRelease
```

On macOS or Linux, use `./gradlew` with the same flags. The signed APK is written to `app/build/outputs/apk/release/app-release.apk`. Verify it before distribution using the `apksigner` from the installed Android SDK Build Tools:

```powershell
apksigner verify --verbose --print-certs app\build\outputs\apk\release\app-release.apk
Get-FileHash app\build\outputs\apk\release\app-release.apk -Algorithm SHA256
```

The release build rejects missing values, relative or missing keystores, keystores inside the repository, and invocations that leave the Gradle configuration or build cache enabled. Debug and ordinary local verification do not require release credentials.

## Accessibility gesture spike

The current Phase 0 screen is a manual harness for Android gesture dispatch:

1. Install and open the debug APK.
2. Select **Open accessibility settings**.
3. Enable **Veyarc gesture control**, accept Android's warning, and return to Veyarc.
4. Confirm that the setting is enabled and the service connection is ready.
5. Use **Dispatch tap**, **Dispatch hold**, and **Dispatch rectangle**.
6. Confirm that the test surface reports the corresponding received input and draws the rectangular path.

The spike requests permission to perform gestures but explicitly disables accessibility window-content retrieval. It does not inspect semantic UI content.
