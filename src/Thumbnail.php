<?php

declare(strict_types=1);

namespace Pam\Native\Media;

use InvalidArgumentException;

/** One request of a `Media::thumbnails()` batch. */
final class Thumbnail
{
    private int $maxWidth = 512;

    private int $maxHeight = 512;

    private int $timeMillis = 0;

    private ThumbnailFormat $format = ThumbnailFormat::Jpeg;

    private int $quality = 80;

    private function __construct(
        private readonly string $source,
        private readonly string $destination,
    ) {
        MediaPath::assertSource($source);
        MediaPath::assert($destination);
    }

    /** `$source` is a sandbox path, or an HTTPS URL for remote videos. */
    public static function make(string $source, string $destination): self
    {
        return new self($source, $destination);
    }

    public function size(int $maxWidth, int $maxHeight): self
    {
        if ($maxWidth < 1 || $maxHeight < 1 || $maxWidth > 8192 || $maxHeight > 8192) {
            throw new InvalidArgumentException('Thumbnail dimensions must be between 1 and 8192.');
        }
        $this->maxWidth = $maxWidth;
        $this->maxHeight = $maxHeight;

        return $this;
    }

    /** Video frame position in milliseconds. */
    public function at(int $timeMillis): self
    {
        if ($timeMillis < 0) {
            throw new InvalidArgumentException('Thumbnail time cannot be negative.');
        }
        $this->timeMillis = $timeMillis;

        return $this;
    }

    public function format(ThumbnailFormat $format, int $quality = 80): self
    {
        if ($quality < 1 || $quality > 100) {
            throw new InvalidArgumentException('Thumbnail quality must be between 1 and 100.');
        }
        $this->format = $format;
        $this->quality = $quality;

        return $this;
    }

    public function source(): string
    {
        return $this->source;
    }

    /** @internal @return array<string, string|int> */
    public function toWire(): array
    {
        return [
            'source' => $this->source,
            'destination' => $this->destination,
            'maxWidth' => $this->maxWidth,
            'maxHeight' => $this->maxHeight,
            'timeMillis' => $this->timeMillis,
            'format' => $this->format->value,
            'quality' => $this->quality,
        ];
    }
}
