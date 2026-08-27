# HomeCommand

A lightweight Android dashboard for controlling [Zigbee2MQTT](https://www.zigbee2mqtt.io/) devices
(lights, covers, sensors, thermostats) directly over MQTT — no cloud, no hub app in between.

## Table of Contents

- [Features](#features)
- [Architecture](#architecture)
- [MQTT topic conventions](#mqtt-topic-conventions)
- [Security notes](#security-notes)
- [Building](#building)
- [Code quality](#code-quality)
- [Contributing](#contributing)
- [License](#license)

## Features

- Device cards with per-type controls: on/off switches, dimmers, color & color-temp lights,
  covers (open/stop/close), thermostat +/- stepping and sensor read-outs
- Rooms group devices into their own drill-in screens; unassigned devices stay reachable from home
- Received state payloads are cached locally (last N values per topic, configurable in Settings,
  default 100), so cards show the latest known value immediately on launch
- Card status dot reflects the live MQTT subscription state (broker connected + state topic SUBACK'd)
- Sensor tiles are read-only: they only need a state topic, and each predefined device type renders
  its known fields (contact/motion/vibration/battery/temperature/...) via typed extractors
- Custom payload templates per device action with `{placeholder}` substitution for non-standard firmware
- Broker settings with optional TLS and username/password auth
- Automatic reconnection, subscription restore and device-state refresh after drops

## Architecture

Plain View-based app (Fragments + Material Components) kept intentionally small.

Key decisions:

- **Drawer-based navigation with separate sub-screen activities.** Rooms, settings, and logs open
  as distinct `Activity` instances (`RoomDetailActivity`, `SettingsActivity`, `LogsActivity`),
  preserving native Android back-stack behavior. No nested fragment back stacks.
- **No activity-coupled services.** Fragments never reach into `MainActivity`; they talk to
  their ViewModels, which talk to `MqttManager` and `DeviceStorage`.
- **`HomeViewModel`/`SettingsViewModel` are process-scoped singletons**, mirroring `MqttManager`,
  so every activity shares the same data without reload races. They're not instantiated via
  `viewModels()` or `activityViewModels()`.
- **ViewModels are the source of truth** for devices and device states. MQTT messages arrive on
  background threads and are surfaced to the UI through StateFlow.
- **The MQTT connection lives for the whole process lifetime** rather than being tied to an
  activity, so rotations and navigation don't cause reconnect churn.
- **Pure logic is separated from Android** (`Device`, `MqttSettings`, `DeviceStateReader`,
  `MqttStateHistory`) and is covered by JVM unit tests.

## MQTT topic conventions

| Purpose | Topic | Payload example |
| --- | --- | --- |
| Command | `<topicBase>/<name>/set` | `{"state":"ON","brightness":200}` |
| State request | `<topicBase>/<name>/get` | `{"state":""}` |
| State updates | `<topicBase>/<name>` (subscribed) | `{"state":"ON","brightness":180,...}` |

`<topicBase>` defaults to `zigbee2mqtt` and is configurable in Settings.

## Security notes

- The broker password is encrypted at rest with an AES-256-GCM key stored in **AndroidKeyStore**
  (`CryptoManager`). Legacy plaintext entries migrate automatically on the next save.
- The credentials file is **excluded from cloud backups and device transfers**
  (`backup_rules.xml`, `data_extraction_rules.xml`) because KeyStore keys don't travel.
- `usesCleartextTraffic` is enabled so plain-TCP brokers on the local network work; prefer TLS
  (`useTls`) when your broker supports it. Saving a non-TLS broker whose host resolves outside
  the local network shows a warning — cleartext beyond the LAN exposes credentials.
- Release builds are R8-minified with resource shrinking; all persisted model fields carry
  `@SerializedName` so obfuscation cannot break JSON round-trips.

## Building

Requirements: JDK 21 (provisioned automatically via the Gradle toolchain), Android SDK 37.

```powershell
.\gradlew.bat assembleDebug        # build
.\gradlew.bat installDebug         # install on connected device
```

## Code quality

```powershell
.\gradlew.bat spotlessApply        # format (ktlint)
.\gradlew.bat detekt               # static analysis
.\gradlew.bat testDebugUnitTest    # unit tests
```

Formatting and static analysis are expected to stay green; run `spotlessApply` before committing.

## Contributing

We welcome contributions. Before you start:

- **Code style:** Run `spotlessApply` to format your code; never hand-format Kotlin.
- **Static analysis:** Ensure `detekt` passes. See `config/detekt/detekt.yml` for rules.
- **Testing:** Add JVM unit tests for pure logic (no Android imports). Tests run via `testDebugUnitTest`.
- **Architecture:** Keep Android dependencies out of `data/` and `mqtt/` packages. ViewModels own
  all state changes and I/O. Fragments never touch `MqttManager` directly.
- **Commit messages:** Be concise and descriptive.

Before pushing, verify the full build passes:

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat spotlessApply detekt testDebugUnitTest assembleDebug --console=plain
```

See `AGENTS.md` for detailed development notes and architecture invariants.

## License

[See LICENSE file](./LICENSE)
