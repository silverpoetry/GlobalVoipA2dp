# Architecture

## Problem model

Android communication audio has three independent policy layers:

1. native AudioPolicy may remove classic A2DP from PHONE-strategy candidates while the native phone
   state is `MODE_IN_COMMUNICATION`;
2. a communication-device request for SCO can start HFP/SCO and suspend A2DP;
3. a `VOICE_COMMUNICATION` track can remain attached to the call-volume control path even when its
   physical sink is A2DP.

Changing only one layer produces incomplete behavior. The module therefore uses three centralized
Hooks, but enables mode and volume changes only while a redirected A2DP route client belongs to the
current communication owner.

## Hook set

### `AudioSystemAdapter#setPhoneState(int, int)`

For an eligible application UID with an active redirected route, native
`MODE_IN_COMMUNICATION (3)` is rewritten to `MODE_NORMAL (0)`. Java-side `AudioService` ownership and
application-visible mode state remain unchanged. `MODE_IN_CALL (2)` is never rewritten.

`AudioService#onUpdateAudioMode(...)` is deoptimized during installation because the small adapter
method can otherwise be inlined into its caller.

### `AudioDeviceBroker#onSetCommunicationDeviceForClient(...)`

This is the shared Broker handler reached by modern communication-device requests and legacy SCO
operations after public argument validation.

Only enabled requests for `TYPE_BLUETOOTH_SCO (7)` and `TYPE_BLE_HEADSET (26)` are eligible:

- the requested device attribute is replaced with the currently routed or first connected classic
  A2DP output;
- the original Binder token, PID, UID, `AttributionSource`, SCO mode, event source, and privileged
  flag are preserved;
- the original Broker method creates and owns the route client, including replacement, clear,
  Binder-death, and native preferred-device behavior;
- SCO is not requested because the committed client device is A2DP.

Speaker, earpiece, wired, USB, and all other target devices are passed to the original framework
without argument changes. If such a request replaces an earlier redirected route, the module only
releases its compatibility state after the framework call succeeds.

Legacy `stopBluetoothSco` can carry a null device on some framework paths. When the stored client is
the redirected A2DP device, the module supplies that same device for the off request so the Broker's
native equality check can remove the client. Modern `clearCommunicationDevice` retains its canonical
null argument.

### `AudioService#updateStreamVolumeAlias(boolean, String)`

The framework rebuilds `sStreamVolumeAlias` when mode or configuration changes. While an eligible
redirected route is active, the post-Hook sets:

```text
STREAM_VOICE_CALL (0) -> STREAM_MUSIC (3)
```

When stream states already exist, the module uses the framework's normal lock order
`mSettingsLock -> mVolumeStateLock`, snapshots VOICE_CALL indices, and invokes framework methods to
rescale and apply alias-dependent values. When the policy exits, it restores the prior alias,
indices, user-mute state, volume-group projection, native volume, and alias notification.

The deprecated `STREAM_BLUETOOTH_SCO (6)` is not recreated on systems that already merge it into the
VOICE_CALL stream.

## Compatibility event

Some applications wait for an SCO-connected event after submitting a route request. Physical SCO is
intentionally not started, so the module posts one package-targeted, non-sticky compatibility event
after confirming that the original Broker committed an A2DP route client. Clear or replacement paths
send the corresponding disconnected state when appropriate.

No public query API is falsified. Applications may still observe that the actual routed device is
A2DP and that physical SCO is inactive.

## Runtime configuration

The companion application and module open the same `routing` Remote Preferences group:

- the UI uses `XposedService#getRemotePreferences(...)`;
- the module uses `XposedModule#getRemotePreferences(...)`;
- both register a preference listener;
- LSPosed owns persistence and cross-process notifications;
- no shell command, root request, or custom Android system property is used.

Disabling the setting immediately bypasses new SCO and BLE Headset requests. Existing routes are not
asynchronously torn down; a subsequent application route operation performs native replacement and
module-state cleanup.

## Safety model

- All private classes, methods, fields, and constructors resolve before any Hook is installed.
- The preference listener and all three Hooks form one installation transaction; failure unhooks
  installed members in reverse order and unregisters the listener.
- Policy-evaluation errors call the untouched original method.
- Exceptions from original framework methods are neither swallowed nor retried.
- Real calls, privileged callers, non-application UIDs, and missing A2DP fail open.
- Modern API protective exception mode is enabled and hot reload is disabled.
- Static scope is exactly `system`; no application process loads the module entry.

## Lifecycle limitation

Classic A2DP is not a public communication-device type. A passive A2DP disconnect may therefore
bypass the normal communication-client cleanup path on some systems. Physical audio falls back when
the sink disappears, but module-owned native mode, volume alias, or compatibility state can persist
until a later mode, route, or alias event. Leaving and re-entering the VoIP session is the safest
recovery action.
