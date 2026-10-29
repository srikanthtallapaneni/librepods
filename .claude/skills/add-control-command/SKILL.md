---
name: add-control-command
description: Wire a new (or newly discovered) AACP control command or opcode end to end in the Android app, from AACPManager through the ViewModel to a settings toggle/slider. Use when adding support for an AirPods setting.
---

# Add an AACP control command

Control commands are AACP opcode `0x09` packets:
`04 00 04 00 09 00 [identifier] [data1] [data2] [data3] [data4]` (see `docs/control_commands.md`).
Booleans are `0x01` = on, `0x02` = off.

Paths below are relative to `android/app/src/main/java/me/kavishdevar/librepods/`.

1. **Identifier**: add the entry to `AACPManager.Companion.ControlCommandIdentifiers` in
   `bluetooth/AACPManager.kt` with the exact byte, e.g. `NEW_FEATURE_CONFIG(0x3C)`. Add a short
   comment if the meaning is a guess. (A brand-new *opcode* instead goes in `Opcodes`, with a
   `create…Packet()` builder, a `send…()` wrapper and a branch in `receivePacket()` that size-checks the
   packet before reading it and forwards it through `PacketCallback`.)
2. **Docs**: add the identifier row to `docs/control_commands.md` (and payload notes if known).
3. **Capability (if model-specific)**: add a value to `Capability` in `data/AirPods.kt` and list it on
   each supporting model's `capabilities`.
4. **Observe it**: add the identifier to `identifiersList` in
   `AirPodsViewModel.observeAACP()` so `state.controlStates[ID]` updates when the AirPods report it.
   If it also needs a dedicated field in `AirPodsUiState`, set it in `observeControl()` like
   `DYNAMIC_END_OF_CHARGE`.
5. **Write it**: from UI call the existing setters on `AirPodsViewModel`:
   `setControlCommandBoolean`, `setControlCommandInt`, `setControlCommandByte` or
   `setControlCommandValue`. They already skip the device write in demo mode. Add a new setter only
   if the encoding is unusual, and keep the `if (!isDemoMode)` guard.
6. **UI**: in the right screen under `presentation/screens/`, gated by capability, e.g.

   ```kotlin
   if (state.capabilities.contains(Capability.NEW_FEATURE)) {
       StyledToggle(
           label = stringResource(R.string.new_feature),
           description = stringResource(R.string.new_feature_description),
           checked = state.controlStates[ControlCommandIdentifiers.NEW_FEATURE_CONFIG]
               ?.getOrNull(0) == 0x01.toByte(),
           onCheckedChange = {
               viewModel.setControlCommandBoolean(ControlCommandIdentifiers.NEW_FEATURE_CONFIG, it)
           }
       )
   }
   ```
7. **Strings**: add the keys with the `add-strings` skill.
8. **Demo mode**: if the setting should show in demo mode, give it a value in `demoState`
   (`AirPodsViewModel.kt`).
9. **Linux (optional)**: the legacy Qt app defines packets in `linux/airpods_packets.h`; only touch it
   if asked.
10. Build with the `build-android` skill and run the `code-reviewer` agent. Leave everything
    uncommitted for Sri to review.
