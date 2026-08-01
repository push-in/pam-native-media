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

Platform support: Android API 26+, iOS 15+, PAM Native 0.6.x.
