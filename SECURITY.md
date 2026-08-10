# Security policy

## Supported versions

| Version | Security updates |
|---|---|
| Latest public release | Yes |
| Development and debug builds | Best effort |
| Older private test builds | No |

## Reporting a vulnerability

Use GitHub's private vulnerability reporting for this repository. Do not open a public Issue for a
problem that can restart or boot-loop a privileged process, route private audio to an unintended
device, expose signing material, or enable arbitrary code execution.

Include the affected version, Android and LSPosed versions, reproduction steps, impact, and the
smallest necessary redacted log excerpt. Do not submit complete framework files, proprietary APKs,
account data, device serials, Bluetooth addresses, or signing material. Allow a reasonable
remediation window before public disclosure.

## Operational safety

- Keep a tested recovery path before enabling a module that injects into `system_server`.
- Install only artifacts from this repository's Releases and verify SHA-256.
- Never publish or reuse the Android debug keystore for release signing.
- Disable the module before a system update and revalidate private framework signatures afterward.
- Treat any unsupported framework implementation as incompatible until proven otherwise.
