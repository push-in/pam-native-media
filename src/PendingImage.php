<?php

declare(strict_types=1);

namespace Pam\Native\Media;

use Closure;
use InvalidArgumentException;
use JsonException;
use Pam\Native\Modules\NativeModuleResult;
use Pam\Native\Modules\NativeModules;

/**
 * Fluent native image pipeline. EXIF orientation is applied first, then the
 * operations run in call order; crop rectangles use the pixel space of the
 * image at that point of the pipeline. Large sources are subsampled while
 * decoding, so PHP never holds pixel data.
 */
final class PendingImage
{
    private const string MODULE = 'media';

    /** @var list<array<string, int|string>> */
    private array $operations = [];

    private bool $onlyScaleDown = false;

    private ImageFormat $format = ImageFormat::Jpeg;

    private int $quality = 85;

    /** @internal */
    public function __construct(private readonly string $source)
    {
        MediaPath::assert($source);
    }

    /** Pass 0 for one dimension to constrain only the other. */
    public function resize(int $width, int $height, ResizeMode $mode = ResizeMode::Contain): self
    {
        if ($width < 0 || $height < 0 || $width > 16384 || $height > 16384 || ($width === 0 && $height === 0)) {
            throw new InvalidArgumentException('Resize dimensions must be between 1 and 16384 (0 = automatic).');
        }
        $this->operations[] = ['op' => 'resize', 'width' => $width, 'height' => $height, 'mode' => $mode->value];

        return $this;
    }

    /** Never enlarge: resize operations keep images that are already small enough. */
    public function onlyScaleDown(bool $enabled = true): self
    {
        $this->onlyScaleDown = $enabled;

        return $this;
    }

    public function crop(int $x, int $y, int $width, int $height): self
    {
        if ($x < 0 || $y < 0 || $width < 1 || $height < 1 || $width > 65535 || $height > 65535) {
            throw new InvalidArgumentException('Crop rectangle is invalid.');
        }
        $this->operations[] = ['op' => 'crop', 'x' => $x, 'y' => $y, 'width' => $width, 'height' => $height];

        return $this;
    }

    /** Clockwise rotation in multiples of 90 degrees (negative values rotate counter-clockwise). */
    public function rotate(int $degrees = 90): self
    {
        if ($degrees % 90 !== 0) {
            throw new InvalidArgumentException('Rotation must be a multiple of 90 degrees.');
        }
        $normalized = (($degrees % 360) + 360) % 360;
        if ($normalized !== 0) {
            $this->operations[] = ['op' => 'rotate', 'degrees' => $normalized];
        }

        return $this;
    }

    public function flip(FlipDirection $direction = FlipDirection::Horizontal): self
    {
        $this->operations[] = ['op' => 'flip', 'direction' => $direction->value];

        return $this;
    }

    public function format(ImageFormat $format, int $quality = 85): self
    {
        if ($quality < 1 || $quality > 100) {
            throw new InvalidArgumentException('Image quality must be between 1 and 100.');
        }
        $this->format = $format;
        $this->quality = $quality;

        return $this;
    }

    /** @internal @return array<string, string|int|bool> */
    public function payload(string $destination): array
    {
        try {
            $operations = json_encode($this->operations, JSON_THROW_ON_ERROR);
        } catch (JsonException $error) {
            throw new InvalidArgumentException($error->getMessage(), previous: $error);
        }

        return [
            'source' => $this->source,
            'destination' => $destination,
            'operations' => $operations,
            'onlyScaleDown' => $this->onlyScaleDown,
            'format' => $this->format->value,
            'quality' => $this->quality,
        ];
    }

    /**
     * Encodes the result atomically into a sandbox path (metadata is not copied).
     *
     * @param Closure(ImageResult): void $then
     * @param null|Closure(string): void $failed
     */
    public function save(string $destination, Closure $then, ?Closure $failed = null): int
    {
        MediaPath::assert($destination);

        return NativeModules::call(self::MODULE, 'imageProcess', $this->payload($destination), static function (NativeModuleResult $result) use ($then, $failed): void {
            if (!$result->succeeded()) {
                $failed?->__invoke($result->message());

                return;
            }
            $values = $result->values();
            $then(new ImageResult(
                (string) ($values['path'] ?? ''),
                (int) ($values['width'] ?? 0),
                (int) ($values['height'] ?? 0),
                (int) ($values['bytes'] ?? 0),
                (string) ($values['mimeType'] ?? ''),
            ));
        });
    }

    /**
     * Reads dimensions and EXIF orientation without decoding pixels.
     *
     * @param Closure(ImageInfo): void $then
     * @param null|Closure(string): void $failed
     */
    public function probe(Closure $then, ?Closure $failed = null): int
    {
        return NativeModules::call(self::MODULE, 'imageProbe', ['path' => $this->source], static function (NativeModuleResult $result) use ($then, $failed): void {
            if (!$result->succeeded()) {
                $failed?->__invoke($result->message());

                return;
            }
            $values = $result->values();
            $then(new ImageInfo(
                (int) ($values['width'] ?? 0),
                (int) ($values['height'] ?? 0),
                (int) ($values['storedWidth'] ?? 0),
                (int) ($values['storedHeight'] ?? 0),
                ExifOrientation::tryFrom((int) ($values['orientation'] ?? 1)) ?? ExifOrientation::Normal,
                (string) ($values['mimeType'] ?? 'application/octet-stream'),
                (int) ($values['bytes'] ?? 0),
            ));
        });
    }
}
