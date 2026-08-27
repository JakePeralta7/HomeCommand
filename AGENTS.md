# AGENTS.md

## Commands (Windows PowerShell)

Set JAVA_HOME before any Gradle invocation or the wrapper fails:

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat spotlessApply detekt testDebugUnitTest assembleDebug --console=plain
```

- Run in this order; `spotlessApply` first (it rewrites formatting detekt/ktlint would flag).
- Always pass `--console=plain` (cleaner output on Windows).
- Tests are JVM-only (`app/src/test`); no instrumented tests exist. Single class:
  `.\gradlew.bat testDebugUnitTest --tests "net.elad.homecommand.mqtt.DeviceStateReaderTest" --console=plain`
- Test stack is bare JUnit 4 only — no Robolectric, no mocking library. That's why code under
  test must stay free of Android imports.

Install + smoke-test on device:

```powershell
.\gradlew.bat installDebug --console=plain
$raw = (Get-Content local.properties | Select-String "sdk.dir").Line -replace "^sdk\.dir=", ""
$adb = Join-Path ([System.Text.RegularExpressions.Regex]::Unescape($raw)) "platform-tools\adb.exe"
& $adb shell am force-stop net.elad.homecommand
& $adb shell monkey -p net.elad.homecommand -c android.intent.category.LAUNCHER 1
& $adb logcat -d --pid=(& $adb shell pidof net.elad.homecommand) | Select-String "MqttManager"
```

`sdk.dir` in `local.properties` contains escaped backslashes — unescape before joining or adb path is wrong.

## Static analysis gotchas (detekt 2.x)

- Config lives in `config/detekt/detekt.yml`; it uses 2.x keys (`LongMethod.allowedLines`,
  `TooManyFunctions.allowedFunctionsPer*`). 1.x syntax (`threshold:`, `build:` section) fails the build.
- `ThrowsCount` and `ReturnCount` max is 2: write single-exit helpers that return `null` instead of throwing.
- `LoopWithTooManyJumpStatements`: a loop may not contain two `continue`s — restructure as a `when`.
- MagicNumber and TooGenericExceptionCaught are disabled project-wide; don't re-enable casually.
- Never hand-format Kotlin to match ktlint's trailing-comma / when-branch style — run `spotlessApply`
  and let it win.

## Architecture invariants

- `mqtt/MqttManager` is a process-scoped singleton (`MqttManager.get(context)`); it owns the HiveMQ
  MQTT 5 client, subscriptions registry, connection state, and payload history cache. Its public
  API is suspend-based (`connect(): Boolean`, `publish(): Boolean`, `testConnection(): ConnectionTest`);
  command failures surface through `HomeViewModel.commandFailures`, not silently. Fragments must
  never cast activities or touch MQTT directly — they go through ViewModels.
- `HomeViewModel` and `SettingsViewModel` are process-scoped singletons (`.get(context)`) so every
  activity shares rooms/devices/states/settings; they rely on the same never-cleared lifecycle as
  `MqttManager`. Don't instantiate them with `viewModels()`/`activityViewModels()`.
- Sub-screens are separate activities (`RoomDetailActivity`, `MqttConnectionActivity`,
  `GeneralActivity`), not stacked fragments — system back must stay native activity popping.
  Don't reintroduce child-fragment back stacks inside tab containers. Shared chrome lives in
  `ui/widgets/SubScreenChrome.kt`; breadcrumb trails in `ui/widgets/BreadcrumbBarView.kt`.
- Edge-to-edge insets go on the screen ROOT (and bottom nav), never as top padding on a
  MaterialToolbar hosting custom children — Toolbar measures children against its height minus
  vertical padding, collapsing them to 0.
- ViewModels are the single source of truth for devices/states; storage I/O is always
  `suspend` + `Dispatchers.IO` (`DeviceStorage`, `StateCacheStorage`).
- Pure logic lives in `data/` and `mqtt/` packages with zero Android imports so it stays JVM-testable.
  New parsing/formatting logic belongs there, not in Fragments/adapters.
- HiveMQ builder quirk: auth must be applied inline on the `connectWith()` chain
  (`.simpleAuth().username(..).password(..).applySimpleAuth()`, discarding the return). Extension
  functions on the plain `Mqtt5ConnectBuilder` do not compile against this API version.
- `minSdk = 37` is intentional. Do not add SDK_INT guards or backport workarounds.

## Domain rules

- Device types are predefined (`data/DeviceType`): each type has a dedicated tile layout in
  `ui/rooms` and its state fields are read via `mqtt/DeviceStateReader`. Sensor types are read-only:
  `Device.commandTopic` may be null for them; validation requires a state topic instead. Only
  `SMART_PLUG` and `IR_REMOTE` are writable.
- State parsing is per-field extractors (e.g. `DeviceStateReader.contact`, `power`), not JSONPath.
  Zigbee2MQTT semantics matter: `contact: true` means *closed*; button presses arrive as transient
  `action` fields. IR learning publishes `learn_ir_code: ON`; the code arrives on the state topic as
  `learned_ir_code`.
- Rooms live in the same prefs file as devices (`devices_v2`/`rooms` keys); pre-rooms data was
  intentionally dropped — don't reintroduce legacy-key fallbacks.
- Every Gson-persisted model field (`Device`, `Room`, `MqttSettings`) must carry `@SerializedName`
  with the current JSON key; new persisted fields must too or deserialization will fail and silently
  default to null values.
- Broker password is AES-GCM encrypted via AndroidKeyStore (`CryptoManager`, `enc:v1:` prefix);
  legacy plaintext migrates on next save. The prefs file (`my_automations`) is excluded from
  backups (`backup_rules.xml` / `data_extraction_rules.xml`) because KeyStore keys don't travel —
  keep those exclusion files in sync if prefs names change.
- Battery-powered Zigbee sensors answer `/get` only on their wake cycle (~60s+); latency there is
  device behavior, not an app bug.

## Conventions

- Comments/KDoc: one-liners explaining *why* only (threading contracts, crypto format, non-obvious
  fallbacks). No restating comments, no region markers.
- Navigation: drawer-based (see `MainActivity`) with separate activities for settings
  (`SettingsActivity`), logs (`LogsActivity`), and room detail screens (`RoomDetailActivity`).
  Settings and room edits are sub-screen activities launched via `startActivity()`.
- `README.md`'s architecture section lags behind the code — trust the code over it.
- Only commit when the user explicitly asks; leave changes uncommitted otherwise.

## Release & Deployment

- Release builds are deployed to Google Play closed testing only; never install release APK
  manually via adb. Debug builds (`assembleDebug`/`installDebug`) are for local testing only.
- After merging to `master`, update the version in `app/build.gradle.kts` (Versions object),
  build the release bundle with `bundleRelease`, and upload to Play Console.
