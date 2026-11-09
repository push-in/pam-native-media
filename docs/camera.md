# Embedded camera

> Moved out of the README; nothing was removed.


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
and explicit stop. `Photo` and `Video` bind only the CameraX/AVFoundation
outputs they need. `PhotoVideo` binds photo and video together for a shutter
that takes a photo on tap and records while held; devices that reject the
combined stream configuration fall back to the photo pipeline and a record
command then reports `Failure`. Android uses CameraX's texture-compatible preview
pipeline so controls can be composited above the camera and reactive updates do
not abandon its surface. The preview host is touch-transparent; apps retain
ownership of declarative shutter, mode, flash, and lens controls. Apps
must request camera/microphone permission before enabling the view. Both
platforms persist captures under the PAM file sandbox and return relative paths.
Construct a `FileReference` from the capture metadata to preview, edit, upload,
or delete the file with the standard PAM APIs.

Optional recording zoom runs entirely on the native camera:

```php
$camera = CameraView::make()
    ->mode(CameraMode::Video)
    ->recordingZoom(2.8, 1600)
    ->maxDuration(3)
    ->recordRevision($recordRevision)
    ->stopRevision($stopRevision);
```

The ramp starts at `RecordingStarted`, from 1× to the requested target over the
specified milliseconds. The camera's zoom range bounds the target; stopping,
disabling, changing lenses/modes or releasing the view cancels and resets zoom.
The default target `1.0` and duration `0` leave zoom disabled. Targets must be
finite and at least `1.0`; durations accept `0`–`600000` milliseconds, with zero
disabling the animation. Direct `media.camera` hosts use the additive
`zoomTarget` (decimal) and `zoomDurationMillis` (integer) properties. Changing
these values affects the next recording. Existing camera event integers stay
unchanged. Android uses CameraX with a native animator, and iOS uses the
AVFoundation zoom ramp; no animation frames cross the PHP bridge.

## Pinch zoom, zoom reset and double tap

Gestures are opt-in and handled natively; no gesture frames cross the bridge:

```php
$camera = CameraView::make()
    ->pinchZoom()                       // pinch on the preview
    ->maxZoom(8.0)                      // default 16; the lens maximum still applies
    ->zoomResetRevision($zoomReset)     // bump to return to 1x
    ->doubleTapFlip()                   // double tap switches back/front lens
    ->onEvent(function (CameraEventKind $event, ?CameraCapture $capture, string $message, ?CameraLens $lens): void {
        if ($event === CameraEventKind::Ready) {
            // $lens->facing, $lens->minZoom, $lens->maxZoom (effective, maxZoom applied)
        }
    });
```

- **Pinch** (`pinchZoom`, default off): zoom = ratio at gesture start × scale
  accumulated since the start, clamped to the lens minimum and
  `min(maxZoom, lens maximum)`. Android uses `ScaleGestureDetector` with CameraX
  `setZoomRatio`; iOS uses `UIPinchGestureRecognizer` with `videoZoomFactor`.
  A pinch during a `recordingZoom()` ramp cancels the ramp and keeps the user's
  zoom (it is not reset to 1× when that recording stops).
- **Reset** (`zoomResetRevision`): each change after the first render sets zoom
  to 1× clamped to the effective range and cancels a running recording ramp.
- **Ready** (`CameraEventKind::Ready`, 1) now carries a `CameraLens` with the
  facing and the effective `minZoom`/`maxZoom`. It is sent whenever the camera
  is bound (again after a lens or mode change).
- **Double tap** (`CameraEventKind::DoubleTap`, 7): reported when
  `doubleTapFlip()` or `doubleTapEvents()` is on. With `doubleTapFlip()` the
  view switches lens natively (not while recording, which would end the clip);
  the event then reports the new facing with `minZoom`/`maxZoom` `0`, and the
  new range follows in `Ready`. The host's `facing()` value takes over again on
  its next change.
- Touches pass through the camera view unless `pinchZoom`, `doubleTapFlip` or
  `doubleTapEvents` is on. Detectors/recognizers exist only while the view is
  enabled and one of them is on, and are dropped on disable and release.

Direct `media.camera` hosts use the additive `pinchZoom`, `doubleTapFlip`,
`doubleTapEvents` (flags), `maxZoom` (decimal) and `zoomResetRevision`
(integer) properties; events gain `facing` (integer) and `minZoom`/`maxZoom`
(decimals). `onEvent` handlers with three parameters keep working.

Platform support: Android API 26+, iOS 15+, PAM Native `>=1.0.35 <2.0.0`.


## Android camera focus and clipping (0.7.2)

> Translated from Portuguese; content unchanged.

```php
$camera = CameraView::make()
    ->tapToFocus()                  // confirmed single tap: focus + AF/AE/AWB metering
    ->focusAt(0.25, 0.75, $revision) // optional command; increment the positive revision
    ->onEvent(function (CameraEventKind $event, ?CameraCapture $capture, string $message, ?CameraLens $lens): void {
        if ($event === CameraEventKind::Ready) {
            // $lens->hasFlash, $lens->hasTorch, $lens->minZoom and $lens->maxZoom
        }
    });
```

`focusAt()` takes normalized coordinates between 0 and 1 relative to the visible
preview. CameraX applies rotation, mirroring and crop. An increasing revision runs
once, including on the first render; the preview must be ready and enabled.
Invalid values throw `InvalidArgumentException`. Native failures arrive as
`Failure`; lenses without metering support ignore the request. Metering expires
automatically after five seconds. `tapToFocus` is off by default and shares the
detector with double tap; it does not send gesture frames to PHP. Disabling or
releasing the camera removes the detectors and releases the existing session.

Android keeps `PreviewView.PERFORMANCE` for surfaces without clipping. If the
host or an ancestor has a rounded outline or `clipToOutline`, it uses
`COMPATIBLE` (TextureView), honoring the radius set by the layout, including 18.
The switch requests a new surface without rebinding or stopping a recording.

`Ready` reports `hasFlash` and `hasTorch` of the active lens (again on every lens
change) and the effective zoom limits, already
bounded by `maxZoom()`. Older hosts omit `hasFlash`/`hasTorch`, read as
`false`; the `CameraLens` constructor still accepts three arguments.
Tap to focus is implemented **only on Android**; `hasFlash`/`hasTorch`
come on both platforms since 0.7.3.
Direct hosts use `tapToFocus` (bool), `focusX`/`focusY` (normalized decimals)
and `focusRevision` (positive integer).
