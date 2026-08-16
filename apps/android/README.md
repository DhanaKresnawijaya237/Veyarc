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

## Accessibility gesture spike

The current Phase 0 screen is a manual harness for Android gesture dispatch:

1. Install and open the debug APK.
2. Select **Open accessibility settings**.
3. Enable **Veyarc gesture control**, accept Android's warning, and return to Veyarc.
4. Confirm that the setting is enabled and the service connection is ready.
5. Use **Dispatch tap**, **Dispatch hold**, and **Dispatch rectangle**.
6. Confirm that the test surface reports the corresponding received input and draws the rectangular path.

The spike requests permission to perform gestures but explicitly disables accessibility window-content retrieval. It does not inspect semantic UI content.
