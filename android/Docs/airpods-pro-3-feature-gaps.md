# AirPods Pro 3: features LibrePods for Android doesn't have yet

Date: 2026-10-30. Branch read: `srikanth-pixel10-no-premium` at `9552f41`, plus the uncommitted changes in the local copy.
Also read: upstream `librepods-org/librepods` branch `android/rewrite` at `3d4d270` (2026-10-28), which has the heart rate and recorder work.

This builds on the earlier general list ("Missing AirPods features for LibrePods"). It only covers what matters for AirPods Pro 3 (A3063, A3064, A3065).

## No root

Everything in this doc works without root, Xposed or the root module. Two features from the first draft were dropped because they can only work with root:

- **Hi-res mic for other apps and calls.** Android has no way for an app to register a virtual microphone.
- **Head-tracked spatial audio in Android.** Android's Spatializer only accepts head tracking from a system sensor, and an app can't add one.

Without root, the app needs a phone that can open the AirPods connection itself: a Pixel on Android 16 QPR3 or later, ColorOS/OxygenOS 16, realme UI 7.0, or any phone on Android 17.

## Effort scale

| Size | Meaning |
| --- | --- |
| S | A day or less |
| M | 2 to 5 days |
| L | 1 to 3 weeks |
| XL | More than 3 weeks, or open-ended research |

Estimates assume the protocol is already known. Where reverse engineering (RE) is needed, the estimate says so separately. RE time is the least predictable part. It also needs an iPhone or Mac paired with the AirPods Pro 3 to capture traffic (PacketLogger on macOS, or a Bluetooth HCI log on iOS with a developer profile), read with the [apple-wireshark](https://github.com/pabloaul/apple-wireshark) dissector.

## Hurdle tags

- **RE**: the packets aren't known yet, or only partly known.
- **API**: Android doesn't give a normal app the API it would need.
- **Play**: Google Play policy makes it harder (declaration forms, sensitive permissions). The FOSS build isn't affected.
- **Upstream**: upstream already has working code, but in the `android/rewrite` architecture, so it needs porting.

## What the app already does on AirPods Pro 3

`AirPodsPro3` in `data/AirPods.kt` declares these capabilities, and each has UI:

- Listening modes (Off, Noise Cancellation, Transparency, Adaptive), adaptive strength, Off option
- Conversation Awareness, Personalized (adaptive) volume, Sleep detection
- Head gestures (also for incoming calls), press-and-hold actions including Google Assistant, stem press speeds
- Hearing Protection (PPE)
- Custom EQ, Camera control, call controls, microphone side, swipe for volume
- Case sounds toggle, Optimized charging, battery alerts, battery widget and notification
- Announce Notifications and Announce Calls (FOSS only), engraving shown on the popup (typed in by hand)
- Rename, ear detection, automatic connection

Hearing Aid, Transparency customization, Loud Sound Reduction and multipoint takeover with an iPhone are also in the app, but they need the VendorID hook, which needs root. Without root they won't work, and nothing in this doc depends on them.

`Capability.HRM` is declared for Pro 3, and the ViewModel's demo state sets `HRM_STATE`, but nothing reads heart rate and no screen uses it.

## Summary

| # | Feature | Hurdles | Effort |
| --- | --- | --- | --- |
| 1 | Heart rate sensor on/off toggle | None | S |
| 2 | Live heart rate + Health Connect | Upstream, Play | M |
| 3 | Workouts (exercise sessions, calories) | API, Play | M to L |
| 4 | Press to mute/unmute in calls | API | S to M |
| 5 | Auto-answer calls | Play (sensitive permission) | S |
| 6 | Head gestures to reply to announcements | None | S to M |
| 7 | Left-behind alert and last-seen location | Play (background location) | M |
| 8 | Hi-res microphone recording in the app | Upstream, API | M |
| 9 | Read the real engraving from the AirPods | RE | S to M after RE |
| 10 | Battery health and AppleCare/warranty info | RE | M after RE |
| 11 | Ear Tip Fit Test | RE | M to L after RE |
| 12 | Find My: play sound on the buds | RE | M after RE |
| 13 | Find My: play sound on the case, Precision Finding | RE, API | XL, probably not possible |
| 14 | Find My network (location via Apple) | API, Apple account | Not possible |
| 15 | Live Translation | RE, API | L |
| 16 | Firmware updates | RE, legal | Won't do |
| 17 | Hearing Test | Calibration, medical | Won't do |

Details for each are below.

---

## 1. Heart rate sensor on/off toggle

iOS has a Heart Rate switch in the AirPods settings. The command is known: `HRM_STATE` (`0x30`) in `AACPManager.ControlCommandIdentifiers`.

- **Work:** a toggle in the AirPods device settings screen gated on `Capability.HRM`, same pattern as the case sounds toggle.
- **Hurdles:** none. Worth doing first because #2 and #3 depend on the sensor being on.
- **Effort:** S.

## 2. Live heart rate and Health Connect

AirPods Pro 3 has a PPG heart rate sensor in each bud. Upstream's `android/rewrite` branch reads it, stores samples in Room, shows a chart (`HeartRateScreen`) and writes to Health Connect.

How upstream does it:

- Heart rate isn't a control command. It comes over the RTBuddy channel as a `SensorDataWX` protobuf message (`core/src/main/proto/rtbuddy.proto`, descriptor `SENSOR_DATA_WX = 0x00100000`).
- To start it, the app sends a `SensorServiceSetting` for service `HEARTRATE` (19), or `HEARTRATE_COMMAND` (84) when the firmware's `version3` starts with 9 or higher, with a report interval in microseconds. An interval of 0 stops it. See `RTBuddyManager.setSensorServiceReportInterval` and `AppleDevice.startHr()`.
- Readings come back as `HEARTRATE` / `HEARTRATEv2` sensor data. The BPM is `payload[1]`.

Hurdles:

- **Upstream:** the fork has no RTBuddy packet support, no protobuf, and no Room. Porting means adding the protobuf-lite Gradle plugin, an RTBuddy packet parser in `AACPManager.receivePacket`, and somewhere to store samples. `AirPodsService.kt` is already 3,400 lines, so put this in its own class (for example `bluetooth/RTBuddyManager.kt` and `services/HeartRateRecorder.kt`).
- **Play:** writing heart rate to Health Connect needs the `WRITE_HEART_RATE` permission, a Health Connect declaration in the Play Console, and a privacy policy that covers health data. FOSS builds just need the permission.
- Readings only arrive while at least one bud is in the ear, and keeping the sensor on costs battery. Stop it when ear detection says both buds are out.
- The firmware version switch (`HEARTRATE` vs `HEARTRATE_COMMAND`) is upstream's guess from captures. Test on Sri's firmware.

**Effort:** M (3 to 5 days) for live BPM on screen plus Health Connect writes. Add 1 to 2 days for a history chart.

## 3. Workouts (exercise sessions, calories)

On iPhone, Pro 3 can track a workout in the Fitness app, using heart rate plus the buds' motion sensors to estimate calories.

- **Work:** a "Start workout" action that turns on heart rate (#2), records an `ExerciseSessionRecord` plus heart rate series in Health Connect, and estimates active calories from heart rate, age and weight (a published formula such as Keytel et al.).
- **Hurdles:**
  - **API:** Apple does the calorie maths on the iPhone, not in the AirPods. We'd write our own estimate, and it won't match Apple's numbers.
  - Step and cadence data would need the motion sensor services (`ACTIVITY`, `CMA`, `DEVMOTION6` in the proto). Upstream uses those only for head tracking so far. Treat steps as RE.
  - **Play:** more Health Connect permissions to declare (`WRITE_EXERCISE`, `WRITE_ACTIVE_CALORIES_BURNED`). A foreground service of type `health` is needed to keep recording with the screen off.
- **Effort:** M without steps, L with steps.

## 4. Press to mute/unmute in calls

iOS lets you press the stem to mute and unmute during a call. The config command is known: `SOFTWARE_MUTE_CONFIG` (`0x27`), no UI today.

- **Hurdles:**
  - **API:** turning it on makes the AirPods send a stem press instead of acting by themselves, so the phone has to mute the call. `AudioManager.setMicrophoneMute()` is ignored for calls on recent Android unless the app owns the call. The fix is an `InCallService` or `Connection` API, which a normal app can't use for the dialer's calls. An accessibility service that taps the mute button is possible but fragile.
  - Check first whether the AirPods mute their own mic when the config is on. If they do, this is just a toggle (S).
- **Effort:** S if the AirPods do the muting, M if the phone has to.

## 5. Auto-answer calls

`AUTO_ANSWER_MODE` (`0x1E`) is read but not shown. On iPhone, auto-answer is done by the phone, not the AirPods.

- **Work:** a setting that answers a ringing call after a delay when the AirPods are in the ear, using `TelecomManager.acceptRingingCall()`.
- **Hurdles:** **Play:** needs `ANSWER_PHONE_CALLS`, a sensitive permission that Play reviews. The app already watches call state for head gestures, so the plumbing exists.
- **Effort:** S.

## 6. Head gestures to reply to announcements

The fork already announces notifications and calls (FOSS only), and already uses head gestures for incoming calls. iOS also lets you nod or shake to reply to or dismiss an announced message.

- **Work:** after an announcement, start `GestureDetector` for a few seconds. Nod opens a voice reply through the notification's `RemoteInput` with `SpeechRecognizer`. Shake dismisses it.
- **Hurdles:** none new. `SpeechRecognizer` needs the mic, which on Android means switching the AirPods to the headset profile (lower audio quality for a few seconds). #8 would avoid that.
- **Effort:** S for dismiss, M with spoken replies.

## 7. Left-behind alert and last-seen location

iOS warns you when you leave AirPods behind. That uses the Find My network, but a local version works without it.

- **Work:** when the AACP connection drops and the BLE scan no longer sees the AirPods for a minute or two, while the phone is moving, post an alert with the last-seen location. Keep the last location on a small map or with a "Navigate" button.
- **Hurdles:** **Play:** needs background location (`ACCESS_BACKGROUND_LOCATION`), which Play reviews closely and asks you to justify with a video. Asking for location only at disconnect time with a foreground service is an easier sell. Exclude home Wi-Fi to avoid false alarms.
- **Effort:** M.

## 8. Hi-res microphone recording in the app

iPhones get a high quality mono mic stream (AAC-ELD) from the AirPods over AACP while music keeps playing over A2DP. On Android today, using the AirPods mic switches to the headset profile and drops playback to call quality.

Upstream `android/rewrite` has a work-in-progress recorder:

- `AACPManager.requestMicrophoneStream()` / `endMicrophoneStream()` use opcode `MICROPHONE_STREAM` (`0x58`). Frames arrive as `MicrophoneFrame`s.
- `audio/EldDecoder.kt` decodes them with Android's `MediaCodec` AAC-ELD decoder (config `F8 E6 30 00`, 64 kbps, mono). `WavWriter` saves them, and `RecordingScreen` lists recordings.

Hurdles:

- **Upstream:** it's marked work in progress, and the service-side calls are commented out in upstream's `AirPodsService.kt`. Expect to finish it, not just port it.
- **API:** the AAC-ELD decoder is in Android's software codec (`c2.android.aac.decoder`), so it should work on Pixels, but some OEM builds may lack ELD support.
- Bluetooth bandwidth: the mic stream shares the link with A2DP. Watch for dropouts at high A2DP bitrates.

**Effort:** M (about a week) for an in-app recorder with play, share and delete.

## 9. Read the real engraving from the AirPods

The fork asks the user to type the engraving (`data/Engraving.kt` says the AirPods don't send it). Upstream's `RTBuddyDescriptor` has `DIGITAL_ENGRAVING_INFO = 0x00800000`, which suggests the AirPods do send it over RTBuddy.

- **Hurdles:** **RE:** upstream names the descriptor but doesn't parse it. Needs a capture of the payload. It also depends on the RTBuddy parser from #2.
- **Effort:** RE 1 to 3 days with a capture. Then S. Keep manual entry as the fallback.

## 10. Battery health and AppleCare/warranty info

iOS shows battery condition for each bud and the case, and warranty coverage. Upstream lists `APPLECARE` (opcode `0x64`) and an RTBuddy `BATTERY_HEALTH` descriptor, but doesn't use either.

- **Hurdles:** **RE:** payload format unknown.
- **Effort:** RE about a week. Then M for UI.

## 11. Ear Tip Fit Test

Pro 3 ships with five tip sizes (XXS to L). iOS plays a short test sound and uses the inner mics to check the seal on each ear. Upstream's capability list has `EAR_TIP_FIT_TEST` (`0x17`), so the AirPods advertise it.

- **Hurdles:**
  - **RE:** the start command and result format are unknown. It's probably on the RTBuddy `ACOUSTIC` channel.
  - The test sound is played by the iPhone. We'd need to know whether the AirPods expect a specific signal. We can't ship Apple's audio file, so we'd generate our own.
- **Effort:** RE 1 to 2 weeks. Then M for the flow and per-ear result UI.

## 12. Find My: play sound on the buds

When the AirPods are connected but lost nearby (under a cushion), iOS plays a loud chirp on each bud.

- **Hurdles:**
  - **RE:** no known opcode. Capture "Play Sound" from Find My on an iPhone while the AirPods are connected.
  - Only works while Bluetooth is connected, so it covers "lost in the room", not "lost outside".
- **Effort:** RE up to a week. Then S to M (a button, plus a per-bud choice).

## 13. Find My: play sound on the case, Precision Finding

The Pro 3 case has a speaker, its own Bluetooth, and an Ultra Wideband (U2) chip.

- **Hurdles:**
  - **RE / API:** with the buds in the case and the lid closed, the case talks Apple's Find My accessory protocol, which uses keys made when the AirPods were added to an Apple ID. We don't have those keys, so we can't ask the case to play a sound unless an AACP command reaches it through the buds. That's unknown.
  - **API:** Precision Finding uses Apple's Nearby Interaction UWB protocol. Android's UWB API (`androidx.core.uwb`) only speaks the FiRa standard, and Apple doesn't expose its AirPods UWB to non-Apple devices.
- **Effort:** XL research, probably not possible. If the buds can relay a command to the case, the case sound becomes M.

## 14. Find My network

Adding AirPods to Find My, seeing their location via other people's Apple devices, and "Notify when left behind" through iCloud.

- **Hurdles:** needs an Apple ID, Apple's servers and Apple's key pairing. Reading reports for your own AirPods is possible (OpenHaystack-style tools do it), but only with keys exported from an Apple device and with an Apple account login, which is against Apple's terms and fragile.
- **Effort:** not possible in a reasonable way. #7 is the practical substitute.

## 15. Live Translation

iOS 26 translates a conversation: the iPhone does the speech recognition and translation, the AirPods turn down the other person's voice and play the translation. Upstream lists opcode `PERSONAL_TRANSLATION` (`0x60`).

- **Work:** on-device speech recognition from the AirPods mic, ML Kit on-device translation, and text-to-speech to the AirPods, plus a transcript on screen for the other person.
- **Hurdles:**
  - **RE:** what `0x60` does (probably switching the AirPods into a mode that ducks the other speaker, and maybe the "press both stems" trigger).
  - **API:** without #8, the mic forces the headset profile, so quality drops. Android's on-device recognizer and ML Kit cover fewer languages than Apple and are less accurate.
- **Effort:** L (2 to 3 weeks) for a usable version without the `0x60` mode. Add RE time for the native mode.

## 16. Firmware updates

Opcode `UARP_DATA` (`0x4F`) is Apple's update protocol.

- **Hurdles:** Apple's firmware can't be redistributed, the protocol isn't documented, and a failed update can brick the AirPods.
- **Effort:** won't do. Showing the installed firmware version already works.

## 17. Hearing Test

Pro 3 supports Apple's clinical hearing test (capability `HEARING_TEST`).

- **Hurdles:** it needs calibrated tones and a validated method. Upstream decided not to do it. The app already accepts an existing audiogram for the Hearing Aid.
- **Effort:** won't do.

---

## Suggested order

1. **#1 heart rate toggle** (S), then **#2 heart rate + Health Connect** (M). This is the main thing Pro 3 has that older models don't, and upstream has already done the RE.
2. **#5 auto-answer** (S), **#6 announcement gestures** (S to M), **#4 press to mute** (S to M). Known commands or existing code.
3. **#7 left-behind alert** (M). No RE needed, and useful every day.
4. **#8 in-app recorder** (M). Port and finish upstream's work.
5. **#9 engraving**, **#12 play sound**, **#11 fit test**, **#10 battery health**. These need an iPhone or Mac for captures. Do them together in one RE session.
6. **#3 workouts** and **#15 Live Translation** once heart rate and the recorder exist.

## A note on upstream

Upstream's `android/rewrite` is a full restructure (a `core` module, Room database, `AppleDevice` / `AppleState` flows). Porting #2 and #8 into this fork's current structure is doable, but each later feature will be a fresh port. If the rewrite lands on upstream `main` soon, rebasing the fork's own features (case sounds, battery alerts, announcements, engraving, Google Assistant press-and-hold) onto it may be less total work than porting rewrite features one at a time. The `sync-upstream` skill is the place to start that.
