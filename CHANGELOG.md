# Changelog

## 0.5.3 - 2026-10-07

- Android camera preview renders through a `SurfaceView`
  (`PreviewView.ImplementationMode.PERFORMANCE`, react-native-vision-camera's
  default): the camera buffers reach the compositor with their own colour
  space and range. The `TextureView` path re-sampled them through GL and
  showed a flatter, washed-out frame; on a Galaxy S10 the preview luminance
  over the same static scene moved from +9.1 to -6.6 levels against the React
  Native reference, with matching contrast. iOS already draws through
  `AVCaptureVideoPreviewLayer`.

## 0.5.2 - 2026-10-06

- Add optional `CameraView::recordingZoom(target, durationMillis)` and native
  `zoomTarget`/`zoomDurationMillis` properties. Zoom begins at recording start,
  clamps to the device range and resets on stop, disable, rebind or release.
- Keep recording timers and asynchronous camera setup owned by the live
  Android host; ignore callbacks after release.
- iOS treats successfully finalized duration-limited captures as usable files
  and updates the native duration limit for each recording.
- Add PHP zoom contract/validation tests and Android zoom range/progression
  tests. No additional dependencies.

## 0.5.1 - 2026-10-06

- Add `CameraMode::PhotoVideo` (3): one session with photo capture and video
  recording, for a shutter that takes a photo on tap and records while held
  (the React Native vision-camera photo + video outputs). Android binds
  Preview + ImageCapture + VideoCapture and falls back to the photo pipeline
  when the device rejects the combined streams; iOS adds the photo and movie
  outputs (plus the microphone when authorized).
- iOS: a record command on a session without the movie output (`Photo` mode)
  reports `Failure`, as on Android, instead of starting an unattached output.

## 0.5.0 - 2026-10-05

- iOS: `Media::transcode()` with AVAssetReader/AVAssetWriter (H.264 Main 4.1 /
  AAC-LC, Android preset targets and bitrate tiers, 2 s keyframes, fast start,
  `withoutAudio()`, cancellable progress through the same `transcodeNext`
  channel).
- iOS: `Media::image()` (`resize`/`crop`/`rotate`/`flip`/`format`, `probe()`
  with stored dimensions and EXIF orientation) on ImageIO/CoreGraphics with the
  Android geometry, and `Media::thumbnails()` batches; `ThumbnailFormat::Webp`
  where ImageIO can encode WebP; HTTPS video thumbnails.
- iOS: expose `PamMediaTranscoding` (Objective-C, contract version 1) for
  `pushinbr/pam-native-background-transfer` 0.4+.
- Fix: iOS `probe()`/`thumbnail()` resolve paths in the PAM file sandbox
  (`Application Support/pam-files`), matching Android and `FileReference`.
- iOS module work runs off the PHP thread (two workers).
- XCTest mirror of the Android geometry and module tests (`ios/Tests`).
  Uncompiled on the release machine; needs device validation.

## 0.4.0 - 2026-10-05

- Add `Media::transcode($src)->to()->preset(VideoPreset)->maxBitrate()->fastStart()->withoutAudio()->progress()->run()`:
  Media3 Transformer export to H.264 Main@4.1 / AAC-LC MP4 with bounded
  dimensions, 2 s keyframes, HDR→SDR tone mapping, mono→stereo, sample-rate
  normalization, cancellable `TranscodeTask` and native fast-start (`moov`
  before `mdat`) rewriting.
- Add `Media::image($src)` with `resize(w, h, ResizeMode)`, `onlyScaleDown()`,
  `crop()`, `rotate()`, `flip()`, `format(ImageFormat, quality)` and atomic
  `save()`, plus `probe()` returning display and stored dimensions with the
  EXIF `ExifOrientation`. Decoding subsamples to the needed resolution and caps
  decoded pixels.
- Add `Media::thumbnails([...Thumbnail])` batches with per-item results,
  scaled video frame extraction and HTTPS sources for remote videos;
  `ThumbnailFormat::Webp`.
- Expose `dev.pam.media.MediaTranscoding`, a stable JVM entry point (kept via
  consumer R8 rules) used by `pushinbr/pam-native-background-transfer` 0.3 to
  transcode inside its upload worker.
- Fix: Android `probe()`/`thumbnail()` now resolve paths in the PAM file
  sandbox (`filesDir/pam-files`), matching camera captures and `FileReference`.
- Android module work runs off the PHP thread on a bounded executor.
- Requires PAM Native `>=1.0.35 <2.0.0`. New APIs are Android-only for now;
  iOS keeps `probe()`, `thumbnail()` and the camera.
- Add Android JVM unit tests and instrumented tests.

## 0.3.2 - 2026-10-04

- Keep iOS image and video thumbnails inside both requested dimensions,
  including rotated EXIF images, and preserve the previous output if encoding fails.
- Resolve each sandbox path component before writing, so an intermediate
  symbolic link cannot redirect a thumbnail outside application storage.
- Guard non-finite AVFoundation metadata and run Swift thumbnail contracts on
  macOS alongside the PHP package checks.

## 0.2.5 - 2026-08-24

- Expand the plugin contract through the complete pre-1.0 PAM Native line.

## 0.2.4 - 2026-08-24

- Certify PAM Native 0.9.1 while preserving the supported 0.8 line.

## 0.2.3 - 2026-08-07

- Store embedded camera photos and videos in the PAM file sandbox and return
  renderer-safe relative paths, so captured media can be previewed, edited,
  uploaded and deleted through `FileReference` on Android and iOS.

## 0.2.2 - 2026-08-07

- Add a typed photo/video camera mode and bind only the active capture pipeline.
- Add CameraX quality fallback so embedded video capture works on constrained
  devices and Android emulators.
- Render Android previews through the compatible texture pipeline so modal
  overlays and declarative updates do not abandon the camera surface.
- Keep the Android preview touch-transparent so declarative camera controls
  layered by the host application receive their press events.

## 0.2.1 - 2026-08-05

- Fix CameraX callback return types so embedded camera hosts compile with the
  Kotlin toolchain shipped by PAM Native.

## 0.2.0 - 2026-08-05

- Add a typed embedded camera view with native preview, front/back lens,
  photo flash, photo capture and start/stop video recording commands.
- Add app-owned capture results, bounded recording duration, optional audio,
  Android CameraX integration and camera/microphone manifest metadata.

## 0.1.0 - 2026-08-01

- Initial public release of the documented PAM Native package contract.
- Add bounded input validation, sequential integer protocol enums, automated
  package tests, and PHP 8.4/8.5 continuous integration.
