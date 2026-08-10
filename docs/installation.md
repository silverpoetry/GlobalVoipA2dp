# Installation, upgrade, and removal

## Prerequisites

- Android 16;
- a working Modern LSPosed installation with API 102 support;
- a recovery path that can disable the module if `system_server` fails;
- a classic A2DP headset already working for ordinary media playback.

Private framework signatures vary. A newer Android build or a vendor-modified audio service is not
automatically compatible merely because the API level matches.

## Install

1. Download the APK and matching `.sha256` file from
   [Releases](https://github.com/silverpoetry/GlobalVoipA2dp/releases).
2. Verify the digest:

   ```powershell
   Get-FileHash .\GlobalVoipA2dp-vX.Y.Z.apk -Algorithm SHA256
   ```

3. Install the APK.
4. Enable Global VoIP A2DP Router in LSPosed. The Modern static scope must show only System Framework
   / `system`; do not add any application.
5. Reboot the device.
6. Open “VoIP A2DP” and confirm the routing switch is enabled.

## First validation

Test in this order:

1. system UI sounds and ordinary speaker playback;
2. ordinary A2DP media playback;
3. a VoIP session with microphone enabled;
4. hardware volume keys and the media-volume panel;
5. switching the session to speaker and earpiece;
6. a real phone call, including its normal output-device controls.

Stop and disable the module if any non-VoIP route changes unexpectedly.

## Upgrade

Installing a new APK does not replace code already loaded into `system_server`. After changing module
code or installing a new release, reboot once. Remote Preferences changes made by the settings UI do
not require a reboot after that.

Public Releases use a dedicated signing key. A locally debug-signed APK cannot be upgraded in place:

1. disable the old module in LSPosed;
2. reboot;
3. uninstall the debug build;
4. install the public Release;
5. enable the module;
6. reboot once more.

## Runtime disable

Turn off “Bluetooth calls use A2DP” in the companion application. New SCO and BLE Headset requests
will use the native Android route without rebooting.

The switch does not forcibly replace an already committed route. Re-select the desired output inside
the VoIP application or leave and re-enter the session.

## Complete removal

1. disable the module in LSPosed;
2. reboot;
3. uninstall the APK.

If the device cannot reach the launcher, use the recovery or safe-mode mechanism provided by the root
and Xposed environment. Do not replace framework files or delete system audio libraries; this project
does not modify them.
