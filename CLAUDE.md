# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

MeshLink is an Android app (Kotlin, XML Views + ViewBinding, single `:app` module, package `com.hieu.p2p`) for offline messaging, data sync, file transfer and voice/video calls over a multi-hop phone mesh. Only the one-hop link uses Google Nearby Connections; routing, crypto, CRDT sync and calls are hand-written. `README.md` and `TAI_LIEU_DU_AN.md` (Vietnamese) hold the full protocol description, verification status and next-steps list — keep `TAI_LIEU_DU_AN.md` updated when protocol or status changes. The user writes docs and UI strings in Vietnamese.

## Commands

Gradle needs a JDK; use Android Studio's JBR:
`export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"`

- Instrumented tests on a real device — **do not use `connectedDebugAndroidTest`** (it uninstalls the app and wipes its data/identity). Instead:
  ```
  ./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
  adb install -r app/build/outputs/apk/debug/app-debug.apk
  adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
  adb shell am instrument -w -e class com.hieu.p2p.<TestClass> com.hieu.p2p.test/androidx.test.runner.AndroidJUnitRunner
  ```
- Logs: `adb logcat -s MeshLink:I` (all `MeshNode` logs go through tag `MeshLink`; during calls a stats line is logged every 5 s).
- Debug-only preview activities for checking UI without peers: see the `meshlink-preview` skill.

Real mesh behaviour needs 2+ physical phones with Play Services; emulators have no real radios.

## Architecture

- **`mesh/` is the pure Kotlin protocol core and must not import anything from `android.*`** — that is what keeps the whole protocol testable on the JVM.
- **`MeshNode`** runs every mutation on a single `Executor` (the `mesh-core` thread in the app, inline in tests), so protocol code is lock-free by design — don't add locks; route new mutations through the executor. Time comes from an injected `clock`.

### Protocol invariants

- Wire format in `mesh/core/Packet.kt`; **bump `VERSION` whenever the packet format or semantics change** — mismatched versions can't talk (UI shows an "outdated neighbour" warning).
- All packets are signed by the originator (ttl/hops excluded from signature) and verified by every relay before forwarding — except `CALL_MEDIA`, which is unsigned but AES-GCM encrypted with the per-call key and only ever sent along a fixed route (never flooded).
- NodeId = SHA-256(signing key) truncated; DM headers are AAD so relays can route but not read or readdress.
- CRDT data (public channel `chat`, `board`) must converge regardless of delivery order/duplication; "I'm safe" status uses key `status-<ID>` so it overwrites.

### Tests

New protocol features should get a `FakeNetwork` simulation test (chains, rings, partitions, packet loss).
