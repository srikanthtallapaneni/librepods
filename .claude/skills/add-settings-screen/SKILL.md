---
name: add-settings-screen
description: Add a new Compose screen/destination to the LibrePods Android app (Screen.kt, AppNavGraph, NavigationRoot title, entry point) using the shared Styled components. Use when a feature needs its own page.
---

# Add a settings screen

Paths are under `android/app/src/main/java/me/kavishdevar/librepods/presentation/`.

1. **Destination**: in `navigation/Screen.kt` add
   ```kotlin
   @Serializable
   data object MyFeature: Screen
   ```
   Use `data class MyFeature(val arg: String): Screen` if it needs an argument (see `LongPress`).
2. **Screen composable**: create `screens/MyFeatureScreen.kt` with the GPLv3 header (copy from any
   existing screen). Signature pattern:
   ```kotlin
   @Composable
   fun MyFeatureScreen(viewModel: AirPodsViewModel /*, navigateToX: () -> Unit */) {
       val state by viewModel.uiState.collectAsState()
       ...
   }
   ```
   - Build content from `components/StyledList`, `StyledListItem`, `StyledToggle`, `StyledSlider`,
     etc. so both design systems render. Check `LocalDesignSystem.current == DesignSystem.Material`
     only when spacing differs (see `RenameScreen` top/bottom padding).
   - Read device values from `state`, write through ViewModel setters. No direct `AACPManager` or
     service calls.
   - Every visible string is a `stringResource`.
3. **Route**: in `navigation/AppNavGraph.kt` add a branch to the `when (screen)`:
   ```kotlin
   Screen.MyFeature ->
       NavEntry(screen) {
           if (!airPodsViewModel.isReady) LoadingScreen()
           MyFeatureScreen(airPodsViewModel)
       }
   ```
4. **Title**: add `Screen.MyFeature -> stringResource(R.string.my_feature)` to the `title` `when` in
   `navigation/NavigationRoot.kt` (the `when` is exhaustive, so the build fails if you forget).
5. **Entry point**: add a `navigateToMyFeature: () -> Unit` parameter to the parent screen (usually
   `AirPodsSettingsScreen` / `AirPodsSettingsRoute`), pass `{ navigate(Screen.MyFeature) }` from
   `AppNavGraph`, and call it from a `StyledListItem(onClick = …)`.
6. Strings via `add-strings`, build via `build-android`, then run the `code-reviewer` agent. Leave the
   changes uncommitted for Sri to review.
