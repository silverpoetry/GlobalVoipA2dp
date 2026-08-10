# Contributing

Global VoIP A2DP Router runs inside `system_server`. Correctness, narrow scope, recoverability, and
evidence take precedence over broad but unverified compatibility.

## Development environment

- JDK 17;
- Android SDK Platform 37;
- a Modern LSPosed API 102 test environment;
- a device or emulator environment that can be recovered if a privileged process fails.

Run the full local check before opening a pull request:

```powershell
.\gradlew.bat --no-daemon clean testDebugUnitTest lintRelease assembleRelease
```

## Design rules

1. Hook the narrowest shared framework boundary that preserves Android's native lifecycle.
2. Never inject into individual VoIP applications for generic routing behavior.
3. Preserve real-call, privileged-caller, speaker, earpiece, wired, and USB paths.
4. Do not report success for a route the framework did not actually commit.
5. Keep original-method exceptions observable and never call an original method twice.
6. Resolve private members before installing Hooks and roll back a partial installation.
7. Keep Remote Preferences keys centralized in `ModulePreferences`.
8. New polling, persistent state, or background work must document lifetime and failure behavior.

## Evidence required for compatibility changes

- Android and LSPosed versions;
- target class and complete method signature;
- evidence that the method is a stable shared boundary rather than an application-specific path;
- before/after `dumpsys audio` and `media.audio_policy` excerpts with personal data removed;
- fallback behavior when A2DP is missing or the private API cannot be resolved;
- real-call and non-Bluetooth route regression results.

Do not commit proprietary APKs, framework JARs, decompiled source trees, full build fingerprints,
device serials, IP addresses, personal Bluetooth addresses, account data, captures, or signing
material.

## Code and commits

- Java and Kotlin use 4 spaces; Markdown, XML, and YAML use 2 spaces;
- use LF except Windows batch files;
- keep classes focused on one responsibility;
- add unit tests for pure policy and settings behavior;
- use short imperative commit subjects;
- explain impact, evidence, and validation in the pull request.

By contributing, you agree that your contribution may be distributed under the Apache License 2.0.
