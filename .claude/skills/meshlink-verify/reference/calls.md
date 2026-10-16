# Verifying calls

During a call `CallNotifier` logs every 5 s:

`call <hops> hop(s): <engine stats> · <bitrate> kbps · <quality>`

- `<hops>` should match the route length the radar shows for the peer.
- Bitrate adapts: it should settle, not keep dropping toward the floor. A steady fall means loss on the path — compare with the hop count and the distance between phones.
- A call needs both phones to grant microphone (and camera for video); the service adds the microphone/camera FGS types only while a call runs.
- `CALL_MEDIA` is never flooded: each frame goes along the router's current live route to the peer. If a relay leaves mid-call, the call survives only if the router learns another route before `LOST_TIMEOUT_MS` (12 s) of silence; otherwise it ends with reason `LOST`. Expect the hop count in the stats line to change after a reroute.

To check media without peers, `meshlink-preview` has `CallPreviewActivity --es phase active`, which loops the device's own camera/mic through the real codec path.
