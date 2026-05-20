# AndroidResAdapter

A tool to adjust Android device resolution and orientation via ADB — useful for adapting to external monitors or projectors.

## Requirements

- Android device with **Developer options** and **USB debugging** enabled
- [ADB](https://developer.android.com/tools/adb) installed on your computer
- For wireless usage: enable **Wireless debugging** in Developer options

## Quick Start

### 1. Connect

**Wireless (with pairing code):**

```bash
adb pair <ip>:<pairing_port> <pairing_code>
adb connect <ip>:<connect_port>
```

**USB:**

```bash
adb devices
```

### 2. Set Resolution & Orientation

```bash
# Disable auto-rotation, lock to landscape
adb shell settings put system accelerometer_rotation 0
adb shell settings put system user_rotation 1

# Override resolution (e.g. 1920×1080)
adb shell wm size 1920x1080

# Optional: adjust density
adb shell wm density 360
```

### 3. Restore Defaults

```bash
adb shell wm size reset
adb shell settings put system accelerometer_rotation 1
adb shell settings put system user_rotation 0
```

## Useful Commands

| Command | Description |
|---------|-------------|
| `adb shell wm size` | View current resolution |
| `adb shell wm density` | View current density |
| `adb shell dumpsys display \| grep -i orientation` | View display orientation |
| `adb shell wm size reset` | Reset to physical resolution |
| `adb shell wm density reset` | Reset to physical density |

## Orientation Values

| `user_rotation` | Orientation |
|----------------|-------------|
| `0` | Portrait |
| `1` | Landscape (90°) |
| `2` | Portrait upside down (180°) |
| `3` | Landscape reverse (270°) |

## Notes

- `wm size` creates an **override** — the physical display remains unchanged, the system scales output to match.
- The override persists until `wm size reset` or device reboot.
- Not all apps handle non-native resolutions gracefully; some may appear cropped or misaligned.
