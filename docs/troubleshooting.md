# Troubleshooting

## No module log after reboot

Confirm that:

- the installed build is a Modern Xposed module;
- LSPosed marks it enabled;
- static scope is System Framework / `system` only;
- the device was rebooted after installing or upgrading the Hook implementation.

Expected module log entries include:

```text
Loaded in system_server with Modern API 102
All system audio hooks installed; fail-open policy active
```

If installation reports a missing class, method, field, or constructor, the current framework is not
compatible. Disable the module and do not attempt to guess private signatures inside a live
`system_server`.

## Media still drops to call quality

Check that:

- the connected headset exposes classic A2DP;
- the settings switch is enabled;
- the application actually requested SCO or BLE Headset rather than another endpoint;
- physical SCO is not active;
- A2DP is connected, active, and not suspended.

Useful read-only captures:

```powershell
$Serial = '<adb-serial>'
adb -s $Serial shell dumpsys audio > audio.txt
adb -s $Serial shell dumpsys media.audio_policy > audio-policy.txt
adb -s $Serial shell dumpsys media.audio_flinger > audio-flinger.txt
adb -s $Serial shell dumpsys bluetooth_manager > bluetooth.txt
```

Review the files for communication route clients, audio mode, A2DP, SCO, active output patches, and
the selected input device. Remove device names, addresses, package names, account data, and serials
before sharing logs publicly.

## Application says Bluetooth failed while sound uses A2DP

The module sends an SCO-compatible state event but does not falsify public query methods. An
application that polls only `isBluetoothScoOn()` or requires `getCommunicationDevice()` to report a
Bluetooth communication type can reject the route even though AudioFlinger is using A2DP. This is an
application-state compatibility limitation, not necessarily a physical routing failure.

## Volume reaches zero and cannot recover

First disable the module in its settings UI, re-select speaker or another output in the active
session, and adjust media volume directly. If the current route was already committed, leave and
re-enter the session before judging the disabled state.

Do not repeatedly force volume with hidden Binder calls. Capture `dumpsys audio` and inspect the
VOICE_CALL-to-MUSIC alias and both stream indices.

## Speaker or earpiece is unexpectedly redirected

Current releases do not change speaker, earpiece, wired, USB, or other non-headset requests. Capture
the module log and route-client section, then report the exact public device type requested. Do not
include proprietary application packages, full framework dumps, or personal identifiers in an
Issue.

## Passive headset disconnect leaves unusual state

Classic A2DP is not a public communication-device type. Some systems do not immediately run normal
communication-client cleanup when it disappears. Exit the active VoIP session, reconnect or select a
native output, and start a new session. If normal audio remains incorrect, disable the module and
reboot.
