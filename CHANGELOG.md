# Changelog

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
