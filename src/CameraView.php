<?php
declare(strict_types=1);
namespace Pam\Native\Media;
use Closure;use Pam\Native\Element;use Pam\Native\Internal\Wire;use Pam\Native\Renderable;use Pam\Native\UI\CustomView;
final class CameraView implements Renderable
{
    private array $properties=['facing'=>1,'mode'=>1,'flashMode'=>1,'enabled'=>true,'captureRevision'=>0,'recordRevision'=>0,'stopRevision'=>0,'maxDurationSeconds'=>60,'audioEnabled'=>true,'zoomTarget'=>1.0,'zoomDurationMillis'=>0];private ?Closure $handler=null;
    public static function make():self{return new self();}
    /** Native recording zoom; target is further clamped to the camera's capabilities. */
    public function recordingZoom(float $target, int $durationMillis = 1600): self
    {
        if (!is_finite($target) || $target < 1.0 || $durationMillis < 0 || $durationMillis > 600_000) {
            throw new \InvalidArgumentException('Recording zoom requires a finite target >= 1 and a duration from 0 to 600000 ms.');
        }

        return $this->with('zoomTarget', $target)->with('zoomDurationMillis', $durationMillis);
    }
    public function facing(CameraFacing $value):self{return $this->with('facing',$value->value);}public function mode(CameraMode $value):self{return $this->with('mode',$value->value);}public function flash(CameraFlashMode $value):self{return $this->with('flashMode',$value->value);}public function enabled(bool $value=true):self{return $this->with('enabled',$value);}public function captureRevision(int $value):self{return $this->with('captureRevision',max(0,$value));}public function recordRevision(int $value):self{return $this->with('recordRevision',max(0,$value));}public function stopRevision(int $value):self{return $this->with('stopRevision',max(0,$value));}public function maxDuration(int $seconds):self{return $this->with('maxDurationSeconds',max(1,min(600,$seconds)));}public function audio(bool $value=true):self{return $this->with('audioEnabled',$value);}
    /** @param Closure(CameraEventKind,?CameraCapture,string):void $handler */ public function onEvent(Closure $handler):self{$copy=clone $this;$copy->handler=$handler;return $copy;}
    public function toElement():Element{return CustomView::make('media.camera',$this->properties)->onNativeEvent(function(string $payload):void{$v=Wire::decodeMap($payload);$kind=CameraEventKind::tryFrom((int)($v['event']??6))??CameraEventKind::Failure;$capture=null;if(in_array($kind,[CameraEventKind::Captured,CameraEventKind::RecordingStopped],true)){$capture=new CameraCapture((string)($v['path']??''),(string)($v['mimeType']??''),(int)($v['width']??0),(int)($v['height']??0),(int)($v['durationMillis']??0));}$this->handler?->__invoke($kind,$capture,(string)($v['message']??''));});}
    private function with(string $key,string|int|float|bool $value):self{$copy=clone $this;$copy->properties[$key]=$value;return $copy;}
}
