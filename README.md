# PAM Native Media

Inspect images, audio and video and generate correctly oriented thumbnails without loading media bytes into PHP memory.

```bash
pam add media
pam doctor
```

```php
$media = new Pam\Native\Media\Media();
$media->probe('media/clip.mp4', function (?Pam\Native\Media\MediaInfo $info, ?string $error): void {});
$media->thumbnail('media/clip.mp4', 'thumbs/clip.jpg', 640, 360, function (?string $path, ?string $error): void {});
```

Android uses platform codecs plus ExifInterface `1.4.2`; iOS uses AVFoundation and ImageIO. All paths are relative to Application Support/files storage and are canonicalized natively. Images honor EXIF orientation and video thumbnails honor track transforms.

## Embedded camera

`CameraView` renders a lifecycle-aware native camera preview. Capture commands
are revision based, so a declarative re-render never repeats an operation:

```php
$camera = CameraView::make()
    ->facing(CameraFacing::Back)
    ->mode(CameraMode::Photo)
    ->flash(CameraFlashMode::Off)
    ->captureRevision($photoRevision)
    ->recordRevision($recordRevision)
    ->stopRevision($stopRevision)
    ->onEvent(function (CameraEventKind $event, ?CameraCapture $capture, string $message): void {
        if ($event === CameraEventKind::Captured && $capture !== null) {
            // $capture->path is relative to the app-owned PAM file sandbox.
        }
    });
```

The view supports front/back lenses, explicit photo/video mode, off/on/auto
photo flash, torch while recording, optional audio, bounded recording duration
and explicit stop. Each mode binds only the CameraX/AVFoundation outputs it
needs, preserving compatibility with devices that reject combined photo and
video stream configurations. Android uses CameraX's texture-compatible preview
pipeline so controls can be composited above the camera and reactive updates do
not abandon its surface. The preview host is touch-transparent; apps retain
ownership of declarative shutter, mode, flash, and lens controls. Apps
must request camera/microphone permission before enabling the view. Both
platforms persist captures under the PAM file sandbox and return relative paths.
Construct a `FileReference` from the capture metadata to preview, edit, upload,
or delete the file with the standard PAM APIs.

Platform support: Android API 26+, iOS 15+, PAM Native 0.6.x.


## What installation does

`pam add media` resolves the official compatible package, performs a non-mutating Composer preflight, updates the normal `composer.json` and `composer.lock`, refreshes generated native integration when required, and leaves the project ready for `pam doctor` validation.

Use `pam packages` to inspect availability and `pam remove media` to uninstall the capability safely. Direct Composer commands are an advanced interoperability path; PAM is the supported application workflow.

## API guide

| API | Responsibility |
| --- | --- |
| `Media` | Probe sandboxed media and generate bounded thumbnails. |
| `MediaInfo` | Read normalized type, dimensions, duration, and orientation. |
| `CameraView` | Render a lifecycle-aware native photo/video camera. |
| `CameraCapture` | Receive sandbox-relative capture metadata. |
| `ThumbnailFormat` | Choose JPEG, PNG, or supported output encoding. |

All coded states, kinds, and variants are sequential integer-backed enums. Use enum cases in application code; do not depend on raw wire numbers.

## Production checklist

- Request camera and microphone permissions before enabling capture.
- Move or upload captures using their sandbox-relative `FileReference`.
- Bound recording duration, thumbnail dimensions, and retained media.
- Run `pam doctor`, `pam test`, and a signed release build on every supported platform.
- Exercise denial, cancellation, backgrounding, process restart, and offline behavior before release.

## Troubleshooting

- **A path is rejected:** use app-relative paths and never absolute/traversal paths.
- **Preview works but capture fails:** verify permissions and available device storage.
- **Combined streams fail on a device:** enable only the outputs required by the selected mode.
- **Native integration is stale:** run `pam doctor --fix`, rebuild the native host, and inspect the first reported diagnostic.

## Compatibility and support

This package targets PAM Native `0.6.x`, Android API 26+, and iOS 15+ unless a platform-specific section above states a stricter requirement. Platform SDKs, credentials, entitlements, physical hardware, and store configuration remain application responsibilities.

- [PAM documentation](https://push-in.github.io/pam-docs/introduction/)
- [PAM Native overview](https://push-in.github.io/pam-docs/native/overview/)
- [Plugin and native capability model](https://push-in.github.io/pam-docs/native/plugins/)
- [Report an issue](https://github.com/push-in/pam-native-media/issues)

Security vulnerabilities should be reported through the repository security policy or GitHub private vulnerability reporting, not a public issue.
