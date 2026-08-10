# Privacy

Global VoIP A2DP Router is an offline system module.

## Data handled

The companion application stores one user-selected setting through LSPosed Remote Preferences:
whether Bluetooth communication requests should be redirected to A2DP.

The system module processes only the routing metadata needed to evaluate a request: calling UID,
attribution package, requested audio-device type, audio mode, connected output types, and framework
route-client state. This processing happens locally in memory.

## Data not collected

- no network permission;
- no analytics, telemetry, advertising, or remote crash reporting;
- no account, location, contact, storage, or media permission;
- no microphone or audio-capture permission in the companion application;
- no audio samples, meeting content, transcripts, or call history;
- no upload endpoint.

LSPosed and Android logs may contain package names, UIDs, device names, framework stack traces, or
other surrounding system information. Review and redact logs before sharing them publicly.

## Removal

Disable the module in LSPosed, reboot, and uninstall the APK. Application-side settings are removed
with the package; LSPosed may retain module metadata according to its own lifecycle.
