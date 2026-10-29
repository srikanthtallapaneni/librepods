---
name: add-airpods-model
description: Add support for a new AirPods model (model numbers, images, capabilities, BLE model ID) to the LibrePods Android app. Use when a new AirPods generation or variant must be recognised.
---

# Add an AirPods model

1. **Model class** in `android/app/src/main/java/me/kavishdevar/librepods/data/AirPods.kt`:
   ```kotlin
   class AirPodsX: AirPodsBase(
       modelNumber = listOf("A1234", "A1235"),   // every A-number (left, right, case variants)
       name = "AirPods X",
       displayName = "AirPods",                   // what the UI shows; defaults to "AirPods"
       budsRes = R.drawable.airpods_x_buds,
       leftBudsRes = R.drawable.airpods_x_left,
       rightBudsRes = R.drawable.airpods_x_right,
       caseRes = R.drawable.airpods_x_case,
       capabilities = setOf(Capability.LISTENING_MODE, /* … */)
   )
   ```
   Base the capability set on the closest existing model and only add what is confirmed.
2. **Register** it in `AirPodsModels.models` (same file). Lookup is by A-number via
   `getModelByModelNumber`.
3. **Images**: four PNGs in `android/app/src/main/res-apple/drawable/` named
   `airpods_x_buds.png`, `airpods_x_left.png`, `airpods_x_right.png`, `airpods_x_case.png`.
   If no art exists yet, reuse the closest model's drawables and say so when reporting back.
4. **BLE model ID**: if the proximity advert uses a new model ID, add it to `modelNames` in
   `bluetooth/BLEManager.kt` (hex, e.g. `0x2720 to "AirPods X"`).
5. **Linux (optional)**: the legacy app has its own model handling in `linux/`; only change it when asked.
6. Build with `build-android` and run the `code-reviewer` agent. Leave the changes uncommitted for
   Sri to review; suggest the commit subject `android: add AirPods X`.
