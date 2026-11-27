# Changelog

## 5.2

- Added optional **Always Show Floating Bar** controls to start and manage recordings from anywhere, with smoother drag/snap physics and edge-magnet snapping (#27).
- Added **Hide Floating Ball While Recording** to exclude floating controls from full-screen and single-app video capture while keeping notification controls accessible (#45).
- Isolated camera permissions from the main app task so toggling the floating camera while recording does not bring `MainActivity` to the foreground (#27).
- Added **AV1** video encoder support on Android 12+ devices with automatic fallback (`VideoEncoderCatalog`) (#44).
- Kept recording countdowns intact across permission dialogs and activity transitions (#43).
- Kept video capture alive during display sleep/timeouts and added frame-drop warnings if a video track ends early (#48).
- Safeguarded interrupted recordings for recovery across OS kills and prevented non-critical `TRIM_MEMORY_BACKGROUND` / moderate trim callbacks from prematurely stopping recordings (#49).
- Improved simultaneous internal + microphone audio capture with playback/restriction diagnostics and optional privileged Shizuku/Root capture fallback for `ALLOW_CAPTURE_BY_SYSTEM` streams (#43, #47).
- Improved VoIP/communication app coexistence (WeChat, Tencent Meeting, etc.) on HAL-sensitive devices and active calls, added explicit `USAGE_VOICE_COMMUNICATION` / `ALLOW_CAPTURE_BY_NONE` restriction diagnostics, and added privileged `CAPTURE_VOICE_COMMUNICATION_OUTPUT` support (#24).

### Known limitations

- On stock non-rooted Android 10+, Android restricts third-party internal capture of `USAGE_VOICE_COMMUNICATION` call streams unless privileged `CAPTURE_VOICE_COMMUNICATION_OUTPUT` is granted, and the OS may also silence background microphone streams while another app holds active call capture.
- Pressing the hardware Power button on devices where Keyguard immediately revokes `MediaProjection` still cleanly stops and saves the active recording when the OS revokes screen capture.

## 5.1

- Fixed intermittent internal and microphone audio capture stalls during recording (#21).
- Added optional root access alongside Shizuku for privileged recording controls (#23).
- Fixed blank or frozen floating camera previews by keeping camera access in a foreground service (#25).
- Quick Settings recording now starts through a minimal permission screen instead of opening the main app (#26).
- Auto orientation follows screen and captured-app size changes within one continuous video, fitting content into the starting frame with black bars when needed (#28).
- Preserved the existing Developer Options Show Taps setting after recording stops (#29).
- Added tap-to-expand/reset and corner drag resizing for the camera preview, with aspect-ratio preservation and screen-edge bounds.
- Added supported microphone noise suppression and echo cancellation, plus a limiter to reduce distortion when microphone and internal audio overlap.

### Known limitations

- Microphone noise suppression and echo cancellation depend on the device. Occasional blended-vocal or pitch artifacts reported during simultaneous microphone and internal audio capture have no confirmed cause yet. Complete speaker-bleed removal is not guaranteed.
- Auto orientation keeps the video's starting dimensions; it does not change the MP4 frame dimensions mid-recording.
- Always-on floating recording controls (#27) are outside the current product direction. WeChat audio capture (#24) remains unverified.

