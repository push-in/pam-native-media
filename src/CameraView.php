<?php
declare(strict_types=1);
namespace Pam\Native\Media;
use Closure;use Pam\Native\Element;use Pam\Native\Internal\Wire;use Pam\Native\Renderable;use Pam\Native\UI\CustomView;
final class CameraView implements Renderable
{
    private array $properties=['facing'=>1,'mode'=>1,'flashMode'=>1,'enabled'=>true,'captureRevision'=>0,'recordRevision'=>0,'stopRevision'=>0,'maxDurationSeconds'=>60,'audioEnabled'=>true,'zoomTarget'=>1.0,'zoomDurationMillis'=>0,'pinchZoom'=>false,'maxZoom'=>16.0,'zoomResetRevision'=>0,'doubleTapFlip'=>false,'doubleTapEvents'=>false,'tapToFocus'=>false,'focusX'=>0.5,'focusY'=>0.5,'focusRevision'=>0];private ?Closure $handler=null;
    public static function make():self{return new self();}
    /** Native recording zoom; target is further clamped to the camera's capabilities. */
    public function recordingZoom(float $target, int $durationMillis = 1600): self
    {
        if (!is_finite($target) || $target < 1.0 || $durationMillis < 0 || $durationMillis > 600_000) {
            throw new \InvalidArgumentException('Recording zoom requires a finite target >= 1 and a duration from 0 to 600000 ms.');
        }

        return $this->with('zoomTarget', $target)->with('zoomDurationMillis', $durationMillis);
    }
    /** Native pinch-to-zoom on the preview, clamped to the lens range and maxZoom(). */
    public function pinchZoom(bool $value = true): self { return $this->with('pinchZoom', $value); }

    /** Upper zoom bound for pinch and reset (default 16); the lens maximum still applies. */
    public function maxZoom(float $value): self
    {
        if (!is_finite($value) || $value < 1.0) {
            throw new \InvalidArgumentException('Max zoom must be a finite value >= 1.');
        }

        return $this->with('maxZoom', $value);
    }

    /** Each change after the first render resets zoom to 1x (clamped to the lens range). */
    public function zoomResetRevision(int $value): self { return $this->with('zoomResetRevision', max(0, $value)); }

    /** Double tap flips between the back and front lens natively (not while recording); also reports DoubleTap. */
    public function doubleTapFlip(bool $value = true): self { return $this->with('doubleTapFlip', $value); }

    /** Report CameraEventKind::DoubleTap without flipping. Touches pass through unless a gesture is enabled. */
    public function doubleTapEvents(bool $value = true): self { return $this->with('doubleTapEvents', $value); }
    /** Android: focus/exposure metering on a confirmed single tap; opt-in. */
    public function tapToFocus(bool $value = true): self { return $this->with('tapToFocus', $value); }

    /** Android: normalized preview coordinates [0,1]; bump revision to focus again. */
    public function focusAt(float $x, float $y, int $revision): self
    {
        if (!is_finite($x) || !is_finite($y) || $x < 0 || $x > 1 || $y < 0 || $y > 1 || $revision < 1) {
            throw new \InvalidArgumentException('Focus requires coordinates in [0,1] and a positive revision.');
        }
        return $this->with('focusX', $x)->with('focusY', $y)->with('focusRevision', $revision);
    }
    public function facing(CameraFacing $value):self{return $this->with('facing',$value->value);}public function mode(CameraMode $value):self{return $this->with('mode',$value->value);}public function flash(CameraFlashMode $value):self{return $this->with('flashMode',$value->value);}public function enabled(bool $value=true):self{return $this->with('enabled',$value);}public function captureRevision(int $value):self{return $this->with('captureRevision',max(0,$value));}public function recordRevision(int $value):self{return $this->with('recordRevision',max(0,$value));}public function stopRevision(int $value):self{return $this->with('stopRevision',max(0,$value));}public function maxDuration(int $seconds):self{return $this->with('maxDurationSeconds',max(1,min(600,$seconds)));}public function audio(bool $value=true):self{return $this->with('audioEnabled',$value);}
    /** @param Closure(CameraEventKind,?CameraCapture,string,?CameraLens):void $handler CameraLens is set for Ready and DoubleTap. */ public function onEvent(Closure $handler):self{$copy=clone $this;$copy->handler=$handler;return $copy;}
    public function toElement():Element{return CustomView::make('media.camera',$this->properties)->onNativeEvent(function(string $payload):void{$v=Wire::decodeMap($payload);$kind=CameraEventKind::tryFrom((int)($v['event']??6))??CameraEventKind::Failure;$capture=null;if(in_array($kind,[CameraEventKind::Captured,CameraEventKind::RecordingStopped],true)){$capture=new CameraCapture((string)($v['path']??''),(string)($v['mimeType']??''),(int)($v['width']??0),(int)($v['height']??0),(int)($v['durationMillis']??0));}$lens=null;if(in_array($kind,[CameraEventKind::Ready,CameraEventKind::DoubleTap],true)){$lens=new CameraLens(CameraFacing::tryFrom((int)($v['facing']??1))??CameraFacing::Back,(float)($v['minZoom']??0.0),(float)($v['maxZoom']??0.0),(bool)($v['hasFlash']??false));}$this->handler?->__invoke($kind,$capture,(string)($v['message']??''),$lens);});}
    private function with(string $key,string|int|float|bool $value):self{$copy=clone $this;$copy->properties[$key]=$value;return $copy;}
}
