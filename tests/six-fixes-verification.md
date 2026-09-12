# Six fixes: implementation and verification

## Files changed in this task

Production: `app/src/main/assets/index.html`; and these files under `app/src/main/java/com/darood/app/`: `AppSettings.java`, `MainActivity.java`, `LanguageManager.java`, `DuroodAudioPlayer.java`, `HijriCoordinator.java`, `HijriCache.java`, `HijriDayData.java`, `HijriSunsetScheduler.java`, `HijriDay29Notification.java`, `BootReceiver.java`.

Tests: updated `HijriRefreshTest.java`, `pronunciation-player.test.ps1`, `durood-ui.test.cjs`, and `salam-ui.test.cjs`; added `first-launch.test.ps1`, `setup-ui.test.cjs`, and this report. Earlier reminder-alarm changes remain in the working tree. The new audio files were supplied by the user; none were edited or copied by this task.

## Hijri date

The current coordinator was scheduling sunset events only on days 29/30. Ordinary dates were resolved independently from Gregorian-date cache/fallback records, and an explicit branch kept a manual correction unchanged across ordinary sunset. That prevented the corrected date from serving as the base for successive increments. The existing HTTP call was already intended to be gated to day 29; the missing daily local transition path was the central defect.

The existing coordinator now advances a persisted effective date at every local sunset. `HijriCache` stores the effective applicable date, source, manual-correction signature, and bounded effective-date history alongside its existing raw API cache. This derived history is separate from raw API records and Room corrections. The next applicable date identifies which sunset boundaries have already been processed. Snapshot calculations remain offline; the serialized reconciliation path commits transitions and schedules the next daily sunset event.

Normal transitions call `HijriDayData.nextLocalDay`, never HTTP. Day 30 enters day 1 of the next month, including Dhul Hijjah → Muharram/year rollover. Only an outgoing final effective day 29 can claim the existing persistent API-event key and request the incoming date. Incoming manual overrides take priority; valid API/cache dates may choose day 30 or day 1 of the next month. Unavailable/conflicting data uses the existing conservative month-end fallback.

Room manual records remain untouched. A changed correction signature invalidates derived state and uses the latest applicable manual date as the base. Later ordinary sunsets increment from that base. The day-29 decision therefore follows the manual/effective date rather than raw API data. An incoming correction added while HTTP is in flight is still rechecked and wins.

Recovery walks missed applicable dates in order, committing progress at each boundary. Repeated receiver/foreground/restart work cannot advance an already current date or repeat an API event. The existing Check Hijri Date notification gains a validated boundary-recovery entry point and persistent handled-date set; its normal notification builder/channel are retained. The New Hijri Month notifier is unchanged and continues deduplicating month notifications. Boot, clock/timezone, exact-access, foreground, and location-refresh paths reuse the coordinator. Cached sunset times from a different location are not reused after a location change.

## First launch and language

Setup visibility previously depended solely on a native bridge call at the very end of the page bootstrap, after content and settings initialization. Also, an absent saved language explicitly followed the device locale. Setup now has an explicit native-rendered visibility state and is initialized before content rendering.

`AppSettings.initializeLaunchPreferences` runs before the activity resolves localized resources. With no completion flag and no prior language, it writes `setup_completed=false` and `app_language=en`. Existing explicit completion flags are honored. An older user with a saved language but no completion flag is treated as an existing configured user. English initialization never replaces an existing language. `LanguageManager` also resolves an absent language to English, including before the main activity runs.

Selecting another language in setup saves the current font/size/theme selections and uses the existing activity-recreation language flow immediately. The completion flag remains false until Done. Done saves the options, marks completion, and recreates into Home. Preference/language tests cover English/Bangla/Urdu device locales, unfinished setup recreation, completed relaunch, legacy upgrade, saved language preservation, and Urdu layout direction. These are source-level tests, not a physical fresh-install reproduction.

## Salam design and shared playback

Salam already used the working Durood control/action classes and layout. The remaining Salam-only pronunciation icon overrides made its idle appearance differ. They were removed; both now use the current Durood idle design and one shared playing-state Stop icon. All dimensions, spacing, responsive rules, colors, typography, Copy/Share/Read emphasis, and expansion behavior continue coming from the same existing styles. A test renders the same content through both card renderers and compares the normalized markup exactly.

The existing single `DuroodAudioPlayer` resource arrays were extended directly: Durood IDs 1–15 map to their matching `R.raw.duroodN`, and Salam IDs 1–10 map to `R.raw.salamN`. No second player was created. Existing replacement, preparation/completion/error callbacks, stop/release, stale-callback rejection, and lifecycle cleanup remain intact. A shared playing-state publication resets both card types. Pronunciation does not call Read/activity persistence. Durood 16–25 and Salam 11–15 retain textual pronunciation without raw-resource references.

## Audio files actually found

All files below exist under `app/src/main/res/raw/`. All are nonempty. Their before/after SHA-256 hashes match; none were renamed, copied, re-encoded, compressed, or otherwise modified by this task.

| Durood files | Salam files |
|---|---|
| durood1.m4a | salam1.m4a |
| durood2.m4a | salam2.m4a |
| durood3.m4a | salam3.m4a |
| durood4.m4a | salam4.m4a |
| durood5.m4a | salam5.m4a |
| durood6.m4a | salam6.m4a |
| durood7.m4a | salam7.m4a |
| durood8.m4a | salam8.m4a |
| durood9.m4a | salam9.m4a |
| durood10.m4a | salam10.m4a |
| durood11.m4a | |
| durood12.m4a | |
| durood13.m4a | |
| durood14.m4a | |
| durood15.m4a | |

Missing expected files: **none**. Filename/resource mapping is verified; spoken recording content has not been listened to on a device.

## Tests actually executed

| Suite | Passed checks |
|---|---:|
| Hijri refresh/transition (real coordinator/cache/schedulers, Android/Room/API doubles) | 86 |
| First-launch preferences and LanguageManager (Android doubles) | 20 |
| Setup JavaScript (DOM/native bridge doubles) | 11 |
| Shared pronunciation player (MediaPlayer/resources doubles, every mapped ID) | 159 |
| Durood UI, English/Bangla/Urdu | 438 |
| Salam UI, English/Bangla/Urdu, including Durood markup parity | 592 |
| Existing user reminder scheduler regression | 33 |
| Existing reminder settings UI regression | 9 |
| **Total** | **1,348** |

The suites cover daily/missed/duplicate sunset transitions, manual-based progression, both day-29 outcomes, delayed eligibility after manual day 28, year rollover, notification deduplication, reboot, offline/invalid API responses, setup persistence, all resource mappings, cross-type playback replacement, completion/error cleanup, text-only ranges, Meaning/Reference expansion, Copy/Share/Read routing, activity count rendering, and existing Tasbih hooks. No database schema or destructive migration was introduced. Database source and New Hijri Month notification source are unchanged. `git diff --check` passes.

## Build results

`gradlew.bat assembleDebug assembleRelease --offline`: **BUILD SUCCESSFUL**, including release vital lint. Final log: `build/six-fixes/build-final.log`. Both builds use the actual current `res/raw` directory, without any resource overlay or changed signing configuration.

- Debug APK: `app/build/outputs/apk/debug/app-debug.apk`.
- Release APK: `app/build/outputs/apk/release/app-release-unsigned.apk` (unsigned as configured).

`adb devices` found no connected device/emulator. Actual sound, recording/content correctness, sunset alarm timing, screen layout, physical first-install/setup behavior, OS process-death/reboot behavior, and real persistent-DB interaction require device testing. No such physical testing is claimed.

No commit, push, reset, revert, stash, audio modification, signing change, or user-data deletion was performed.
