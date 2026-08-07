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
    ->mode(CameraMode::Photo)
    ->flash(CameraFlashMode::Off)
    ->captureRevision($photoRevision)
    ->recordRevision($recordRevision)
    ->stopRevision($stopRevision)
    ->onEvent(function (CameraEventKind $event, ?CameraCapture $capture, string $message): void {
        if ($event === CameraEventKind::Captured && $capture !== null) {
            // $capture->path is relative to the app-owned PAM file sandbox.
        }
    });
```

The view supports front/back lenses, explicit photo/video mode, off/on/auto
photo flash, torch while recording, optional audio, bounded recording duration
and explicit stop. Each mode binds only the CameraX/AVFoundation outputs it
needs, preserving compatibility with devices that reject combined photo and
video stream configurations. Android uses CameraX's texture-compatible preview
pipeline so controls can be composited above the camera and reactive updates do
not abandon its surface. The preview host is touch-transparent; apps retain
ownership of declarative shutter, mode, flash, and lens controls. Apps
must request camera/microphone permission before enabling the view. Both
platforms persist captures under the PAM file sandbox and return relative paths.
Construct a `FileReference` from the capture metadata to preview, edit, upload,
or delete the file with the standard PAM APIs.

Platform support: Android API 26+, iOS 15+, PAM Native 0.6.x.
