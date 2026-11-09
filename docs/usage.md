# Usage: transcoding, images and thumbnails

> Moved out of the README; nothing was removed.

Inspect images, audio and video and generate correctly oriented thumbnails without loading media bytes into PHP memory.

```bash
pam composer require pushinbr/pam-native-media
pam doctor --fix
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

