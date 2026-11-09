# A real example: Zé Chat

> Moved out of the README; nothing was removed.

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
[`example/`](../example).
