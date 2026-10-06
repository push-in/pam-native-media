# Camera and compression demo

A one-screen PAM Native app for `pushinbr/pam-native-media`:

- an embedded `CameraView` (photo/video mode, front/back lens, revision-based
  shutter, 30 s recording limit);
- photos compressed to a 1920 px WebP 82 with `Media::image()` (Zé Chat's
  outbox rule) and probed for their EXIF orientation;
- videos transcoded to a 720p fast-start MP4 with progress and cancellation,
  plus a thumbnail from `Media::thumbnails()`.

```bash
cd example
pam composer install
pam doctor --fix
pam dev            # or: pam build
```

The app installs the released package from Packagist. Every output is written under `outbox/` in the PAM file sandbox.
