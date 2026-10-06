<?php
declare(strict_types=1);
namespace Pam\Native\Media;

enum CameraMode:int
{
    case Photo=1;
    case Video=2;
    /** Photo capture plus hold-to-record video on one session (falls back to photo-only where the device rejects the combined streams). */
    case PhotoVideo=3;
}
