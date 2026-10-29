# LibrePods coding standards

These rules are drawn from the existing code, `.editorconfig`, the Gradle setup and upstream's commit
history. They describe how the code base is already written, so a change that follows them looks like
the code around it. The `code-reviewer` Claude agent checks changes against this file; keep the two
in sync when a rule changes.

Rules marked **[must]** are blocking in review. The rest are **[should]**: follow them unless the
surrounding code clearly does otherwise, and say why when handing the change over.

## 1. All files

- **[must]** Formatting follows `.editorconfig`: UTF-8, LF line endings, final newline, no trailing
  whitespace (except Markdown). Indent with spaces: 4 for Kotlin, KTS, XML, Java, C/C++, QML,
  Python; 2 for everything else (YAML, JSON); 4 in Markdown.
- **[must]** New Kotlin, C++ and QML source files start with the project GPLv3 header:

  ```
  /*
      LibrePods - AirPods liberated from Apple’s ecosystem
      Copyright (C) 2025 LibrePods contributors

      This program is free software: you can redistribute it and/or modify
      it under the terms of the GNU General Public License as published by
      the Free Software Foundation, either version 3 of the License, or
      any later version.

      This program is distributed in the hope that it will be useful,
      but WITHOUT ANY WARRANTY; without even the implied warranty of
      MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
      GNU General Public License for more details.

      You should have received a copy of the GNU General Public License
      along with this program.  If not, see <https://www.gnu.org/licenses/>.
  */
  ```

  Vendored third-party code (`cpp/xz/`, `linux/thirdparty/`) keeps its own license header and is not
  edited except to update it from upstream.
- **[must]** No secrets or machine-local files: keystores, `local.properties`, service-account JSON,
  API tokens. Do not add personal absolute paths (the existing `org.gradle.java.home` line in
  `android/gradle.properties` is a known exception, not a precedent).
- **[should]** Don't leave new commented-out code blocks. Existing commented code is upstream's
  parking lot for unfinished features; leave it alone unless the change is about it.
- **[should]** Comments explain *why* or record protocol knowledge (byte layouts, observed firmware
  behaviour). Don't narrate what the next line does.

## 2. Kotlin (Android)

### Language and style
- **[must]** Kotlin official code style (`kotlin.code.style=official`). Explicit imports only, no
  wildcard (`.*`) imports.
- **[must]** Package matches directory under `me.kavishdevar.librepods` (`bluetooth`, `data`,
  `services`, `presentation.{components,screens,viewmodel,navigation,theme,widgets,overlays}`,
  `utils`, `billing`, `receivers`).
- **[should]** Naming: `PascalCase` types and composables, `camelCase` functions and properties,
  `SCREAMING_SNAKE_CASE` for `const val` and enum entries. Screens end in `Screen`, ViewModels in
  `ViewModel`, managers in `Manager`.
- **[should]** Logging uses `android.util.Log` with a `TAG` (`private const val TAG = "ClassName"`
  at file or companion level). Don't log full proximity keys, IRK/ENC keys or MAC addresses at
  `Log.i` or above.
- **[must]** No `!!` on values that come from the device, a broadcast, `SharedPreferences` or an
  Intent extra. Packet data is untrusted: check `packet.size` before indexing, as
  `AACPManager.receivePacket()` does.
- **[should]** Prefer `?.`, `?:`, `let`, `getOrNull()` over nested null checks. Byte constants are
  written in hex (`0x0D`) with `.toByte()` where needed.
- **[should]** Data classes holding `ByteArray` override `equals`/`hashCode` with `contentEquals`/
  `contentHashCode` (see `AACPManager.ControlCommandStatus`).

### Concurrency
- **[must]** No `GlobalScope` and no `runBlocking` on the main thread. In ViewModels use
  `viewModelScope`; Bluetooth/socket/file work goes to `Dispatchers.IO`.
- **[must]** Never do socket reads/writes or blocking I/O on the main thread or inside a composable.
- **[should]** A `CoroutineScope(...)` or listener created by a class is cancelled/unregistered in
  its teardown (`onCleared`, `onDestroy`, `DisposableEffect` `onDispose`). Every
  `registerReceiver`/`registerControlCommandListener` has a matching unregister.

### Protocol layer (`bluetooth/`, `data/`)
- **[must]** New AACP opcodes go in `AACPManager.Companion.Opcodes`; new control-command IDs go in
  `ControlCommandIdentifiers` with the exact byte from the device. Unknown/guessed meanings get a
  short comment (the existing `// not sure what this does` style is fine).
- **[must]** Packet builders are split into `createXxxPacket(): ByteArray` (pure) and
  `sendXxx(): Boolean` (writes), matching existing pairs like `createRenamePacket`/`sendRename`.
- **[must]** Control-command booleans use the device encoding `0x01` = on, `0x02` = off (see
  `AirPodsViewModel.setControlCommandBoolean`). Don't use `0x00` for off.
- **[should]** New protocol facts are also documented in `docs/` (`control_commands.md`,
  `opcodes.md`, `AAP Definitions.md`).
- **[must]** Features are gated by model capability: add a `Capability` and list it on the
  `AirPodsBase` subclasses that support it rather than checking model names in UI code.

### State and UI (Compose)
- **[must]** UI state is immutable. `AirPodsViewModel` exposes `StateFlow<AirPodsUiState>` and
  updates with `_uiState.update { it.copy(...) }`. Don't add `mutableStateOf` fields to ViewModels
  or mutate collections inside the state.
- **[must]** ViewModel setters that write to the device skip the write when `isDemoMode` is true and
  still update UI state, so demo mode keeps working.
- **[must]** Screens and components do not touch `AACPManager`, sockets or `AirPodsService`
  directly; go through the ViewModel (or `ControlCommandRepository` inside it).
- **[must]** All user-visible text uses `stringResource(R.string.x)` / `context.getString`. New
  keys are `snake_case` in `res/values/strings.xml`; non-translatable strings carry
  `translatable="false"`. Don't edit a locale file without a real translation.
- **[should]** Reuse `presentation/components/Styled*` (`StyledToggle`, `StyledList`,
  `StyledListItem`, `StyledSlider`, `StyledScaffold`, …) so both design systems
  (`LocalDesignSystem.current`, Apple-style vs `DesignSystem.Material`) render correctly. Raw
  Material widgets are only for M3-only code paths.
- **[should]** Navigation: new destinations are `@Serializable data object`/`data class` in
  `Screen.kt`, wired in `AppNavGraph` and titled in `NavigationRoot`. Screens receive navigation as
  lambdas (`navigateToX: () -> Unit`), never a NavController/back stack.
- **[should]** Composables: `modifier: Modifier = Modifier` as the first optional parameter on
  reusable components; hoist state (pass `checked` + `onCheckedChange`), keep side effects in
  `LaunchedEffect`/`DisposableEffect`.
- **[should]** Opt-ins (`ExperimentalHazeMaterialsApi`, `ExperimentalMaterial3Api`,
  `ExperimentalEncodingApi`) are declared with `@OptIn`/`@file:OptIn` where used, as in existing
  files.

### Platform, build and flavors
- **[must]** Flavor differences are expressed with `BuildConfig.PLAY_BUILD`. The fork's FOSS build
  treats every user as premium (`FOSSBillingProvider`); don't reintroduce a paywall in FOSS.
- **[must]** Dependencies are added through `gradle/libs.versions.toml` and referenced as
  `libs.xxx`. No hard-coded coordinates in `build.gradle.kts`.
- **[must]** Hidden/system API calls and anything needing root, Xposed or privileged permissions are
  guarded (check `XposedState`/`state.vendorIdHook`/permission) and fail soft with a log, never crash
  a non-root user.
- **[should]** New runtime permissions are declared in `AndroidManifest.xml` and requested through
  the existing onboarding/permission flow.
- **[should]** Keep `minSdk` (33 FOSS / 36 Play) in mind; guard newer APIs with `Build.VERSION.SDK_INT`.
- **[should]** `SharedPreferences` file is `"settings"`; new keys are `snake_case` and read with a
  default value.

## 3. Native C/C++ (`android/app/src/main/cpp`, `linux/`)

- **[must]** Same GPLv3 header on new files. Vendored code stays untouched.
- **[should]** Linux app: Qt style. `PascalCase` classes, `camelCase` methods, `Q_OBJECT` on QObject
  subclasses, signals/slots for cross-thread work, header guards (`#ifndef FOO_H`) or `#pragma once`
  matching the neighbouring files. New sources are listed in `linux/CMakeLists.txt`.
- **[must]** Bounds-check every buffer read from a socket or packet before indexing.
- **[should]** Native hook code (`l2c_fcr_hook.cpp`) logs through the existing log macros and returns
  to the original function on any failure rather than aborting the Bluetooth process.

## 4. Resources

- **[must]** Drawables for a new AirPods model follow the existing names
  (`<model>_buds`, `<model>_left`, `<model>_right`, `<model>_case`).
- **[should]** Locale folders are `values-<lang>` / `values-<lang>-r<REGION>`. (`value-it/` is a known
  misnamed folder.)
- **[should]** Widget resources use "listening mode/control" naming, matching the recent rename away
  from "noise control".

## 5. Commits, PRs, docs

- **[must]** Claude sessions work in Sri's local copy and never commit, push or open PRs on their own.
  Changes are left uncommitted for Sri to review; Sri makes the commits.
- **[must]** Commit subject: `<area>: <imperative summary>` with `area` in `android`, `linux`,
  `ci`, `docs`, `fix`, `root-module`. Lowercase, no trailing period.
- **[should]** One logical change per commit; version bumps are their own `android: bump version`
  commit.
- **[should]** User-visible changes on Android are listed in the hand-off summary (and the PR body if Sri opens one). If a feature is mostly
  AI-written, add it to the README's "Use of AI" section.
- **[should]** Verification is stated honestly: which Gradle task ran, on which variant, and whether it
  was tested on real AirPods or only in demo mode. There is no automated test suite.
