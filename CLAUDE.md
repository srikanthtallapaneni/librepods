# LibrePods — guide for Claude sessions

LibrePods brings AirPods features that Apple keeps to its own devices (listening modes, ear detection,
accurate battery, head gestures, conversation awareness, hearing aid, transparency tuning, …) to
Android and Linux, by speaking Apple's proprietary Bluetooth protocols (AACP over L2CAP, plus ATT
and BLE advertisements).

This repository is **Sri's fork** (`srikanthtallapaneni/librepods`) of the upstream project
`kavishdevar/librepods`. The fork's own change so far: the FOSS build's payment wall is removed
(`FOSSBillingProvider` always reports premium). Keep that behaviour unless told otherwise.

Read `docs/coding-standards.md` before writing code. It is what the `code-reviewer` agent enforces.

## Repository map

| Path | What it is |
| --- | --- |
| `android/` | The main product: Kotlin + Jetpack Compose Android app (Gradle, single `:app` module). |
| `android/app/src/main/java/me/kavishdevar/librepods/` | All Kotlin sources (package `me.kavishdevar.librepods`). |
| `android/app/src/main/cpp/` | Native `libl2c_fcr_hook.so` (Xposed hook for the Bluetooth stack's L2CAP FCR bug) + vendored xz decoder. |
| `android/app/src/main/res/` | Resources; `values-*/strings.xml` are translations. `res-apple/` is an extra res dir holding AirPods model images (`res-apple/drawable/<model>_{buds,left,right,case}.png`) and Apple-style fonts. |
| `android/app/src/main/resources/META-INF/xposed/` | libxposed module metadata (entry class, scope = Bluetooth + Settings apps). |
| `android/buildSrc/.../versioning.gradle.kts` | Computes `versionName`/`versionCode` from `android/version.properties`. |
| `android/gradle/libs.versions.toml` | Version catalog. All dependencies go through it. |
| `linux/` | Legacy C++17 / Qt 6 / QML desktop app (CMake). Upstream's active Linux work is a Rust rewrite on the `linux/rust` branch, not here. |
| `root-module-manual/` | Magisk/KernelSU module skeleton; Gradle zips the APK into it as a privileged system app. |
| `docs/` | Reverse-engineered protocol notes: `AAP Definitions.md`, `control_commands.md`, `opcodes.md`, `device-info.md`. |
| `extras/` | Helper scripts (`proximity_keys.py`) and root-module changelog/update JSON. |
| `.github/workflows/` | CI: `ci-android.yml` (builds on every push/PR touching `android/`), Linux workflows. |

## Android architecture

```
AirPods  <--L2CAP PSM 0x1001 (AACP)-->  AACPManager ─┐
         <--L2CAP ATT------------------> ATTManager  ├─> AirPodsService (foreground service, 3k lines)
         <--BLE adverts----------------> BLEManager  ┘        │  broadcasts + ServiceManager singleton
                                                               ▼
                       ControlCommandRepository  ──>  AirPodsViewModel (StateFlow<AirPodsUiState>)
                                                               ▼
                       NavigationRoot (StyledScaffold + title)  ─>  AppNavGraph (Navigation 3)  ─>  screens/
```

- **`bluetooth/AACPManager.kt`** builds and parses AACP packets. Opcodes live in
  `AACPManager.Companion.Opcodes`; control-command IDs in `ControlCommandIdentifiers` (byte values
  match `docs/control_commands.md`). Incoming packets go through `receivePacket()` and are dispatched
  to `PacketCallback`; per-ID listeners use `ControlCommandListener`.
- **`bluetooth/ATTManager.kt`** reads/writes GATT-like handles (`ATTHandles`: transparency, loud
  sound reduction, hearing aid). **`BLEManager.kt`** decodes proximity advertisements (battery, lid,
  ear state) using keys from `utils/BluetoothCryptography.kt`.
- **`services/AirPodsService.kt`** owns the sockets, notifications, widgets, ear-detection media
  control, island overlays and the cross-device takeover logic. `ServiceManager` exposes the running
  instance. Intent actions are string constants prefixed `me.kavishdevar.librepods.`.
- **`data/`**: `AirPods.kt` declares every supported model (`AirPodsBase` subclass with model
  numbers, drawables and a `Set<Capability>`). UI features are gated on `state.capabilities`.
  `ControlCommandRepository` wraps `AACPManager` for the ViewModel.
- **`presentation/`**: Compose UI. `viewmodel/AirPodsViewModel` holds one immutable
  `AirPodsUiState` updated with `_uiState.update { it.copy(...) }`. Screens take the ViewModel (or
  `state` + callbacks) and navigate through lambdas such as `navigateToX: () -> Unit`.
  `navigation/Screen.kt` is a sealed interface of `@Serializable` destinations; `AppNavGraph` maps
  each to a `NavEntry`; `NavigationRoot` maps each to its title.
- **Design systems**: `LocalDesignSystem.current` is either Apple-style ("liquid glass", Kyant
  backdrop + Haze) or `DesignSystem.Material` (M3 Expressive). Shared `Styled*` components in
  `presentation/components/` handle both; use them instead of raw Material widgets.
- **Demo mode**: `AirPodsViewModel.isDemoMode` lets the UI run without hardware. ViewModel setters
  must keep working (skip the Bluetooth write, still update state) in demo mode.
- **Build flavors**: `foss` (GitHub/root module, minSdk 33) and `play` (Play Store, minSdk 36,
  `BuildConfig.PLAY_BUILD == true`, real Play Billing). Branch on `BuildConfig.PLAY_BUILD`, never
  on package name.
- **Root / Xposed**: `utils/KotlinModule.kt` is the libxposed entry point; it loads
  `libl2c_fcr_hook.so` into `com.android.bluetooth`. `utils/RadareOffsetFinder.kt` finds hook
  offsets. Features marked "VendorID hook" need `state.vendorIdHook`.

## Build and check

There are no unit or instrumentation tests in this repo. "Verify" means: it compiles, lint is not
worse, and (when possible) it was tried on a device or in demo mode.

Android (run inside `android/`, JDK 21, Android SDK + NDK `30.0.14904198`, compileSdk 37):

```bash
./gradlew assembleFossDebug          # what CI runs on pull requests
./gradlew assemblePlayDebug          # Play flavor (checks PLAY_BUILD branches compile)
./gradlew lintFossDebug              # Android lint
./gradlew printAppVersion            # VERSION_NAME / VERSION_CODE from version.properties
./gradlew collectReleaseArtifacts    # release APKs, AAB, root-module zips into ../release (needs signing keys)
```

Linux legacy app (Qt 6, OpenSSL, libpulse): `cd linux && mkdir -p build && cd build && cmake .. -G Ninja && ninja`.

Gotchas:
- `android/gradle.properties` contains `org.gradle.java.home=/Users/srikantht/jdk21/Contents/Home`
  (Sri's Mac). On any other machine, override it (`./gradlew -Dorg.gradle.java.home=$JAVA_HOME ...`)
  or the build fails. Do not commit a different machine's path.
- Cloud sessions usually have no Android SDK, so Gradle cannot run there. Say so plainly instead of
  claiming a build passed.
- Release signing reads `android/local.properties` (`RELEASE_STORE_FILE`, …). Never commit it or a keystore.
- `android/app/src/main/res/value-it/` is misnamed (should be `values-it`), so Italian strings are
  currently ignored by Android. Fix it only as a deliberate, separate change.
- `aboutlibraries.json` in `res/raw/` is generated by the AboutLibraries plugin.

## Workflow rules

- **Work in Sri's local copy, never commit.** All changes are made in the local checkout at
  `/Users/srikantht/Freelance/librepods` and left **uncommitted** for Sri to review. Do not run
  `git commit`, `git push`, `git stash`, `git reset`, or open PRs unless Sri asks for that specific
  step in the current conversation. When done, list the changed files (`git status`) and stop.
- When Sri does commit, subjects use a lowercase area prefix, as in upstream history: `android: ...`, `linux: ...`,
  `ci: ...`, `docs: ...`, `fix: ...`. Imperative, short, no trailing period.
- If Sri asks for a PR, it targets `main` of the fork, never upstream.
- Every new Kotlin/C++ source file starts with the GPLv3 header used across the repo (see
  `docs/coding-standards.md`).
- User-facing text goes into `res/values/strings.xml` (English); add translations only when you
  actually have them. Never hard-code UI strings in Kotlin.
- Protocol discoveries (new opcode, control-command ID, packet layout) also get written into the
  matching file in `docs/`.
- The README has a "Use of AI" section listing AI-written parts. If a change adds a substantial
  AI-written feature, mention it there.

## Claude Code helpers in this repo

- Agents (`.claude/agents/`):
  - `code-reviewer`: reviews a diff against `docs/coding-standards.md`. Run it before handing changes to Sri for review.
  - `protocol-explorer`: read-only; answers "how does packet/opcode/feature X work" from the
    Kotlin, C++ and `docs/` sources.
- Skills (`.claude/skills/`): `build-android`, `add-control-command`, `add-settings-screen`,
  `add-airpods-model`, `add-strings`, `bump-version`, `sync-upstream`.
