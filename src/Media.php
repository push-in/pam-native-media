<?php

declare(strict_types=1);

namespace Pam\Native\Media;

use Closure;
use InvalidArgumentException;
use Pam\Native\Modules\NativeModuleResult;
use Pam\Native\Modules\NativeModules;

final class Media
{
    private const string MODULE = 'media';

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
        if ($path === '' || strlen($path) > 1024 || str_contains($path, "\0") || str_starts_with($path, '/')) {
            throw new InvalidArgumentException('Media paths must be relative sandbox paths.');
        }
    }
}
