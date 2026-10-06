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

Android uses Media3 Transformer `1.10.1`, platform codecs and ExifInterface `1.4.2`; Android paths resolve inside the PAM file sandbox (`filesDir/pam-files`, the `FileReference::$path` space). iOS (0.5+) implements the same API: `transcode()` re-encodes with AVAssetReader/AVAssetWriter to H.264 Main 4.1 / AAC-LC with the Android preset targets (dimensions, bitrate tiers, 2 s keyframes, stereo 44.1 kHz audio, BT.709 SDR) and writes `moov` first for `fastStart()`; display orientation is kept as the track transform. `image()` decodes through ImageIO (EXIF-oriented, subsampled, capped at 48 MP) and applies the same resize/crop/rotate/flip geometry as Android; `ImageFormat::Webp` needs an iOS version whose ImageIO can encode WebP and fails with a clear message otherwise. `thumbnails()` batches and HTTPS video thumbnails are supported. iOS paths resolve inside `Application Support/pam-files` (the `FileReference::$path` space). The stable Objective-C entry point `PamMediaTranscoding` lets `pushinbr/pam-native-background-transfer` 0.4+ transcode inside its pipelines. The iOS implementation has not been validated on a device yet; see `ios/Tests/MediaModuleTests.swift`. Images honor EXIF orientation and video thumbnails honor track transforms.

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
and explicit stop. `Photo` and `Video` bind only the CameraX/AVFoundation
outputs they need. `PhotoVideo` binds photo and video together for a shutter
that takes a photo on tap and records while held; devices that reject the
combined stream configuration fall back to the photo pipeline and a record
command then reports `Failure`. Android uses CameraX's texture-compatible preview
pipeline so controls can be composited above the camera and reactive updates do
not abandon its surface. The preview host is touch-transparent; apps retain
ownership of declarative shutter, mode, flash, and lens controls. Apps
must request camera/microphone permission before enabling the view. Both
platforms persist captures under the PAM file sandbox and return relative paths.
Construct a `FileReference` from the capture metadata to preview, edit, upload,
or delete the file with the standard PAM APIs.

Optional recording zoom runs entirely on the native camera:

```php
$camera = CameraView::make()
    ->mode(CameraMode::Video)
    ->recordingZoom(2.8, 1600)
    ->maxDuration(3)
    ->recordRevision($recordRevision)
    ->stopRevision($stopRevision);
```

The ramp starts at `RecordingStarted`, from 1× to the requested target over the
specified milliseconds. The camera's zoom range bounds the target; stopping,
disabling, changing lenses/modes or releasing the view cancels and resets zoom.
The default target `1.0` and duration `0` leave zoom disabled. Targets must be
finite and at least `1.0`; durations accept `0`–`600000` milliseconds, with zero
disabling the animation. Direct `media.camera` hosts use the additive
`zoomTarget` (decimal) and `zoomDurationMillis` (integer) properties. Changing
these values affects the next recording. Existing camera event integers stay
unchanged. Android uses CameraX with a native animator, and iOS uses the
AVFoundation zoom ramp; no animation frames cross the PHP bridge.

Platform support: Android API 26+, iOS 15+, PAM Native `>=1.0.35 <2.0.0`.


## What installation does

`pam add media` (or `pam composer require pushinbr/pam-native-media` followed by `pam doctor --fix`) resolves the official compatible package, performs a non-mutating Composer preflight, updates the normal `composer.json` and `composer.lock`, refreshes generated native integration when required, and leaves the project ready for `pam doctor` validation. The package is a PAM Native plugin (module `media`, view `media.camera`); nothing is added to `pam-native.json`.

Use `pam packages` to inspect availability and `pam remove media` to uninstall the capability safely. Direct Composer commands are an advanced interoperability path; PAM is the supported application workflow.

### Android

Merged permissions: `CAMERA` and `RECORD_AUDIO` (only `CameraView` uses them;
request `PermissionKind::Camera`/`Microphone` before enabling the view).
Dependencies: CameraX `1.6.1` (`camera-camera2`, `camera-lifecycle`,
`camera-view`, `camera-video`), Media3 `1.10.1` (`media3-common`,
`media3-effect`, `media3-transformer`) and ExifInterface `1.4.2`.

### iOS

Frameworks `AVFoundation`, `CoreGraphics`, `CoreMedia`, `CoreVideo`, `ImageIO`,
`UniformTypeIdentifiers`. Usage strings merged into Info.plist:
`NSCameraUsageDescription` = "Capture photos and videos." and
`NSMicrophoneUsageDescription` = "Record audio with videos." Other plugins
that declare the same keys (`pam-native-webrtc`, `pam-native-camera`) use the
same strings, because the CLI rejects conflicting values.

## A real example: Zé Chat

Zé Chat compresses every photo before it enters the outbox, like its React
Native predecessor (`optimizeImageToWebp`): EXIF orientation baked in, longest
side at most 1920 px and never upscaled, lossy WebP 82. GIFs and any failure
keep the original file:

```php
use Pam\Native\FileReference;
use Pam\Native\Media\{ImageFormat, ImageInfo, ImageResult, Media, ResizeMode};

public static function compressImage(FileReference $file, Closure $done): void
{
    if (!str_starts_with($file->mimeType, 'image/') || $file->mimeType === 'image/gif') {
        $done($file, 0, 0);
        return;
    }
    $destination = 'zechat-outbox/'.bin2hex(random_bytes(12)).'.webp';
    try {
        Media::image($file->path)
            ->resize(1920, 1920, ResizeMode::Contain)
            ->onlyScaleDown()
            ->format(ImageFormat::Webp, 82)
            ->save(
                $destination,
                fn (ImageResult $r) => $done(new FileReference($r->path, 'photo.webp', ImageFormat::Webp->mimeType(), $r->bytes), $r->width, $r->height),
                fn (string $_error) => $done($file, 0, 0),
            );
    } catch (InvalidArgumentException) {   // a path outside the sandbox
        $done($file, 0, 0);
    }
}

// Display size after EXIF orientation (ImageInfo already swaps 90°/270°).
Media::image($file->path)->probe(fn (ImageInfo $i) => $done($i->width, $i->height), fn () => $done(0, 0));
```

Videos are re-encoded inside the durable upload with
`pam-native-background-transfer`'s `->transcode(VideoPreset::Adaptive, fallbackToOriginal: true)`,
which calls this package's transcoder natively. A runnable minimal app is in
[`example/`](example).

## API reference

All classes live in `Pam\Native\Media`. Native calls return the module request id (`int`).

### `Media`

| Method | Description |
| --- | --- |
| `Media::transcode(string $source): PendingTranscode` | Video transcode builder. |
| `Media::image(string $source): PendingImage` | Image pipeline builder. |
| `Media::thumbnails(list<Thumbnail> $requests, Closure(list<ThumbnailResult>) $then)` | 1–100 thumbnails in one call, results in request order. |
| `(new Media())->probe(string $path, Closure(?MediaInfo, ?string) $complete)` | Type, size, dimensions, duration, rotation. |
| `(new Media())->thumbnail(string $source, string $destination, int $maxWidth, int $maxHeight, Closure(?string, ?string) $complete, ThumbnailFormat $format = Jpeg, int $quality = 85, int $timeMillis = 0)` | One thumbnail (0.3 API). |

### `PendingTranscode` / `TranscodeTask` / `TranscodeResult`

`to(string $destination)` (required, must differ from the source),
`preset(VideoPreset)` (default `Adaptive`), `maxBitrate(int $bitsPerSecond)`
(100 kbps–50 Mbps), `fastStart(bool = true)`, `withoutAudio()`,
`progress(Closure(float))` (0.0–1.0), `options()`,
`run(Closure(TranscodeResult) $then, ?Closure(string) $failed = null): TranscodeTask`.
`TranscodeTask`: `cancel()`, `finished()`. `TranscodeResult` (readonly):
`path`, `mimeType`, `width`, `height`, `durationMillis`, `bytes`, `bitrate`,
`fastStart`.

### `PendingImage` / `ImageResult` / `ImageInfo`

`resize(int $width, int $height, ResizeMode $mode = Contain)` (1–16384, `0` =
automatic), `onlyScaleDown(bool = true)`, `crop(int $x, int $y, int $width, int $height)`,
`rotate(int $degrees = 90)` (multiples of 90, negative = counter-clockwise),
`flip(FlipDirection = Horizontal)`, `format(ImageFormat, int $quality = 85)`
(1–100; default JPEG 85), `payload(string $destination)`,
`save(string $destination, Closure(ImageResult) $then, ?Closure(string) $failed = null)`,
`probe(Closure(ImageInfo) $then, ?Closure(string) $failed = null)`.
`ImageResult`: `path`, `width`, `height`, `bytes`, `mimeType`. `ImageInfo`:
`width`, `height` (display, after orientation), `storedWidth`,
`storedHeight`, `orientation` (`ExifOrientation`), `mimeType`, `bytes`.

### `Thumbnail` / `ThumbnailResult`

`Thumbnail::make(string $source, string $destination)` (sandbox path or HTTPS
video URL), `size(int $maxWidth, int $maxHeight)` (1–8192), `at(int $timeMillis)`,
`format(ThumbnailFormat, int $quality = 80)`, `source()`, `toWire()`.
`ThumbnailResult`: `source`, `path` (`null` on failure), `width`, `height`,
`error`, `succeeded()`.

### `CameraView` (`Renderable`, immutable)

`make()`, `facing(CameraFacing)` (default `Back`), `mode(CameraMode)` (default
`Photo`), `flash(CameraFlashMode)`, `enabled(bool = true)`,
`captureRevision(int)`, `recordRevision(int)`, `stopRevision(int)`,
`maxDuration(int $seconds)` (1–600, default 60), `audio(bool = true)`,
`onEvent(Closure(CameraEventKind, ?CameraCapture, string $message))`,
`toElement()` (a `CustomView` of kind `media.camera`). Each revision bump runs
its command once. `CameraCapture` (readonly): `path`, `mimeType`, `width`,
`height`, `durationMillis`.

### Value types and enums (int-backed)

| Type | Members |
| --- | --- |
| `MediaInfo` | `kind`, `mimeType`, `bytes`, `width`, `height`, `durationMillis`, `orientationDegrees` |
| `MediaKind` | `Image = 1`, `Audio = 2`, `Video = 3`, `Unknown = 4` |
| `VideoPreset` | `Compact480p = 1`, `Chat720p = 2`, `Chat1080p = 3`, `Adaptive = 4` |
| `ResizeMode` | `Contain = 1`, `Cover = 2`, `Stretch = 3` |
| `ImageFormat` | `Jpeg = 1`, `Png = 2`, `Webp = 3`; `mimeType()` |
| `ThumbnailFormat` | `Jpeg = 1`, `Png = 2`, `Webp = 3` |
| `FlipDirection` | `Horizontal = 1`, `Vertical = 2` |
| `ExifOrientation` | EXIF values `Normal = 1` … `Rotate270 = 8`; `degrees()`, `swapsDimensions()` |
| `CameraFacing` | `Back = 1`, `Front = 2` |
| `CameraMode` | `Photo = 1`, `Video = 2`, `PhotoVideo = 3` |
| `CameraFlashMode` | `Off = 1`, `On = 2`, `Auto = 3` |
| `CameraEventKind` | `Ready = 1`, `Captured`, `RecordingStarted`, `RecordingStopped`, `PermissionDenied`, `Failure = 6` |
| `MediaPath` | `assert()`, `assertSource()` sandbox path validation |

### Errors

Builders throw `InvalidArgumentException` for absolute or traversal paths,
invalid HTTPS URLs, out-of-range sizes, qualities, bitrates and thumbnail
times, crop rectangles outside the image, rotations that are not multiples of
90, a batch outside 1–100 requests, non-`Thumbnail` batch items and a
transcode destination equal to its source. `run()` without `to()` throws
`LogicException`. Native failures go to the `$failed`/`$complete` callbacks
(or `ThumbnailResult::$error`) and never throw.

## Tests

`composer test` runs the PHP contract suite. Android JVM tests (`android/src/test`: resize geometry, fast-start rewriting, options) and instrumented tests (`android/src/androidTest`: EXIF pipeline, thumbnails, Media3 transcode, cross-plugin entry point) run from a PAM Android host that includes this plugin with `testDebugUnitTest` / `connectedDebugAndroidTest`. JVM tests require JUnit 4 and the JVM `org.json` implementation on the test classpath (the Android SDK JSON stub cannot execute parser tests).

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

| `pushinbr/pam-native-media` | `pushinbr/pam-native` | Android | iOS |
| --- | --- | --- | --- |
| 0.5.x | `>=1.0.35 <2.0.0` (tested with 1.14.x) | API 26+ | 15+ (transcode, image pipeline, batches) |
| 0.4.x | `>=1.0.35 <2.0.0` | API 26+ | Probe, thumbnails and camera only |

This package targets PAM Native `>=1.0.35 <2.0.0`, Android API 26+, and iOS 15+ unless a platform-specific section above states a stricter requirement. Platform SDKs, credentials, entitlements, physical hardware, and store configuration remain application responsibilities.

- [PAM documentation](https://push-in.github.io/pam-docs/introduction/)
- [PAM Native overview](https://push-in.github.io/pam-docs/native/overview/)
- [Plugin and native capability model](https://push-in.github.io/pam-docs/native/plugins/)
- [Report an issue](https://github.com/push-in/pam-native-media/issues)

Security vulnerabilities should be reported through the repository security policy or GitHub private vulnerability reporting, not a public issue.
