# StackLauncherPoc - standalone Android 9 APK


## Android 9 stack-launch note

Pie does **not** expose the old hidden `ActivityOptions.setLaunchStackId(int)` API that existed on Android 8.x.
This POC therefore uses the Pie-native sequence:

```text
startActivity(target)
  -> IActivityManager.getTasks() -> taskId
  -> createStackOnDisplay(Display.DEFAULT_DISPLAY)
  -> moveTaskToStack(taskId, stackId, true)
  -> moveStackToDisplay(stackId, virtualDisplayId)
```

This keeps the requested `moveStackToDisplay()` architecture while remaining compatible with API 28.

A minimal launcher-like POC that embeds another installed app inside a `SurfaceView` by using:

```text
SurfaceView
  -> Surface
  -> VirtualDisplay
  <- moveStackToDisplay(dedicatedStackId, virtualDisplayId)
  <- third-party Activity task
```

It is intentionally a normal standalone Gradle project. It does **not** need to live in the AOSP tree and does **not** need a full `framework.jar` at compile time.

## Target

Test target requested for this project:

```text
system-images;android-28;default;x86
Android 9 / API 28
```

The source compiles against the stock `platforms;android-28/android.jar`. Hidden Pie APIs are isolated in `HiddenApiBridge` and called reflectively at runtime.

## Required platform permissions

The manifest requests:

```text
android.permission.MANAGE_ACTIVITY_STACKS
android.permission.INTERNAL_SYSTEM_WINDOW
android.permission.INJECT_EVENTS
- `android.permission.REAL_GET_TASKS` (used to resolve the launched app task before reparenting)
```

Therefore the APK must be signed with the same **platform certificate** as the running system image. No `android.uid.system` shared UID is used.

## Prerequisites

- Linux/macOS shell
- JDK 17+
- Android SDK with:
  - `platforms;android-28`
  - `build-tools;30.0.3`
  - `platform-tools`
- `curl` or `wget`
- `unzip`
- `openssl`

Example SDK installation:

```bash
sdkmanager \
  "platforms;android-28" \
  "build-tools;30.0.3" \
  "platform-tools" \
  "system-images;android-28;default;x86"
```

Create an emulator, for example:

```bash
echo no | avdmanager create avd \
  -n stack_pie_x86 \
  -k "system-images;android-28;default;x86"

emulator -avd stack_pie_x86 \
  -no-snapshot-load \
  -gpu swiftshader_indirect
```

`-writable-system` is not required for this APK; it is installed under `/data/app`.

## One-command build

```bash
export ANDROID_SDK_ROOT="$HOME/Android/Sdk"
./scripts/build-platform-apk.sh
```

Output:

```text
dist/StackLauncherPoc-platform.apk
```

If platform keys are absent, the script fetches the public AOSP Android 9 test platform key. When an emulator is connected it first compares that certificate with the certificate of the emulator's `framework-res.apk`. A mismatch is treated as an error rather than silently producing an APK without the required permissions.

For another ROM/platform key:

```bash
PLATFORM_KEY_DIR=/path/to/platform-keys \
  ./scripts/build-platform-apk.sh
```

The directory must contain:

```text
platform.pk8
platform.x509.pem
```

## Install and run

```bash
./scripts/install-and-run.sh
```

Or manually:

```bash
adb install -r dist/StackLauncherPoc-platform.apk
adb shell am start -n com.example.stacklauncherpoc/.MainActivity
```

Press `+`, select an installed launcher app, and the app is started in a dedicated stack and then moved onto the virtual display backed by the panel's `SurfaceView`.

## Core sequence

`MainActivity.hostSelectedApp()` performs:

```text
createStackOnDisplay(DEFAULT_DISPLAY)
        |
        v
ActivityOptions.setLaunchStackId(stackId)
        |
        v
startActivity(thirdPartyLauncherActivity)
        |
        v
moveStackToDisplay(stackId, virtualDisplayId)
```

These are the actual Android 9 ActivityManager operations; only the Java linkage is reflective so the standalone project can compile with the public SDK.

## Input

Touch events received by the host `SurfaceView` are copied, scaled to virtual-display coordinates, assigned the virtual `displayId`, and injected through `InputManager.injectInputEvent()`.

The BACK button uses the same path with `KEYCODE_BACK`.

## Lifecycle / leak prevention

- one `SurfaceHolder.Callback` per host
- one `VirtualDisplay` per panel
- `surfaceDestroyed()` detaches the Surface instead of recreating the display
- old dedicated stack is removed before switching apps
- `MotionEvent.obtain()` is always paired with `recycle()`
- dialog reference is cleared on dismiss
- `onDestroy()` order is:

```text
removeStack
-> remove input listener
-> remove SurfaceHolder callback
-> VirtualDisplay.setSurface(null)
-> VirtualDisplay.release()
```

## Useful diagnostics

```bash
./scripts/emulator-smoke-check.sh
adb logcat -s StackLauncherPoc VirtualDisplayHost PlatformStackCtl ActivityManager WindowManager InputManager
adb shell dumpsys activity activities
adb shell dumpsys display
```

If hidden API bootstrap fails, first verify platform signing:

```bash
./scripts/verify-emulator-platform-cert.sh
```

If ActivityManager reports a `SecurityException`, inspect the installed package permissions and confirm the signing certificate matches the ROM.
