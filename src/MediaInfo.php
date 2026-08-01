<?php
declare(strict_types=1);
namespace Pam\Native\Media;
final readonly class MediaInfo {public function __construct(public MediaKind $kind,public string $mimeType,public int $bytes,public int $width,public int $height,public int $durationMillis,public int $orientationDegrees) {}}
