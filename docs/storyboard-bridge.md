# Storyboard bridge (Android-only fork feature)

The storyboard-cards field app (M2, package `tw.nowind.sbcards`) logs takes against
storyboard cards. It needs to know when the camera starts and stops recording and what
it was set to. This bridge carries that over Android broadcasts. iOS has no equivalent
(see the PARITY row **Storyboard bridge (fork)**).

Code: `Apps/Android/app/src/main/kotlin/com/opencapture/openpocketcine/bridge/`.
`StoryboardBridgeLogic` is pure and unit-tested; `StoryboardBridge` does the I/O.

## Floating record key

The floating macro shows a 52 dp record key under the main button (above it when there
is no room). Gray: camera not LIVE (tap does nothing). Dark `● 錄`: idle, tap starts.
Red `■ 停`: recording, tap stops. It calls the same `pressShutter` as the rec lamp; the
camera's reported state, not the tap, decides what M2 is told. Long-press shows the
installed build (`versionName`; CI debug builds end in `.r<Actions run number>`).

## Wire contract v1

Payloads are a JSON object string in the `payload` extra. Fields may be added without a
version bump; receivers ignore unknown fields. A breaking change bumps `v`.

### OpenPocketCine → M2

Action `tw.nowind.sbcards.action.CAMERA_EVENT`, explicit package `tw.nowind.sbcards`.
On Android 14+ the broadcast shares the sender's identity, so M2 can check
`getSentFromPackage()` is `com.opencapture.openpocketcine` (or its `.debug` / `.perf`
build variants, which carry an `applicationIdSuffix`). The command action string below
is the same for every variant, so M2 sends it without `setPackage` and every installed
variant receives it.

Every message has:

| Field | Meaning |
| --- | --- |
| `v` | Wire version, `1` |
| `type` | `link`, `rec_start`, `rec_stop`, `rec_lost`, `cmd_result` |
| `seq` | Increments per message from this process |
| `phone_ms` | Wall clock, ms since epoch |
| `mono_ms` | `SystemClock.elapsedRealtime()` |
| `camera` | Snapshot: `live`, `recording`, `shooting_mode`, `resolution_code`, `fps_index`, `color_mode`, `zoom`, `expo_mode`, `iso_limit`, `iso_index`, `ev_comp` (raw camera codes; `-1` / `""` / `null` = unknown) |

Type-specific fields:

- `link`: `connected` (camera LIVE or not).
- `rec_start`: `joined_late` is true when the first state seen was already recording
  (app or bridge started mid-take); M2 must not treat that as a precise start.
- `rec_stop`: `duration_ms` measured on the monotonic clock between the reported start
  and stop (null when the start was not seen).
- `rec_lost`: the link dropped while recording; the stop time is unknown.
- `cmd_result`: reply to a command — `cmd_id`, `cmd`, `ok`, `reason`.

Times are the phone's view of the camera's reported state, not the camera clock. The
4P file remains the time base; M2 aligns by content.

### M2 → OpenPocketCine

Action `com.opencapture.openpocketcine.action.STORYBOARD_COMMAND`, payload:

```json
{"v": 1, "id": "PHONE1-1763100000000-1", "cmd": "rec_start"}
```

`cmd` is `ping`, `rec_start` or `rec_stop`. The bridge only accepts commands whose
`getSentFromPackage()` is `tw.nowind.sbcards`; M2 must send with
`BroadcastOptions.setShareIdentityEnabled(true)`. Below Android 14 commands are ignored
(events are still sent). No shared key is needed.

| Reason | `ok` | Pressed |
| --- | --- | --- |
| `sent` | true | yes |
| `already_recording` / `already_stopped` | true | no |
| `pong` | true | no |
| `not_live` | false | no |
| `version` / `unknown_cmd` | false | no |

`sent` means the shutter was pressed, not that the camera started; the following
`rec_start` / `rec_stop` event is the confirmation.

## Live picture lending

M2 can show the live picture in its own window while this app is behind it. M2 binds the
exported `com.opencapture.openpocketcine.bridge.StoryboardSurfaceService` (explicit component,
any of the three package variants) and hands over its own `Surface`. The live decoder switches
its output there with `setOutputSurface` (`PocketCameraSession.attachSurface`): no re-encode, no
keyframe request, one decode. This app's own live view always wins while it has a surface
(`SurfaceArbiter`), so the picture moves back when the operator returns here.

Binder interface `com.opencapture.openpocketcine.storyboard.Surface`, raw transactions (no AIDL):

| Code | Request (after the interface token) | Reply |
| --- | --- | --- |
| `FIRST_CALL_TRANSACTION` attach | client token binder, `int 1`, `Surface` parcel | no-exception, `int result`, `int wire version` |
| `+1` detach | — | same |
| `+2` status | — | same |

Results: `0` ok (live), `1` no session (activity not running), `2` session not live, `3` bad
surface. Every call checks `getPackagesForUid(getCallingUid())` contains `tw.nowind.sbcards`;
others are refused. M2 must call detach synchronously from `surfaceDestroyed` (a dead output
wedges MediaCodec). A death link on the client token, unbind and service destroy also return
the picture.

The camera network stays usable in the background only while the floating macro's
`connectedDevice` foreground service runs; without it Android blocks the sockets and the
borrowed picture freezes.

## Verification

Unit: `StoryboardBridgeLogicTest`. Physical (pending): floating key start/stop on a
Pocket 4 Pro, M2 receives each event, M2 command starts and stops, a non-M2 sender is
ignored, link loss mid-take sends `rec_lost`. Picture lending: M2 shows the picture with this app
behind it, switching back and forth keeps a picture on both sides, M2 killed mid-view returns
the picture (unit: `SurfaceArbiterTest`).
