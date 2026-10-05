# Changelog

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
