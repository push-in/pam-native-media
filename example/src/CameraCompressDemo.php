<?php

declare(strict_types=1);

namespace App;

use Pam\Native\Component;
use Pam\Native\Element;
use Pam\Native\FileReference;
use Pam\Native\Media\CameraCapture;
use Pam\Native\Media\CameraEventKind;
use Pam\Native\Media\CameraFacing;
use Pam\Native\Media\CameraMode;
use Pam\Native\Media\CameraView;
use Pam\Native\Media\ImageFormat;
use Pam\Native\Media\ImageInfo;
use Pam\Native\Media\ImageResult;
use Pam\Native\Media\Media;
use Pam\Native\Media\ResizeMode;
use Pam\Native\Media\Thumbnail;
use Pam\Native\Media\ThumbnailResult;
use Pam\Native\Media\TranscodeResult;
use Pam\Native\Media\TranscodeTask;
use Pam\Native\Media\VideoPreset;
use Pam\Native\PermissionDecision;
use Pam\Native\PermissionKind;
use Pam\Native\Style;
use Pam\Native\System\Permissions;
use Pam\Native\UI\Button;
use Pam\Native\UI\Column;
use Pam\Native\UI\Image;
use Pam\Native\UI\Row;
use Pam\Native\UI\SafeAreaView;
use Pam\Native\UI\Screen;
use Pam\Native\UI\Text;
use Pam\Native\UI\View;

/**
 * Capture a photo or a video with the embedded camera, then prepare it for
 * upload: photos become a 1920 px WebP (Zé Chat's outbox rule), videos a
 * 720p fast-start MP4 with a thumbnail.
 */
final class CameraCompressDemo extends Component
{
    private bool $cameraAllowed = false;
    private CameraMode $mode = CameraMode::Photo;
    private CameraFacing $facing = CameraFacing::Back;
    private int $captureRevision = 0;
    private int $recordRevision = 0;
    private int $stopRevision = 0;
    private bool $recording = false;
    private string $status = 'Waiting for camera permission';
    private string $preview = '';
    private ?TranscodeTask $task = null;

    public function boot(): void
    {
        Permissions::requestKind(PermissionKind::Camera, function (PermissionDecision $camera): void {
            Permissions::requestKind(PermissionKind::Microphone, function (PermissionDecision $_microphone) use ($camera): void {
                $this->cameraAllowed = $camera->granted();
                $this->status = $camera->granted() ? 'Ready' : 'Camera permission denied';
            });
        });
    }

    public function render(): Element
    {
        $camera = $this->cameraAllowed
            ? CameraView::make()
                ->facing($this->facing)
                ->mode($this->mode)
                ->maxDuration(30)
                ->captureRevision($this->captureRevision)
                ->recordRevision($this->recordRevision)
                ->stopRevision($this->stopRevision)
                ->onEvent($this->cameraEvent(...))
                ->toElement()
                ->style(new Style(flexGrow: 1))
            : null;

        return Screen::make(
            SafeAreaView::make(
                Column::make(
                    View::make($camera)->style(new Style(height: 360, backgroundColor: 0xFF000000)),
                    Row::make(
                        Button::make($this->mode === CameraMode::Photo ? 'Video mode' : 'Photo mode')->onPress($this->toggleMode(...)),
                        Button::make('Flip')->onPress($this->flip(...)),
                        Button::make($this->shutterLabel())->onPress($this->shutter(...)),
                    )->style(new Style(gap: 8)),
                    Text::make($this->status),
                    $this->task !== null && !$this->task->finished()
                        ? Button::make('Cancel transcode')->onPress(fn () => $this->task?->cancel())
                        : null,
                    $this->preview !== ''
                        ? Image::make((new FileReference($this->preview, 'preview', 'image/*', 0))->uri())
                            ->style(new Style(width: 160, height: 160))
                        : null,
                )->style(new Style(flexGrow: 1, padding: 16, gap: 12)),
            ),
        );
    }

    public function toggleMode(): void
    {
        $this->mode = $this->mode === CameraMode::Photo ? CameraMode::Video : CameraMode::Photo;
    }

    public function flip(): void
    {
        $this->facing = $this->facing === CameraFacing::Back ? CameraFacing::Front : CameraFacing::Back;
    }

    public function shutter(): void
    {
        if ($this->mode === CameraMode::Photo) {
            $this->captureRevision++;          // each bump takes exactly one photo
        } elseif ($this->recording) {
            $this->stopRevision++;
        } else {
            $this->recordRevision++;
        }
    }

    private function shutterLabel(): string
    {
        return match (true) {
            $this->mode === CameraMode::Photo => 'Take photo',
            $this->recording => 'Stop',
            default => 'Record',
        };
    }

    private function cameraEvent(CameraEventKind $event, ?CameraCapture $capture, string $message): void
    {
        match ($event) {
            CameraEventKind::RecordingStarted => $this->recording = true,
            CameraEventKind::RecordingStopped => $this->videoCaptured($capture),
            CameraEventKind::Captured => $this->photoCaptured($capture),
            CameraEventKind::PermissionDenied => $this->status = 'Camera permission denied',
            CameraEventKind::Failure => $this->status = 'Camera error: '.$message,
            CameraEventKind::Ready => null,
        };
    }

    private function photoCaptured(?CameraCapture $capture): void
    {
        if ($capture === null) {
            return;
        }
        $this->status = 'Compressing photo…';
        $destination = 'outbox/'.bin2hex(random_bytes(6)).'.webp';
        Media::image($capture->path)
            ->resize(1920, 1920, ResizeMode::Contain)
            ->onlyScaleDown()
            ->format(ImageFormat::Webp, 82)
            ->save($destination, function (ImageResult $result) use ($capture): void {
                $this->preview = $result->path;
                $this->status = sprintf('WebP %dx%d, %d KB (was %dx%d)', $result->width, $result->height, intdiv($result->bytes, 1024), $capture->width, $capture->height);
                Media::image($result->path)->probe(function (ImageInfo $info): void {
                    $this->status .= ' · orientation '.$info->orientation->name;
                });
            }, function (string $error): void {
                $this->status = 'Compression failed: '.$error;
            });
    }

    private function videoCaptured(?CameraCapture $capture): void
    {
        $this->recording = false;
        if ($capture === null) {
            return;
        }
        $base = 'outbox/'.bin2hex(random_bytes(6));
        Media::thumbnails(
            [Thumbnail::make($capture->path, $base.'.jpg')->size(320, 320)->at(500)],
            function (array $results): void {
                $first = $results[0] ?? null;
                if ($first instanceof ThumbnailResult && $first->succeeded()) {
                    $this->preview = (string) $first->path;
                }
            },
        );
        $this->task = Media::transcode($capture->path)
            ->to($base.'.mp4')
            ->preset(VideoPreset::Chat720p)
            ->fastStart()
            ->progress(function (float $progress): void {
                $this->status = sprintf('Transcoding %d%%', (int) round($progress * 100));
            })
            ->run(function (TranscodeResult $video): void {
                $this->status = sprintf('MP4 %dx%d, %.1f s, %d KB', $video->width, $video->height, $video->durationMillis / 1000, intdiv($video->bytes, 1024));
            }, function (string $error): void {
                $this->status = 'Transcode failed: '.$error;
            });
    }
}
