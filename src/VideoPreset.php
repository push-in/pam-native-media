<?php

declare(strict_types=1);

namespace Pam\Native\Media;

/**
 * Upload-ready H.264/AAC MP4 profiles. Every preset keeps the display
 * orientation and aspect ratio, never upscales, uses even dimensions,
 * a 2 s keyframe interval and AAC-LC stereo at 44.1/48 kHz.
 */
enum VideoPreset: int
{
    /** ≤ 854×480, ~0.8 Mbps video, 96 kbps audio: data-saver sharing. */
    case Compact480p = 1;
    /** ≤ 1280×720, ~1.5 Mbps video, 128 kbps audio: chat messages. */
    case Chat720p = 2;
    /** ≤ 1920×1080, ~2.5 Mbps video, 128 kbps audio: feed posts. */
    case Chat1080p = 3;
    /** 1080p up to three minutes, 720p for longer videos. */
    case Adaptive = 4;
}
