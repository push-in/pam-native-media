<?php

declare(strict_types=1);

namespace Pam\Native\Media;

/** Image metadata; `width`/`height` are display dimensions after EXIF orientation. */
final readonly class ImageInfo
{
    public function __construct(
        public int $width,
        public int $height,
        public int $storedWidth,
        public int $storedHeight,
        public ExifOrientation $orientation,
        public string $mimeType,
        public int $bytes,
    ) {
    }
}
