<?php
declare(strict_types=1);
namespace Pam\Native\Media;
final readonly class CameraCapture { public function __construct(public string $path,public string $mimeType,public int $width,public int $height,public int $durationMillis){} }
