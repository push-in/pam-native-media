<?php
declare(strict_types=1);
namespace Pam\Native\Media;
/** Lens reported by Ready and DoubleTap. Zoom bounds are effective (maxZoom already applied); 0 means not known yet. */
final readonly class CameraLens { public function __construct(public CameraFacing $facing,public float $minZoom,public float $maxZoom){} }
