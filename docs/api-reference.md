# API reference

> Moved out of the README; nothing was removed.

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
`recordingZoom(float $target, int $durationMillis = 1600)`,
`pinchZoom(bool = true)`, `maxZoom(float)` (finite, >= 1, default 16),
`zoomResetRevision(int)`, `doubleTapFlip(bool = true)`,
`doubleTapEvents(bool = true)`,
`tapToFocus(bool = true)`, `focusAt(float $x, float $y, int $revision)` (Android),
`onEvent(Closure(CameraEventKind, ?CameraCapture, string $message, ?CameraLens $lens))`,
`toElement()` (a `CustomView` of kind `media.camera`). Each revision bump runs
its command once. `CameraCapture` (readonly): `path`, `mimeType`, `width`,
`height`, `durationMillis`. `CameraLens` (readonly, `Ready`/`DoubleTap` only):
`facing`, `minZoom`, `maxZoom` (`0` when not known yet), `hasFlash`, `hasTorch` (Android `CameraInfo.hasFlashUnit()`, iOS `AVCaptureDevice.hasFlash`/`hasTorch`; false for legacy hosts).

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
| `CameraEventKind` | `Ready = 1`, `Captured`, `RecordingStarted`, `RecordingStopped`, `PermissionDenied`, `Failure = 6`, `DoubleTap = 7` |
| `MediaPath` | `assert()`, `assertSource()` sandbox path validation |

### Errors

Builders throw `InvalidArgumentException` for absolute or traversal paths,
invalid HTTPS URLs, out-of-range sizes, qualities, bitrates and thumbnail
times, crop rectangles outside the image, rotations that are not multiples of
90, a batch outside 1–100 requests, non-`Thumbnail` batch items and a
transcode destination equal to its source. `run()` without `to()` throws
`LogicException`. Native failures go to the `$failed`/`$complete` callbacks
(or `ThumbnailResult::$error`) and never throw.

