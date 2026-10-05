<?php

declare(strict_types=1);

namespace Pam\Native\Media;

final readonly class ImageResult
{
    public function __construct(
        public string $path,
        public int $width,
        public int $height,
        public int $bytes,
        public string $mimeType,
    ) {
    }
}
