# Changelog

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

