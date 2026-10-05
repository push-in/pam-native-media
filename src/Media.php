<?php

declare(strict_types=1);

namespace Pam\Native\Media;

use Closure;
use InvalidArgumentException;
use JsonException;
use Pam\Native\Modules\NativeModuleResult;
use Pam\Native\Modules\NativeModules;

final class Media
{
    private const string MODULE = 'media';

    /**
     * Re-encodes a video into an upload-ready MP4.
     *
     * ```php
     * Media::transcode('captures/clip.mov')->to('outbox/clip.mp4')->preset(VideoPreset::Chat720p)
     *     ->run(fn (TranscodeResult $video) => ..., fn (string $error) => ...);
     * ```
     */
    public static function transcode(string $source): PendingTranscode
    {
        return new PendingTranscode($source);
    }

    /**
     * Native image pipeline: `probe()` or resize/crop/rotate/flip/format and `save()`.
     */
    public static function image(string $source): PendingImage
    {
        return new PendingImage($source);
    }

    /**
     * Generates thumbnails for many images/videos in one native call; results keep request order.
     *
     * @param list<Thumbnail> $requests
     * @param Closure(list<ThumbnailResult>): void $then
     */
    public static function thumbnails(array $requests, Closure $then): int
    {
        if ($requests === [] || count($requests) > 100) {
            throw new InvalidArgumentException('A thumbnail batch needs between 1 and 100 requests.');
        }
        $wire = [];
        $sources = [];
        foreach ($requests as $request) {
            if (!$request instanceof Thumbnail) {
                throw new InvalidArgumentException('Thumbnail batches accept Thumbnail requests only.');
            }
            $wire[] = $request->toWire();
            $sources[] = $request->source();
        }
        try {
            $items = json_encode($wire, JSON_THROW_ON_ERROR | JSON_UNESCAPED_SLASHES);
        } catch (JsonException $error) {
            throw new InvalidArgumentException($error->getMessage(), previous: $error);
        }

        return NativeModules::call(self::MODULE, 'thumbnails', ['items' => $items], static function (NativeModuleResult $result) use ($then, $sources): void {
            $rows = [];
            if ($result->succeeded()) {
                try {
                    $decoded = json_decode((string) ($result->values()['results'] ?? '[]'), true, 8, JSON_THROW_ON_ERROR);
                    $rows = is_array($decoded) ? $decoded : [];
                } catch (JsonException) {
                    $rows = [];
                }
            }
            $results = [];
            foreach ($sources as $index => $source) {
                $row = is_array($rows[$index] ?? null) ? $rows[$index] : [];
                $path = isset($row['path']) && is_string($row['path']) && $row['path'] !== '' ? $row['path'] : null;
                $error = $path === null ? (string) ($row['error'] ?? ($result->succeeded() ? 'Thumbnail failed.' : $result->message())) : null;
                $results[] = new ThumbnailResult($source, $path, (int) ($row['width'] ?? 0), (int) ($row['height'] ?? 0), $error);
            }
            $then($results);
        });
    }

    /** @param Closure(?MediaInfo, ?string): void $complete */
    public function probe(string $path, Closure $complete): int
    {
        $this->assertPath($path);
        return NativeModules::call(self::MODULE, 'probe', ['path' => $path], static function (NativeModuleResult $result) use ($complete): void {
            $values = $result->values();
            if (!$result->succeeded()) {
                $complete(null, $result->message());
                return;
            }
            $complete(new MediaInfo(
                MediaKind::tryFrom((int) ($values['kind'] ?? 4)) ?? MediaKind::Unknown,
                (string) ($values['mimeType'] ?? 'application/octet-stream'),
                (int) ($values['bytes'] ?? 0),
                (int) ($values['width'] ?? 0),
                (int) ($values['height'] ?? 0),
                (int) ($values['durationMillis'] ?? 0),
                (int) ($values['orientationDegrees'] ?? 0),
            ), null);
        });
    }

    /** @param Closure(?string, ?string): void $complete */
    public function thumbnail(string $source, string $destination, int $maxWidth, int $maxHeight, Closure $complete, ThumbnailFormat $format = ThumbnailFormat::Jpeg, int $quality = 85, int $timeMillis = 0): int
    {
        $this->assertPath($source);
        $this->assertPath($destination);
        if ($maxWidth < 1 || $maxHeight < 1 || $maxWidth > 8192 || $maxHeight > 8192) {
            throw new InvalidArgumentException('Thumbnail dimensions must be between 1 and 8192.');
        }
        if ($quality < 1 || $quality > 100 || $timeMillis < 0) {
            throw new InvalidArgumentException('Thumbnail quality or time is invalid.');
        }
        return NativeModules::call(self::MODULE, 'thumbnail', [
            'source' => $source, 'destination' => $destination,
            'maxWidth' => $maxWidth, 'maxHeight' => $maxHeight,
            'format' => $format->value, 'quality' => $quality, 'timeMillis' => $timeMillis,
        ], static function (NativeModuleResult $result) use ($complete): void {
            $path = $result->values()['path'] ?? null;
            $complete(is_string($path) ? $path : null, $result->succeeded() ? null : $result->message());
        });
    }

    private function assertPath(string $path): void
    {
        MediaPath::assert($path);
    }
}
