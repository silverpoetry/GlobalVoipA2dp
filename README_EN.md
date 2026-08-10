# Global VoIP A2DP Router

[简体中文](README.md) · [Installation](docs/installation.md) · [Architecture](docs/architecture.md) · [Troubleshooting](docs/troubleshooting.md)

[![CI](https://github.com/silverpoetry/GlobalVoipA2dp/actions/workflows/ci.yml/badge.svg)](https://github.com/silverpoetry/GlobalVoipA2dp/actions/workflows/ci.yml)
[![Release](https://img.shields.io/github/v/release/silverpoetry/GlobalVoipA2dp?display_name=tag)](https://github.com/silverpoetry/GlobalVoipA2dp/releases)
[![License: Apache-2.0](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)

Global VoIP A2DP Router is a Modern LSPosed module scoped exclusively to Android
`system_server`. When a classic A2DP headset is already connected, it can redirect Bluetooth SCO
or BLE Headset communication-output requests from ordinary third-party applications to A2DP, so
other media does not fall back to the low-bandwidth call transport.

The module does not load into VoIP application processes, does not maintain an application package
allowlist, and does not modify application code. Its Material 3 settings UI uses LSPosed Remote
Preferences without requesting root, running shell commands, or exposing a custom system property.

> [!WARNING]
> This module depends on root, LSPosed, and private Android audio-framework interfaces while running
> inside `system_server`. An incompatible framework may cause silence, incorrect volume behavior, or
> a system-process restart. Keep a tested recovery path before enabling it, and disable it before a
> system update until compatibility has been revalidated.

## Features

- Redirects only `TYPE_BLUETOOTH_SCO` and `TYPE_BLE_HEADSET` output requests.
- Leaves speaker, earpiece, wired, USB, and all other device requests untouched.
- Always bypasses real calls, privileged components, and `MODE_IN_CALL`.
- Avoids starting HFP/SCO and suspending A2DP; Android selects an available non-SCO input.
- Uses media volume as the control source for the active VoIP route and restores prior call-volume
  state when the policy exits.
- Sends a package-targeted compatibility event to applications waiting for SCO state.
- Provides a concise Material 3 UI backed by live LSPosed Remote Preferences.

## How it works

```text
Third-party app requests SCO / BLE Headset
        │
        ▼
AudioService validates the public request
        │
        ▼
AudioDeviceBroker central route handler
        │  replaces the target with the active A2DP device
        ▼
Native communication route client
        │  keeps the original Binder token, UID, and lifecycle
        ▼
AudioFlinger mixing and resampling
        │
        ▼
A2DP encoding and Bluetooth output
```

Applications submit ordinary PCM. `VOICE_COMMUNICATION` and `VOICE_CALL` are routing metadata, not
SCO bitstream formats. The system mixer converts tracks to the sample rate, channel layout, and
codec required by the final A2DP output.

## Requirements

- Android 16;
- Modern LSPosed API 102;
- a classic A2DP headset already connected and working for media playback;
- a recovery environment capable of disabling an LSPosed module.

The current implementation resolves private Android 16 `AudioService` and `AudioDeviceBroker`
members. Signatures may differ across system versions and vendor implementations. Resolution or
installation failure rolls back the complete Hook set and preserves the original framework path.

## Installation

1. Download the APK and matching `.sha256` file from
   [Releases](https://github.com/silverpoetry/GlobalVoipA2dp/releases).
2. Verify the SHA-256 digest.
3. Install the APK and enable it in LSPosed. Its static scope must contain only System Framework /
   `system`; do not select any application.
4. Reboot once so `system_server` loads the module.
5. Open “VoIP A2DP”, confirm that routing is enabled, then test media, VoIP, volume keys, and a real
   phone call in that order.

Android may reject an upgrade from a locally debug-signed build to a public Release. Disable the old
module, reboot, uninstall the debug build, install the Release, enable it, and reboot once more. See
the [installation guide](docs/installation.md) for the full recovery sequence.

## Runtime switch

The companion application and `system_server` share one LSPosed Remote Preferences group. Disabling
the switch immediately bypasses future SCO and BLE Headset requests without a reboot.

The UI does not asynchronously tear down an already committed route because that could expose a
private call through the speaker. Re-select an output device or leave and re-enter the session to
apply the new state to an active call.

## Known limitations

- A2DP latency is normally higher than SCO latency.
- Classic A2DP has no paired microphone input. Recording normally falls back to the built-in
  microphone, but wired, USB, or other concurrently connected inputs can change the actual policy.
- A passive A2DP disconnect may not immediately clear the unconventional communication route client;
  leave and re-enter the session when necessary.
- Applications that only poll SCO query APIs and ignore state events may still report a failed
  Bluetooth route.
- Applications can observe the final A2DP route through public audio APIs. This is not a stealth or
  anti-detection tool.
- Media volume is the control source, while the communication track still uses the VOICE_CALL gain
  curve; mute and step behavior are not guaranteed to match ordinary music exactly.

## Privacy and security

- No `INTERNET`, recording, storage, account, or location permission.
- No analytics, telemetry, advertising, or remote crash-reporting SDK.
- No audio content is read, stored, or forwarded.
- Remote Preferences stay in LSPosed local storage and are not exposed as system properties.
- The module remains an installed package and can be enumerated according to Android package-query
  rules.

See [Privacy](PRIVACY.md) and [Security](SECURITY.md).

## Build

JDK 17 and Android SDK Platform 37 are required:

```powershell
.\gradlew.bat --no-daemon clean testDebugUnitTest lintRelease assembleRelease
```

Without release-signing environment variables, Gradle produces an unsigned APK. The tag workflow
uses repository secrets to sign and verify the APK and publishes a matching SHA-256 file. See
[release signing](docs/release-signing.md).

## License

Licensed under the [Apache License 2.0](LICENSE). See
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) for third-party dependencies and licenses.
