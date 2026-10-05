<?php

declare(strict_types=1);

namespace Pam\Native\Media;

/** EXIF `Orientation` tag; the integer values are the EXIF values themselves. */
enum ExifOrientation: int
{
    case Normal = 1;
    case FlipHorizontal = 2;
    case Rotate180 = 3;
    case FlipVertical = 4;
    case Transpose = 5;
    case Rotate90 = 6;
    case Transverse = 7;
    case Rotate270 = 8;

    /** Clockwise rotation needed to display the stored pixels upright. */
    public function degrees(): int
    {
        return match ($this) {
            self::Rotate180, self::FlipVertical => 180,
            self::Rotate90, self::Transpose => 90,
            self::Rotate270, self::Transverse => 270,
            default => 0,
        };
    }

    public function swapsDimensions(): bool
    {
        return in_array($this, [self::Transpose, self::Rotate90, self::Transverse, self::Rotate270], true);
    }
}
