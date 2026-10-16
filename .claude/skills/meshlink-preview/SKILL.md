---
name: meshlink-preview
description: Show MeshLink screens filled with fake peers so UI can be checked on one device or emulator without a real mesh — radar, find-person sheet, demo chat, call screens (calling/ringing/incoming/active, voice or video). Use after changing layouts, custom views (MeshRadarView, direction arrow, waveform), strings or call UI, or when asked to "xem UI", "preview radar", "xem màn gọi", "chụp màn hình giao diện", "check the UI without peers".
---

Debug builds ship three preview activities (`app/src/debug/java/com/hieu/p2p/ui/`). Install the current debug APK first (`adb install -r`, never uninstall), then open what the change touched and screenshot it (`adb exec-out screencap -p > shot.png`) to look at the result.

| Screen | Command | Extras |
|---|---|---|
| Radar with 6 fake peers | `adb shell am start -S -n com.hieu.p2p/.ui.RadarPreviewActivity` | `--ef heading <deg>` compass heading; `--ez select true` selects a peer |
| Find-person bottom sheet + trace steps | same, with `--ez sheet true` | `--ef angle <deg>` arrow direction (default 35) |
| Chat with sample messages | `adb shell am start -S -n com.hieu.p2p/.ui.DemoChatActivity` | — |
| Call screen | `adb shell am start -S -n com.hieu.p2p/.ui.CallPreviewActivity --es phase <calling\|ringing\|incoming\|active>` | `--ez video true` for a video call |

## Gotchas

- `-S` force-stops the app first, which also stops `MeshService`. On a phone that is part of a live multi-phone test, that drops its links and routes; relaunch `MainActivity` afterwards or use a spare device.
- `CallPreviewActivity` is not isolated: it writes a fake peer name ("Hieu", id `34fdc2f71df8c90c`) into the real `mesh` SharedPreferences and puts the real `MeshNode` into a preview call state. Don't use it on a device whose data matters for the next test.
- `phase active` is not a mock: it loops the device's own camera/mic through the real encode→decode path, so it needs camera and microphone permission and a real camera (emulator cameras give a synthetic picture). Any unknown `phase` value also falls through to `active`.
- The radar sheet extras only apply with `--ez sheet true`; `heading`/`select` only apply without it.
- Preview data is hard-coded Vietnamese sample text; if a string resource changed, check the real screen too, since the previews set some texts directly.
