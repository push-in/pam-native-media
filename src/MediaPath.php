<?php

declare(strict_types=1);

namespace Pam\Native\Media;

use InvalidArgumentException;

/** @internal PAM sandbox-relative paths (the `FileReference::$path` space). */
final class MediaPath
{
    private function __construct()
    {
    }

    public static function assert(string $path): void
    {
        if ($path === '' || strlen($path) > 1024 || str_contains($path, "\0") || str_starts_with($path, '/') || str_contains($path, '\\')) {
            throw new InvalidArgumentException('Media paths must be relative sandbox paths.');
        }
        foreach (explode('/', $path) as $segment) {
            if ($segment === '' || $segment === '.' || $segment === '..') {
                throw new InvalidArgumentException('Media paths must be relative sandbox paths.');
            }
        }
    }

    /** Sandbox path, or an HTTPS URL for remote video frames. */
    public static function assertSource(string $source): void
    {
        if (str_starts_with($source, 'https://')) {
            if (strlen($source) > 8192 || filter_var($source, FILTER_VALIDATE_URL) === false) {
                throw new InvalidArgumentException('Remote media URL is invalid.');
            }

            return;
        }
        self::assert($source);
    }
}
