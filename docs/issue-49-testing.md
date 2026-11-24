# Interrupted recording validation (#49)

## Behavior

MediaMuxer records into an app-private, non-backed-up `.partial` file. A synchronous
SharedPreferences commit records its location before encoding begins. Default gallery
publication uses `IS_PENDING=1` while copying and publishes only after the copy finishes.
Custom document folders use a temporary non-video document and rename it after copying.
Android 8–9 publish into the app's external-files folder, with scoped FileProvider access
for playback and sharing.

Startup recovery checks for an indexed video track containing a sample. A finalized
source is published; an MP4 without a usable index is removed only after persisting an
explanation. Publication, copying and private-source cleanup have persisted checkpoints
so retrying does not create another recording or delete an already-published one.
Storage or permission failures retain the private source and session for retry.
Recovery explanations remain in the app until acknowledged, including when Android
notification permission is denied. Legacy debug-build `output_uri` markers are also checked.

This does **not** reconstruct the MP4 index of a muxer killed before `stop()`. Such a
recording can still be lost. Memory callbacks attempt a normal stop, but Android can kill
without a callback. Publication temporarily needs space for the private source and the
destination copy. Staging uses internal app storage even when the selected output
folder is on external storage.

## Automated coverage

`InterruptedRecordingRecoveryTest` adds 12 JVM tests covering:

- Publication and persisted notice before clearing the marker.
- Persisting an explanation before deleting an unplayable recording.
- Normal successful stop without an interruption notice, and failed stop with a notice.
- Inaccessible source, full storage, unavailable destination and successful retry.
- Cleanup failure and successful cleanup retry.
- Notice persistence failure preventing silent deletion.
- Saved-notice and marker-clear failures retaining the session.

`RecordingSessionStoreTest` adds device tests for real Android APIs:

| Case | Expected result |
| --- | --- |
| Active recording | Private `.partial`, committed marker, no gallery row |
| Missing MP4 index | Removed, no gallery row, persisted explanation |
| Empty file | No publication; persisted explanation |
| Finalized interrupted file | One playable published row; source/marker cleared |
| Normal stop | One published row; no interruption notice |
| Memory-pressure stop | Saved video with memory-pressure notice |
| Revoked custom-folder access | Private source and marker retained; retry notice |
| Kill during destination copy | Same pending row overwritten and published |
| Kill after publication | Existing row preserved without duplication |
| Missing destination | Destination recreated from private source |
| Notice acknowledgment | Notice persists across reads until acknowledged |
| Kill after private-source deletion | Saved destination preserved; marker cleared |
| Legacy debug marker | Unfinalized public output removed with explanation |
| Older dialog acknowledgment | A newer recovery notice remains available |

The tiny H.264 test fixture is generated, not captured from a user's screen:

```sh
ffmpeg -f lavfi -i testsrc=size=64x64:rate=5 -t 1 -c:v libx264 \
  -pix_fmt yuv420p -movflags +faststart recovery-fixture.mp4
```

## Commands

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
# Requires a connected Android device or emulator:
.\gradlew.bat :app:connectedDebugAndroidTest
```

Gradle `testDebugUnitTest` passed **94 tests, 0 failures, 0 errors**, including the 12 new
recovery tests. The same suite also passed independently with the installed Kotlin compiler
and JUnit 4.13.2. The debug app APK and Android test APK both built successfully; all 14
device tests compiled. Full lint was attempted but did not complete because its separate
analysis runtime needed additional large downloads; it is not reported as passing.
Device tests and the manual
cases below were not executed: the user requested continuing without a connected phone.

## Manual device checks still needed

1. Start recording with internal audio. While active, verify that the gallery and app
   library show no new recording. Stop normally and play the published video; check audio.
2. Repeat, then run `adb shell am force-stop com.haseeb.recorder.debug` during capture.
   Verify there is no broken gallery entry. Relaunch and check the removal explanation
   and cleared session. Force-stop is a deterministic process-death proxy, not proof of LMKD.
3. Deny notification permission and repeat the interruption. Confirm the app still shows
   an explanation after relaunch, and that dismissing without acknowledgment does not erase it.
4. Repeat normal-stop and process-death cases using a custom folder. Verify temporary
   documents do not appear as normal recordings; finalized documents play and share.
5. Trigger a running-low or running-critical trim callback while recording. Verify best-effort
   finalization and the saved notice. A routine `UI_HIDDEN` callback must not stop capture.
6. Check API 26–28 private-file playback/sharing and API 29+ gallery publication. The connected
   device previously identified as SM-A075F/API 37 is not the issue reporter's Galaxy A15/API 36.
