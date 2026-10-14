---
name: install-to-phone
description: 安装到手机、部署 Android 应用、真机运行。Use when the user asks to install, deploy, reinstall, or launch this project on a connected Android phone.
---

# Install To Phone

Build, install, and launch this project's Debug application on a connected Android device. Run all commands from the repository root.

## Project Values

- Gradle task: `:app:installDebug`
- Application ID: `life.xstar.music.player`
- Launcher activity: `.MainActivity`
- Local SDK configuration: `local.properties`

## Workflow

1. Resolve ADB.
   - Try `adb` first only when it is available in `PATH`.
   - Otherwise read `sdk.dir` from `local.properties`, unescape Java-properties path separators, and use `<sdk.dir>\platform-tools\adb.exe`.
   - Do not modify the user's system environment variables.
2. Run `<adb> devices -l`.
   - Continue only with devices whose state is exactly `device`.
   - If no device is ready, report whether it is absent, `unauthorized`, or `offline`; ask the user to reconnect or authorize USB debugging.
   - If exactly one device is ready, use its serial.
   - If multiple devices are ready and the user did not identify one, ask which device to use. Pass `ANDROID_SERIAL=<serial>` to Gradle and `-s <serial>` to ADB.
3. Run `.\gradlew.bat :app:installDebug` with a generous timeout. This updates the app while preserving its data.
4. Launch the app with `<adb> -s <serial> shell am start -W -n life.xstar.music.player/.MainActivity`.
5. Report the device model, build/install result, and launch status. Include actionable error details if any step fails.

## Safety

- Never uninstall the app, clear application data, or revoke permissions unless the user explicitly asks.
- Never use `adb install -r` when the Gradle install task can select and install the correct APK.
- Do not install a Release build unless explicitly requested.
- Do not claim successful deployment unless Gradle reports installation on the intended device and Activity Manager reports `Status: ok`.

## PowerShell Notes

Paths can contain spaces. Invoke a resolved ADB path with the call operator:

```powershell
& "C:\path\to\Android\Sdk\platform-tools\adb.exe" -s SERIAL devices -l
```

For a selected device, scope Gradle installation without changing persistent environment settings:

```powershell
$env:ANDROID_SERIAL = "SERIAL"; .\gradlew.bat :app:installDebug
```
