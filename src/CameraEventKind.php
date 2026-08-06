<?php
declare(strict_types=1);
namespace Pam\Native\Media;
enum CameraEventKind:int { case Ready=1; case Captured=2; case RecordingStarted=3; case RecordingStopped=4; case PermissionDenied=5; case Failure=6; }
