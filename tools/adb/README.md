# ADB Flow Runner

Small PowerShell runner for standard ADB automation. It uses public ADB input commands only and does not use root-only event injection.

## Setup

1. Enable USB debugging on the device.
2. Connect the device and authorize the computer.
3. Confirm the device is visible:

```powershell
powershell -ExecutionPolicy Bypass -File .\tools\adb\run-adb-flow.ps1 -ListDevices
```

## Run

Copy `steps.example.json`, edit the package name and coordinates, then run:

```powershell
powershell -ExecutionPolicy Bypass -File .\tools\adb\run-adb-flow.ps1 -Steps .\tools\adb\steps.example.json
```

Use a specific device when multiple devices are connected:

```powershell
powershell -ExecutionPolicy Bypass -File .\tools\adb\run-adb-flow.ps1 -Serial DEVICE_ID -Steps .\tools\adb\steps.example.json
```

Preview commands without touching the device:

```powershell
powershell -ExecutionPolicy Bypass -File .\tools\adb\run-adb-flow.ps1 -DryRun -Steps .\tools\adb\steps.example.json
```

## Step Actions

- `launch`: starts an app by package, or by package plus activity.
- `tap`: taps screen coordinates.
- `longTap`: presses one point for `durationMs`.
- `swipe`: swipes between two points.
- `text`: types simple text.
- `key`: sends an Android key event such as `BACK`, `HOME`, or `ENTER`.
- `back`: shortcut for Android Back.
- `home`: shortcut for Android Home.
- `wait`: waits for `ms`.
- `screenshot`: saves a device screenshot to a local path.
- `note`: prints a message.

If an app intentionally ignores ADB-injected input, this runner will not bypass that restriction.
