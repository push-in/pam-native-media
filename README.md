# PAM Native Media

Inspect images, audio and video and generate correctly oriented thumbnails without loading media bytes into PHP memory.

```bash
composer require pushinbr/pam-native-media
pam mobile codegen
pam mobile ios:prepare
```

```php
$media = new Pam\Native\Media\Media();
$media->probe('media/clip.mp4', function (?Pam\Native\Media\MediaInfo $info, ?string $error): void {});
$media->thumbnail('media/clip.mp4', 'thumbs/clip.jpg', 640, 360, function (?string $path, ?string $error): void {});
```

Android uses platform codecs plus ExifInterface `1.4.2`; iOS uses AVFoundation and ImageIO. All paths are relative to Application Support/files storage and are canonicalized natively. Images honor EXIF orientation and video thumbnails honor track transforms.

## Embedded camera

`CameraView` renders a lifecycle-aware native camera preview. Capture commands
are revision based, so a declarative re-render never repeats an operation:

```php
$camera = CameraView::make()
    ->facing(CameraFacing::Back)
    ->flash(CameraFlashMode::Off)
    ->captureRevision($photoRevision)
    ->recordRevision($recordRevision)
    ->stopRevision($stopRevision)
    ->onEvent(function (CameraEventKind $event, ?CameraCapture $capture, string $message): void {
        if ($event === CameraEventKind::Captured && $capture !== null) {
            // $capture->path is an app-owned temporary JPEG.
        }
    });
```

The view supports front/back lenses, off/on/auto photo flash, torch while
recording, optional audio, bounded recording duration and explicit stop. Apps
must request camera/microphone permission before enabling the view. Captured
files live in the app cache and should be moved or deleted by the caller.

Platform support: Android API 26+, iOS 15+, PAM Native 0.6.x.
