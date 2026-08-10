# Release signing

## Required secrets

The tag workflow expects these GitHub Actions repository secrets:

- `RELEASE_KEYSTORE_BASE64`;
- `RELEASE_KEYSTORE_PASSWORD`;
- `RELEASE_KEY_ALIAS`;
- `RELEASE_KEY_PASSWORD`.

The keystore must be dedicated to this project. Never commit it, its passwords, or a generated
`signing.properties` file. Back up the key offline; losing it prevents in-place upgrades of published
APK versions.

## Local signed build

Set the following environment variables:

```text
GLOBALVOIPA2DP_KEYSTORE_PATH
GLOBALVOIPA2DP_KEYSTORE_PASSWORD
GLOBALVOIPA2DP_KEY_ALIAS
GLOBALVOIPA2DP_KEY_PASSWORD
```

Then run:

```powershell
.\gradlew.bat --no-daemon clean testDebugUnitTest lintRelease assembleRelease
```

Verify the resulting APK with the newest Android SDK Build Tools `apksigner`, then generate a
SHA-256 file. A build without all four variables deliberately remains unsigned and must not be
published as a Release artifact.

## Tag release

The workflow requires the tag to match `versionName` exactly:

```text
versionName 1.1.0 -> tag v1.1.0
```

It builds and verifies the signed APK, renames it to `GlobalVoipA2dp-vX.Y.Z.apk`, generates the
matching `.sha256` file, extracts release notes from `CHANGELOG.md`, and publishes both artifacts.
