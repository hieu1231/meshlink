---
name: meshlink-verify
description: Verify a MeshLink change on real phones — install the build on every connected device, start the mesh, and read the MeshLink logs to confirm discovery, multi-hop routes, CRDT sync, traceroute and calls actually work. Use after changing mesh/, transport/, data/ or service/ code, before calling protocol work done, or when asked to "test trên máy thật", "kiểm tra mesh", "các máy có thấy nhau không", "verify on devices", "check multi-hop".
---

Goal: evidence from real devices that the change works over the radio, not just in `FakeNetwork`. Report what you observed per device (log lines, counts, hop numbers), and say plainly what could not be verified and why.

## Setup

Read `config.json` next to this file. If `devices` is empty or a listed serial is no longer in `adb devices`, ask the user (AskUserQuestion) which connected phones are test devices and how they are placed (all together, or spread out for multi-hop), then save it. Emulators never count as mesh nodes.

## Tools here

- `scripts/deploy.sh [serial…]` — `install -r` the debug APK on each device (all test devices by default), grant runtime permissions, start `MainActivity`. Build first with `./gradlew :app:assembleDebug` (JAVA_HOME in CLAUDE.md).
- `scripts/logs.sh <seconds> [serial…]` — capture `MeshLink` logs from every device at once into `logs/<timestamp>/<serial>.log` and print a per-device summary of the key events.
- `history.log` — one line per past run (date, commit, devices, outcome). Read the last few lines before starting to know what worked last time, and append one line when done.

## What to check, by change

| Changed | Look at | Reference |
|---|---|---|
| transport / connection handling | advertising + discovery started, direct links formed, no repeated failures | `reference/log-lines.md` |
| routing / packet format | nodes discovered with the expected hop counts; no version-mismatch line; routes expire when a phone leaves | `reference/log-lines.md`, `reference/multihop.md` |
| CRDT / board / chat sync | "Đồng bộ: gửi/nhận" counts on both sides after posting on one phone | `reference/log-lines.md` |
| calls | the 5 s `call N hop(s)` stats line: bitrate, quality, hop count | `reference/calls.md` |
| DMs / attachments | no log line on delivery — check the UI on the receiver (screenshot via `adb exec-out screencap -p`) | — |

## Gotchas

- Never `adb uninstall`, `pm clear` or `connectedAndroidTest`: each wipes the node identity, so peers see a brand-new node and old routes/DMs no longer line up. `meshlink-guard` blocks them.
- Every phone must run the same `Packet.VERSION`. A line "Máy kề bên dùng giao thức v…" means one phone has an old build: redeploy all of them, not just the one you changed.
- Phones on one desk connect directly (P2P_CLUSTER, up to 4 links each), so every node shows "cách 1 bước". That proves nothing about relaying — see `reference/multihop.md` before claiming multi-hop works.
- Launching the app does not start the mesh by itself: `MainActivity` only resumes it if the user had it switched on before (`wantsRunning`), and a fresh install first asks for a display name. If `logs.sh` shows 0 lines for a device, the mesh is off there — screenshot it and have the user (or `adb shell input`) set the name and turn the mesh on. `adb shell dumpsys activity services com.hieu.p2p` lists `MeshService` when it runs.
- `am start -S` (used by the preview activities) kills the service and drops that phone out of the mesh mid-test.
- Discovery on Nearby can take 10–60 s; capture at least 60 s before concluding two phones don't see each other.
