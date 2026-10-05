<!-- pam:product-page:start -->
<div align="center">

# PAM Native Media

**Inspect and prepare media without loading whole files into PHP.**

Read metadata and generate thumbnails through native codecs with sandboxed paths and bounded results.

[![Latest version](https://img.shields.io/packagist/v/pushinbr/pam-native-media?style=flat-square&label=stable)](https://packagist.org/packages/pushinbr/pam-native-media)
[![CI](https://img.shields.io/github/actions/workflow/status/push-in/pam-native-media/ci.yml?branch=main&style=flat-square&label=CI)](https://github.com/push-in/pam-native-media/actions)
![PHP](https://img.shields.io/badge/PHP-8.5-777BB4?style=flat-square&logo=php&logoColor=white)
![Android](https://img.shields.io/badge/Android-API%2026%2B-3DDC84?style=flat-square&logo=android&logoColor=white)
![iOS](https://img.shields.io/badge/iOS-15%2B-000000?style=flat-square&logo=apple&logoColor=white)

**[Documentation](https://push-in.github.io/pam-docs/native/overview/) · [Quick start](#quick-start) · [What you can build](#what-you-can-build) · [PAM ecosystem](https://push-in.github.io/pam-docs/ecosystem/) · [Issues](https://github.com/push-in/pam-native-media/issues)**

</div>

---

## Why PAM Native Media

Read metadata and generate thumbnails through native codecs with sandboxed paths and bounded results. The public API is strictly typed for PHP 8.5; expensive or frame-sensitive work stays in Rust or the platform SDK instead of crossing the application boundary every frame.

| | |
| --- | --- |
| **Best for** | A focused capability you can add to any PAM Native application |
| **Native path** | MediaMetadataRetriever · AVFoundation |
| **Application model** | Composer package + generated native integration |
| **Design rule** | Independent module; no feed, vertical, or application template bundled |

## What you can build

- Gallery and attachment pickers
- Upload preparation and validation
- Fast thumbnail pipelines for local media libraries

## Quick start

Already have a PAM Native project? Add only this capability:

```bash
pam composer require pushinbr/pam-native-media
pam doctor --fix
```

New to PAM? Follow the **[five-minute PAM Native setup](https://push-in.github.io/pam-docs/native/overview/)** once, then return here. Your application stays a normal Composer project with a committed lockfile.
<!-- pam:product-page:end -->

## See it in action

Inspect images, audio and video and generate correctly oriented thumbnails without loading media bytes into PHP memory.

```bash
pam add media
pam doctor
```

```php
use Pam\Native\Media\{FlipDirection, ImageFormat, ImageInfo, ImageResult, Media, ResizeMode, Thumbnail, ThumbnailResult, TranscodeResult, VideoPreset};

// Upload-ready MP4 (H.264 Main / AAC-LC, bounded size, 2 s keyframes, fast start)
$task = Media::transcode('captures/clip.mov')
    ->to('outbox/clip.mp4')
    ->preset(VideoPreset::Chat720p)
    ->maxBitrate(1_200_000)
    ->fastStart()
    ->progress(fn (float $p) => $this->encoding = $p)
    ->run(fn (TranscodeResult $video) => $this->send($video->path), fn (string $error) => $this->fail($error));
// $task->cancel();

// Image pipeline: EXIF orientation first, then operations in call order
Media::image('captures/photo.jpg')
    ->resize(1600, 1600, ResizeMode::Contain)
    ->onlyScaleDown()
    ->crop(0, 0, 1600, 900)
    ->rotate(90)
    ->flip(FlipDirection::Horizontal)
    ->format(ImageFormat::Webp, 80)
    ->save('outbox/photo.webp', fn (ImageResult $image) => ...);

Media::image('captures/photo.jpg')->probe(fn (ImageInfo $info) => [$info->width, $info->height, $info->orientation]);

// Many thumbnails in one native call (sandbox paths, or HTTPS URLs for remote videos)
Media::thumbnails([
    Thumbnail::make('captures/clip.mov', 'thumbs/clip.jpg')->size(320, 320)->at(1000),
    Thumbnail::make('https://cdn.example.com/v.mp4', 'thumbs/v.jpg'),
], fn (array $results) => ...); // list<ThumbnailResult>, request order, per-item error

// Unchanged 0.3 API
$media = new Media();
$media->probe('media/clip.mp4', function (?Pam\Native\Media\MediaInfo $info, ?string $error): void {});
$media->thumbnail('media/clip.mp4', 'thumbs/clip.jpg', 640, 360, function (?string $path, ?string $error): void {});
```

`VideoPreset`: `Compact480p` (≤854×480, 0.8 Mbps), `Chat720p` (≤1280×720, 1.5 Mbps), `Chat1080p` (≤1920×1080, 2.5 Mbps), `Adaptive` (1080p up to 3 min, then 720p). Presets never upscale, keep the display orientation and aspect ratio, tone-map HDR to SDR, convert mono audio to stereo and resample unusual rates to 48 kHz. `ResizeMode::Contain|Cover|Stretch` accept `0` for one automatic dimension; `onlyScaleDown()` never enlarges (Cover then keeps the box aspect ratio). Decoding subsamples large photos to the size actually needed and caps decoded pixels, so PHP never holds pixel data. Outputs are written atomically.

Android uses Media3 Transformer `1.10.1`, platform codecs and ExifInterface `1.4.2`; Android paths resolve inside the PAM file sandbox (`filesDir/pam-files`, the `FileReference::$path` space). iOS uses AVFoundation and ImageIO for `probe()`/`thumbnail()`; `transcode()`, `image()` and `thumbnails()` are Android-only in 0.4. Images honor EXIF orientation and video thumbnails honor track transforms.

Other PAM plugins can reuse the transcoder through the stable JVM entry point `dev.pam.media.MediaTranscoding.transcode(context, source, destination, optionsJson, cancelled, progress)`; `pushinbr/pam-native-background-transfer` uses it for `->transcode()`.

On macOS, run the focused sizing and sandbox checks with `swiftc ios/Sources/MediaThumbnailSizing.swift ios/Sources/MediaSandboxPath.swift tests/ios-thumbnail/main.swift -o /tmp/pam-media-ios-check && /tmp/pam-media-ios-check`.

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

Platform support: Android API 26+, iOS 15+, PAM Native `>=1.0.35 <2.0.0`.


## What installation does

`pam add media` resolves the official compatible package, performs a non-mutating Composer preflight, updates the normal `composer.json` and `composer.lock`, refreshes generated native integration when required, and leaves the project ready for `pam doctor` validation.

Use `pam packages` to inspect availability and `pam remove media` to uninstall the capability safely. Direct Composer commands are an advanced interoperability path; PAM is the supported application workflow.

## API guide

| API | Responsibility |
| --- | --- |
| `Media` | Probe sandboxed media, generate bounded thumbnails; `transcode()`, `image()`, `thumbnails()`. |
| `PendingTranscode` / `TranscodeTask` / `TranscodeResult` | Fluent video transcode, cancellation and result. |
| `PendingImage` / `ImageInfo` / `ImageResult` | Image resize/crop/rotate/flip/encode and EXIF-aware probe. |
| `Thumbnail` / `ThumbnailResult` | Batched image/video thumbnails. |
| `VideoPreset`, `ResizeMode`, `ImageFormat`, `FlipDirection`, `ExifOrientation` | Sequential integer-backed enums (EXIF values for orientation). |
| `MediaInfo` | Read normalized type, dimensions, duration, and orientation. |
| `CameraView` | Render a lifecycle-aware native photo/video camera. |
| `CameraCapture` | Receive sandbox-relative capture metadata. |
| `ThumbnailFormat` | Choose JPEG, PNG, or supported output encoding. |

All coded states, kinds, and variants are sequential integer-backed enums. Use enum cases in application code; do not depend on raw wire numbers.

## Tests

`composer test` runs the PHP contract suite. Android JVM tests (`android/src/test`: resize geometry, fast-start rewriting, options) and instrumented tests (`android/src/androidTest`: EXIF pipeline, thumbnails, Media3 transcode, cross-plugin entry point) run from a PAM Android host that includes this plugin with `testDebugUnitTest` / `connectedDebugAndroidTest`.

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

This package targets PAM Native `0.8–1.x`, Android API 26+, and iOS 15+ unless a platform-specific section above states a stricter requirement. Platform SDKs, credentials, entitlements, physical hardware, and store configuration remain application responsibilities.

- [PAM documentation](https://push-in.github.io/pam-docs/introduction/)
- [PAM Native overview](https://push-in.github.io/pam-docs/native/overview/)
- [Plugin and native capability model](https://push-in.github.io/pam-docs/native/plugins/)
- [Report an issue](https://github.com/push-in/pam-native-media/issues)

Security vulnerabilities should be reported through the repository security policy or GitHub private vulnerability reporting, not a public issue.
