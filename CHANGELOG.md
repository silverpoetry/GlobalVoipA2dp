# Changelog

All notable changes to this project are documented in this file.

## [1.1.0] - 2026-08-11

### Added

- Modern LSPosed API 102 `system_server` module with a static `system` scope.
- Material 3 settings application backed by LSPosed Remote Preferences.
- Package-targeted compatibility state events for applications waiting on SCO setup.
- Unit tests, APK audit tooling, CI, signed tag-release workflow, and public documentation.

### Changed

- Bluetooth route interception is limited to SCO and BLE Headset requests.
- Speaker, earpiece, wired, USB, real-call, and privileged routes remain native.
- Native phone state and media-volume aliasing are active only while a redirected A2DP route client
  belongs to the current communication owner.

### Security

- The settings application does not request root, run shell commands, or expose a custom system
  property.
- Hook installation and Remote Preferences listener registration share one rollback boundary.
- Framework callback failures are protective and fail open to the original Android behavior.

### Validation

- Five unit tests cover route and mode policy gates.
- Android Lint reports no issues.
- Debug and release APK metadata, Modern Xposed entries, signatures, and scope are audited during the
  release process.

[1.1.0]: https://github.com/silverpoetry/GlobalVoipA2dp/releases/tag/v1.1.0
