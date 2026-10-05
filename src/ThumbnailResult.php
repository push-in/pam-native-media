<?php

declare(strict_types=1);

namespace Pam\Native\Media;

/** One entry of a `Media::thumbnails()` batch, in request order. */
final readonly class ThumbnailResult
{
    public function __construct(
        public string $source,
        public ?string $path,
        public int $width = 0,
        public int $height = 0,
        public ?string $error = null,
    ) {
    }

    public function succeeded(): bool
    {
        return $this->path !== null;
    }
}
