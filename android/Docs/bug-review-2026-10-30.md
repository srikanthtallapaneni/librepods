# Bug review: recent LibrePods work (2026-10-30)

Scope: the last six commits on `srikanth-pixel10-no-premium`:

- `f247dfe` engraving + digital assistant
- `e04e7dd` popup engraving animation
- `30b88e5` listening-mode toggles in the notification
- `1d44986` island styling + head gestures during calls
- `904a510` split settings, case sounds, battery alerts, Announce Notifications/Calls

The review also covers nearby code those commits touch. Line numbers refer to the files as they are at `904a510`.

Not done: no Gradle build or device test was run (the review shell has no Android SDK), and nothing was fixed.

| Severity | Count |
| --- | --- |
| Critical | 1 |
| High | 2 |
| Medium | 8 |
| Low | 10 |

---

## Critical

### C1. Personal AirPods data committed and pushed in `android/engraving.txt`
- **Where:** `android/engraving.txt` (added in `f247dfe`, pushed to `origin/srikanth-pixel10-no-premium`).
- **Problem:** The file is a raw `AACPDump` logcat capture. Line 14 is the device-information packet (opcode `0x1D`). It contains the AirPods name ("Sri…"), the model/firmware strings and the serial numbers in plain ASCII, and the rest of the capture identifies the device. The file is public on the fork and is not used by the app.
- **Fix:**
  1. `git rm --cached android/engraving.txt`.
  2. Add `android/*.txt` or a `logs/` rule to `.gitignore`.
  3. Commit the removal.
  4. Because it is already pushed, the data stays in history. Rewrite the branch with `git filter-repo --path android/engraving.txt --invert-paths` and force-push, but only if nobody else has based work on it.
  5. Consider the serials exposed.

---

## High

### H1. Announce Notifications/Calls can read private messages out of the phone speaker
- **Where:** `services/AirPodsService.kt:3056-3060` (`announce`), `utils/Announcer.kt:96-99`.
- **Problem:** `announce()` only checks that the AACP socket is up, that a bud reports "in ear" and that no call is active. It never checks that the phone's audio actually goes to the AirPods. The AACP link stays up while audio is elsewhere: after another Apple device takes over (`otherDeviceTookOver` / `disconnectAudio`), when `disconnectedBecauseReversed` is set, or when A2DP hasn't connected yet. In any of those cases TTS speaks message text and sender names through the phone speaker.
- **Fix:**
  - In `announce()`, return early unless the AirPods are the active media output. For example, require `!otherDeviceTookOver && !disconnectedBecauseReversed` and an `AudioDeviceInfo.TYPE_BLUETOOTH_A2DP` or `TYPE_BLE_HEADSET` output in `audioManager.getDevices(GET_DEVICES_OUTPUTS)` whose address matches `device`.
  - Optionally re-check this inside `Announcer.speakNow()`, since the route can change while an utterance is queued.

### H2. The digital assistant can leave the AirPods in call-quality (SCO) audio for up to 5 minutes
- **Where:** `bluetooth/VoiceRecognitionManager.kt:76-88` and `:96-122`, `services/AirPodsService.kt:1243-1262`.
- **Problem 1:** `onRecordingConfigChanged` treats any non-hotword recording as "the assistant is listening". If another app is already recording when Press and Hold fires, `assistantRecording` turns true at once and SCO is only released when that app stops or after `MAX_SESSION_MS` (5 min). Examples: a screen recorder, a VoIP app, keyboard voice typing. Music plays in HFP quality for that whole time.
- **Problem 2:** `launchDigitalAssistant()` calls `voiceRecognitionManager.start()` before it knows the assistant can open. Without "Display over other apps", Android silently blocks the background activity start, so no exception is thrown. SCO stays open for the 10 s `NO_RECORDING_TIMEOUT_MS` with nothing listening.
- **Fix:**
  - Take a snapshot of the active recording configs (`audioManager.activeRecordingConfigurations`) in `start()`. Only count recordings whose `clientAudioSessionId` wasn't already active, or only count sources `VOICE_RECOGNITION` and `VOICE_COMMUNICATION`.
  - Skip `start()` entirely when `!Settings.canDrawOverlays(this)`, since the launch will be blocked anyway.

---

## Medium

### M1. The ongoing notification is rebuilt on every received AACP packet
- **Where:** `services/AirPodsService.kt:2904-2910` (reader loop), and the builder at `:2122-2196` (from `30b88e5`).
- **Problem:**
  - `updateNotificationContent()` runs for every packet, including the ~25 Hz head-tracking stream.
  - Since `30b88e5`, each call builds a `RemoteViews`, creates 4 `PendingIntent`s and calls `notify()`. Android rate-limits notification updates per app, so updates get dropped (the notification can show a stale mode or battery) and CPU and battery use goes up during head tracking and gesture detection.
  - The battery and listening-mode callbacks (`:870-886`, `:941-953`) already refresh the notification, so the call in the loop is redundant.
- **Fix:** Remove the call from the reader loop. If something is still missing, only refresh when the battery, mode or name actually changed (keep the last rendered values and compare).

### M2. `AACPManager.sendPacket` reports a control command as applied before it is sent
- **Where:** `bluetooth/AACPManager.kt:1160-1174` (the `callback?.onControlCommandReceived(packet)` added in `30b88e5`).
- **Problem:**
  - The status list, the per-ID listeners and now the service callback are all updated before the socket check and write.
  - If `aacpSocket` is null or disconnected, or the write throws, the function returns `false`. By then the ViewModel, widget and notification already show the new listening mode (or case sounds, etc.) although the AirPods never changed.
- **Fix:** Write to the socket first. Update the status and fire the callbacks only when the write succeeded, or roll them back on failure.

### M3. `controlCommandStatusList` is mutated from two threads
- **Where:** `bluetooth/AACPManager.kt:179-180`, `:214-229`.
- **Problem:**
  - It is a plain `mutableListOf`. Sends come from the main thread (ViewModel → `ControlCommandRepository.setValue`) and receives from the AACP reader thread. Both remove and add entries.
  - `30b88e5` changed the guard to `if (existingStatus != null)`, so a remove now happens on every update, which makes overlapping access more likely. A `ConcurrentModificationException` during `find {}` on the reader thread would kill packet handling for the session.
- **Fix:** Guard the list with `synchronized(controlCommandStatusList)` in `getControlCommandStatus` and `setControlCommandStatusValue`, or replace it with a `ConcurrentHashMap<ControlCommandIdentifiers, ByteArray>`.

### M4. Announce Notifications reads out your own replies
- **Where:** `services/NotificationAnnouncerService.kt:75-89`.
- **Problem:**
  - In `MessagingStyle`, a message whose `person` is null was sent by the device user. When you reply from the notification (or on another device), the app reposts the notification with your reply as the last message.
  - `sender` is then null, so the code falls through to `conversation` or `body` and speaks "Message from <chat>. <your own text>".
- **Fix:**
  - Skip announcing when `message.person == null` or `message.person?.key == style.user.key`.
  - Also skip reposts that have `Notification.FLAG_ONLY_ALERT_ONCE` set and no new incoming message.

### M5. `Announcer` audio-focus race and an unbounded queue when TTS fails
- **Where:** `utils/Announcer.kt:48-57`, `:82-89`, `:96-106`.
- **Problem 1:** `stop()` sets `activeUtterances` to 0 and abandons focus. The engine then still delivers `onStop` for the interrupted utterance on a binder thread. If a new announcement started in between, that late `onStop` drives the counter to 0 and abandons focus mid-sentence, so music un-ducks over the announcement.
- **Problem 2:** If TTS init fails, `ready` never becomes true. `pending` then grows forever and nothing is ever spoken, because the instance is cached in `AirPodsService.announcer` and never recreated.
- **Fix:**
  - Track the utterance IDs that are actually in flight (a `Set<String>`), and ignore callbacks for IDs that were already cleared by `stop()`.
  - On init failure, clear `pending`, mark the instance failed and have `announce()` drop it so the next call retries.

### M6. The Announce toggles stay on without notification access
- **Where:** `presentation/screens/AirPodsDeviceSettingsScreen.kt:170-188`, `:197-207`.
- **Problem 1:** The pref is saved as on before access is granted. If you back out of the system screen, or access is revoked later, the toggle still shows on and nothing is announced.
- **Problem 2:** The fallback `startActivity(ACTION_NOTIFICATION_LISTENER_SETTINGS)` at `:206` is not in a try, so on a ROM without that screen tapping the toggle crashes the app.
- **Fix:**
  - Derive the checked state as `pref && NotificationManagerCompat.getEnabledListenerPackages(context).contains(packageName)`, and re-check it in an `ON_RESUME` lifecycle effect.
  - Wrap the fallback in `try`/`catch`.

### M7. Head gestures during calls mix raw and smoothed samples
- **Where:** `utils/GestureDetector.kt:57-58`, `:186-193`.
- **Problem:**
  - `slowSamples` is decided per sample, so ordinary Bluetooth jitter during the 25 Hz stream (one gap over 100 ms) inserts a single raw value between smoothed ones. That creates an artificial peak, which can count toward a false nod or shake: accepting or declining a call by mistake.
  - `lastSampleAt` is in the companion object, so it is shared by every `GestureDetector` instance.
- **Fix:**
  - Decide the rate once over a window (for example the median interval of the last 5 samples), and switch modes only after it has been stable for a few samples.
  - Clear `horizontalBuffer`/`verticalBuffer` when switching modes.
  - Move `lastSampleAt` to an instance field.

### M8. `VoiceRecognitionManager` state is touched from two threads
- **Where:** `bluetooth/VoiceRecognitionManager.kt:57-59`, `:96-137`.
- **Problem:** `start()` and `stop()` run on the AACP reader thread (from `executeStemAction`). `recordingCallback` and `stopRunnable` run on the main `handler`. `activeDevice` and `assistantRecording` are plain fields, so a stop from one thread can race a start from the other, leaving the callback registered or SCO open.
- **Fix:** Post `start()`/`stop()` onto `handler` (or mark the methods `@MainThread` and call them via `Handler(mainLooper).post`).

---

## Low

### L1. Case sounds shows "on" before the AirPods report a value
- **Where:** `presentation/components/AirPodsDeviceSettings.kt:230-231`.
- **Problem:** `checked = value != 0x02` is true while the value is still unknown (null).
- **Fix:** Use `== 0x01.toByte()`, the same as the other toggles.

### L2. The notification hides the "Off" mode on models with Adaptive
- **Where:** `services/AirPodsService.kt:2151-2152`.
- **Problem:** When the user has enabled "Off" in listening modes (`ALLOW_OFF_OPTION`), or the current mode is Off (1), no button is highlighted and Off can't be picked from the notification.
- **Fix:** Show Off when `ALLOW_OFF_OPTION == 0x01` or `ancNotification.status == 1`, and/or follow `LISTENING_MODE_CONFIGS`.

### L3. The "case fully charged" alert rarely or never fires, and alerts repeat after a service restart
- **Where:** `services/BatteryAlertManager.kt:51-67`, `:93-106`.
- **Problem 1:** The case level is only reported while a bud is in the case and the AirPods are connected. With optimized charging the case often stops below 100. The class comment promises "while the buds are out", but nothing implements that.
- **Problem 2:** `alertedThreshold` lives in memory, so each service restart re-alerts a battery that is already low.
- **Fix:**
  - Treat ≥ 80% with `OPTIMIZED_CHARGING`/`NOT_CHARGING` after charging as "done", or drop the case-full alert and update the description string.
  - Persist the last alerted threshold per component in prefs.

### L4. Redundant pref write and hard-coded labels in the conversation-awareness slider
- **Where:** `presentation/screens/AirPodsDeviceSettingsScreen.kt:143-153` (moved from `AppSettingsScreen`).
- **Problem:** The `LaunchedEffect` writes the value it just read back to prefs on every change, and `"10%"`/`"85%"` are hard-coded UI text (CLAUDE.md: no hard-coded UI strings).
- **Fix:** Remove the `LaunchedEffect`, and use string resources such as `stringResource(R.string.percent_value, 10)`.

### L5. The AirPods Settings screen stays editable after the AirPods disconnect
- **Where:** `presentation/navigation/AppNavGraph.kt` (`Screen.AirPodsDeviceSettings` entry), `AirPodsDeviceSettingsScreen.kt:87-195`.
- **Problem:** The home screen only links to it while `state.isLocallyConnected`, but if the connection drops while you are on it, the toggles keep working against the optimistic state (see M2).
- **Fix:** When `!state.isLocallyConnected`, show the disconnected state or pop back to home.

### L6. Engraving has no length limit, isn't stored per device, and the popup always uses the Pro 2 geometry
- **Where:** `presentation/screens/EngravingScreen.kt:73-85`, `viewmodel/AirPodsViewModel.kt` `setEngraving`, `presentation/overlays/PopupWindow.kt` (geometry constants and `startEngravingSync`).
- **Problem:**
  - Long text just shrinks until it is unreadable.
  - The single `engraving` pref is shown for every paired AirPods.
  - The popup overlay is tuned for the one `R.raw.connected` video.
- **Fix:**
  - Cap the input (Apple allows about 2 lines of ~15 characters).
  - Key the pref by MAC address (`engraving_<mac>`).
  - Hide the popup overlay for models whose case doesn't match the video.

### L7. Island open and close springs can fight each other
- **Where:** `presentation/overlays/IslandWindow.kt:436-443` and `:730-741`.
- **Problem:** The open `SpringAnimation`s aren't kept, so a close that starts during the open animation runs a second pair of springs on the same properties and the island jitters before it disappears.
- **Fix:** Keep references to the open springs and `cancel()` them at the start of `close()`.

### L8. `announcer` is shared across threads without `@Volatile`
- **Where:** `services/AirPodsService.kt:3050`, used at `:630` (main executor), `:1312` (AACP reader thread) and `:3058` (main).
- **Problem:** The reader thread may not see an `Announcer` created on main, so it won't stop it when the buds come out.
- **Fix:** Mark the field `@Volatile`, or post `stop()` to the main thread.

### L9. A personal JDK path is committed in `android/gradle.properties`
- **Where:** `android/gradle.properties:10` (changed in `f247dfe`).
- **Problem:** `org.gradle.java.home=/opt/homebrew/...` fails the build on any machine without that path, including CI (`ci-android.yml`) unless it overrides it.
- **Fix:** Move it to `~/.gradle/gradle.properties` on the Mac and keep it out of the repo.

### L10. Hard-coded notification text, and Play policy for the notification listener
- **Where:** `services/AirPodsService.kt` around `:2125-2135` (`"L:"`, `"R:"`, `"Case:"`), and `AndroidManifest.xml` (`NotificationAnnouncerService`).
- **Problem:** The labels aren't localisable. The listener ships in the Play flavor too, where Google Play requires a declared, justified use of notification access.
- **Fix:** Move the labels to `strings.xml`. Either declare the use in the Play Console or move the service into a `src/foss/AndroidManifest.xml` until the feature is ready for Play.
