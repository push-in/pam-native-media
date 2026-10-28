<?php
declare(strict_types=1);
use Pam\Native\Media\CaptureType;
use Pam\Native\FileReference;
use Pam\Native\Media\MediaAlbum;
use Pam\Native\Media\MediaAsset;
use Pam\Native\Media\MediaAssetPage;
use Pam\Native\MediaPickerType;
use Pam\Native\Internal\Runtime;
use Pam\Native\Internal\Wire;
use Pam\Native\ModuleResultStatus;
use Pam\Native\Media\MediaCapture;
use Pam\Native\Media\MediaLibrary;
use Pam\Native\UI\Image;


$lastCall = static function (): array {
    $call = TestDiagnostics::$moduleCall;
    if ($call === null) {
        throw new RuntimeException('No native module call was recorded.');
    }

    return $call + ['values' => $call['payload'] === '' ? [] : Wire::decodeMap($call['payload'])];
};
$rejects = static function (Closure $operation): bool {
    try {
        $operation();
    } catch (InvalidArgumentException|RuntimeException|LogicException) {
        return true;
    }

    return false;
};


$captureError = null;
$captureSucceeded = false;
$captureRequestId = MediaCapture::capture(
    CaptureType::Photo,
    static function (FileReference $_) use (&$captureSucceeded): void {
        $captureSucceeded = true;
    },
    static function (string $message) use (&$captureError): void {
        $captureError = $message;
    },
);
Runtime::dispatchModuleResult(
    $captureRequestId,
    ModuleResultStatus::Failure->value,
    'Camera is unavailable.',
);
$assert(
    !$captureSucceeded && $captureError === 'Camera is unavailable.',
    'Media capture failures must reach the optional failure callback.',
);



$cancelledCapture = new FileReference('unexpected', 'unexpected', 'text/plain', 1);
$cancelledCaptureRequest = \Pam\Native\Media\MediaCapture::capture(
    \Pam\Native\Media\CaptureType::Photo,
    static function (?FileReference $file) use (&$cancelledCapture): void {
        $cancelledCapture = $file;
    },
);
Runtime::dispatchModuleResult(
    $cancelledCaptureRequest,
    ModuleResultStatus::Success->value,
    '',
);
$assert(
    $cancelledCapture === null,
    'Media capture must resolve user cancellation with null instead of throwing.',
);


$mediaPage = null;
$mediaRequestId = MediaLibrary::assets(
    MediaPickerType::Media,
    static function (MediaAssetPage $page) use (&$mediaPage): void {
        $mediaPage = $page;
    },
    limit: 80,
    offset: 160,
    albumId: 'camera',
);
$mediaCall = TestDiagnostics::$moduleCall;
$assert(
    $mediaCall !== null
        && $mediaCall['requestId'] === $mediaRequestId
        && $mediaCall['module'] === 'media-library'
        && $mediaCall['method'] === 'assets'
        && Wire::decodeMap($mediaCall['payload']) == [
            'albumId' => 'camera',
            'limit' => 80,
            'offset' => 160,
            'type' => MediaPickerType::Media->value,
        ],
    'MediaLibrary assets must emit a bounded paginated native query.',
);
Runtime::dispatchModuleResult(
    $mediaRequestId,
    ModuleResultStatus::Success->value,
    Wire::map([
        'items' => json_encode([[
            'id' => '42',
            'uri' => 'content://media/external/images/media/42',
            'name' => 'photo-42.jpg',
            'mimeType' => 'image/jpeg',
            'width' => 1_080,
            'height' => 1_350,
            'durationMs' => 0,
            'size' => 4_096,
            'createdAt' => 1_785_000_000_000,
            'modifiedAt' => 1_785_000_100_000,
            'albumId' => 'camera',
            'albumTitle' => 'Câmera',
            'favorite' => true,
        ]], JSON_THROW_ON_ERROR),
        'hasMore' => true,
    ]),
);
$assert(
    $mediaPage instanceof MediaAssetPage
        && $mediaPage->hasMore
        && $mediaPage->nextOffset === 161
        && count($mediaPage->items) === 1
        && $mediaPage->items[0] instanceof MediaAsset
        && $mediaPage->items[0]->id === '42'
        && $mediaPage->items[0]->width === 1_080
        && $mediaPage->items[0]->favorite
        && !$mediaPage->items[0]->video(),
    'MediaLibrary assets must decode typed media metadata and pagination.',
);

$mediaAlbums = null;
$albumRequestId = MediaLibrary::albums(
    MediaPickerType::Image,
    static function (array $items) use (&$mediaAlbums): void {
        $mediaAlbums = $items;
    },
);
$albumCall = TestDiagnostics::$moduleCall;
$assert(
    $albumCall !== null
        && $albumCall['requestId'] === $albumRequestId
        && $albumCall['module'] === 'media-library'
        && $albumCall['method'] === 'albums'
        && Wire::decodeMap($albumCall['payload']) == [
            'type' => MediaPickerType::Image->value,
        ],
    'MediaLibrary albums must emit its typed media filter.',
);
Runtime::dispatchModuleResult(
    $albumRequestId,
    ModuleResultStatus::Success->value,
    Wire::map([
        'items' => json_encode([[
            'id' => 'camera',
            'title' => 'Câmera',
            'count' => 42,
            'coverUri' => 'content://media/external/images/media/42',
        ]], JSON_THROW_ON_ERROR),
    ]),
);
$assert(
    is_array($mediaAlbums)
        && count($mediaAlbums) === 1
        && $mediaAlbums[0] instanceof MediaAlbum
        && $mediaAlbums[0]->id === 'camera'
        && $mediaAlbums[0]->count === 42,
    'MediaLibrary albums must decode typed album metadata.',
);



// 4. Media library saving.
$savedAsset = null;
MediaLibrary::save('exports/photo.jpg', album: 'real-world app', callback: static function (MediaAsset $asset) use (&$savedAsset): void { $savedAsset = $asset; });
$save = $lastCall();
$assert(
    $save['module'] === 'media-library' && $save['method'] === 'save'
        && $save['values'] == ['path' => 'exports/photo.jpg', 'album' => 'real-world app', 'mimeType' => '', 'name' => ''],
    'MediaLibrary::save must bridge the private path and album.',
);
Runtime::dispatchModuleResult($save['requestId'], ModuleResultStatus::Success->value, Wire::map([
    'item' => json_encode(['id' => '7', 'uri' => 'content://media/external/images/media/7', 'name' => 'photo.jpg', 'mimeType' => 'image/jpeg', 'size' => 10, 'albumTitle' => 'real-world app']),
]));
$assert($savedAsset instanceof MediaAsset && $savedAsset->id === '7' && $savedAsset->albumTitle === 'real-world app', 'MediaLibrary::save must return the typed saved asset.');
$assert(
    $rejects(static fn () => MediaLibrary::save('a.jpg', album: '../x'))
        && $rejects(static fn () => MediaLibrary::save('a.pdf', mimeType: 'application/pdf')),
    'MediaLibrary::save must reject unsafe albums and non-media MIME types.',
);

$mediaSeen = ['uri' => null, 'thumbFailure' => null, 'pageFailure' => null, 'albumFailure' => null, 'limited' => null, 'page' => null];
MediaLibrary::thumbnail('42', 99_999, static function (string $uri) use (&$mediaSeen): void { $mediaSeen['uri'] = $uri; });
$thumb = $lastCall();
$assert(
    $thumb['module'] === 'media-library' && $thumb['method'] === 'thumbnail'
        && $thumb['values'] == ['id' => '42', 'maxPx' => 1024],
    'MediaLibrary::thumbnail must bridge the asset id and clamp the edge.',
);
Runtime::dispatchModuleResult($thumb['requestId'], ModuleResultStatus::Success->value, Wire::map(['uri' => 'file:///cache/pam-media-thumbnails/a.jpg']));
MediaLibrary::thumbnail('43', 8, static function (string $_): void {}, static function (string $message) use (&$mediaSeen): void { $mediaSeen['thumbFailure'] = $message; });
$assert($lastCall()['values']['maxPx'] === 32, 'MediaLibrary::thumbnail must clamp tiny edges to 32 px.');
Runtime::dispatchModuleResult($lastCall()['requestId'], ModuleResultStatus::Failure->value, 'Media asset not found');
MediaLibrary::assets(MediaPickerType::Media, static function ($page) use (&$mediaSeen): void { $mediaSeen['page'] = $page; });
Runtime::dispatchModuleResult($lastCall()['requestId'], ModuleResultStatus::Success->value, Wire::map([
    'items' => json_encode([['id' => '9', 'uri' => 'content://media/external/file/9', 'thumbnailUri' => 'content://media/external/video/media/9', 'mimeType' => 'video/mp4']]),
    'hasMore' => false,
]));
MediaLibrary::assets(MediaPickerType::Media, static function ($_): void {}, failure: static function (string $message) use (&$mediaSeen): void { $mediaSeen['pageFailure'] = $message; });
Runtime::dispatchModuleResult($lastCall()['requestId'], ModuleResultStatus::Failure->value, 'Photos permission is not granted');
MediaLibrary::albums(MediaPickerType::Media, static function (array $_): void {}, static function (string $message) use (&$mediaSeen): void { $mediaSeen['albumFailure'] = $message; });
Runtime::dispatchModuleResult($lastCall()['requestId'], ModuleResultStatus::Failure->value, 'Photos permission is not granted');
MediaLibrary::refreshLimitedSelection(static function (bool $limited) use (&$mediaSeen): void { $mediaSeen['limited'] = $limited; });
$assert($lastCall()['method'] === 'refreshLimitedSelection', 'refreshLimitedSelection must call the native media library.');
Runtime::dispatchModuleResult($lastCall()['requestId'], ModuleResultStatus::Success->value, Wire::map(['limited' => true]));
$assert(
    $mediaSeen['uri'] === 'file:///cache/pam-media-thumbnails/a.jpg'
        && $mediaSeen['thumbFailure'] === 'Media asset not found'
        && $mediaSeen['page']?->items[0]->thumbnailUri === 'content://media/external/video/media/9'
        && $mediaSeen['pageFailure'] === 'Photos permission is not granted'
        && $mediaSeen['albumFailure'] === 'Photos permission is not granted'
        && $mediaSeen['limited'] === true
        && (new MediaAsset('1', 'u', 'n', 'image/jpeg', 1, 1, 0, 1, 0, 0, '', '', false))->thumbnailUri === ''
        && $rejects(static fn () => MediaLibrary::thumbnail(' ', 64, static function (string $_): void {})),
    'MediaLibrary must expose thumbnails, thumbnailUri, limited reselection and failure callbacks without exceptions.',
);


require __DIR__.'/view_capture.php';
