<?php

declare(strict_types=1);

namespace Pam\Native\Media;

enum ImageFormat: int
{
    case Jpeg = 1;
    case Png = 2;
    case Webp = 3;

    public function mimeType(): string
    {
        return match ($this) {
            self::Jpeg => 'image/jpeg',
            self::Png => 'image/png',
            self::Webp => 'image/webp',
        };
    }
}
