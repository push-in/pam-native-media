import Foundation

// Mirror of android/src/test/kotlin/dev/pam/media/CameraZoomRangeTest.kt.
func near(_ a: Double, _ b: Double) -> Bool { abs(a - b) < 0.0001 }

// maxZoom caps the hardware range and never inverts it.
precondition(CameraZoomRange.create(hardwareMinimum: 1, hardwareMaximum: 8, maxZoom: 16) == CameraZoomRange(minimum: 1, maximum: 8))
precondition(CameraZoomRange.create(hardwareMinimum: 0.5, hardwareMaximum: 10, maxZoom: 4) == CameraZoomRange(minimum: 0.5, maximum: 4))
precondition(CameraZoomRange.create(hardwareMinimum: 2, hardwareMaximum: 8, maxZoom: 1) == CameraZoomRange(minimum: 2, maximum: 2))
precondition(CameraZoomRange.create(hardwareMinimum: 1, hardwareMaximum: 8, maxZoom: .nan) == CameraZoomRange(minimum: 1, maximum: 8))
precondition(CameraZoomRange.create(hardwareMinimum: .nan, hardwareMaximum: 0, maxZoom: 16) == CameraZoomRange(minimum: 1, maximum: 1))

// Reset targets 1x inside the range.
precondition(CameraZoomRange(minimum: 0.5, maximum: 8).resetValue == 1)
precondition(CameraZoomRange(minimum: 2, maximum: 8).resetValue == 2)
precondition(CameraZoomRange(minimum: 0.5, maximum: 0.8).resetValue == 0.8)

// Pinch multiplies the start factor by the cumulative recognizer scale and clamps.
let range = CameraZoomRange(minimum: 1, maximum: 4)
let inside = range.pinch(start: 1.5, scale: 2)
precondition(near(inside.zoom, 3) && near(inside.rebasedScale, 2))
let over = range.pinch(start: 2, scale: 10)
precondition(over.zoom == 4 && near(over.rebasedScale, 2))
// After rebasing, pinching back from the clamp reacts immediately.
let back = range.pinch(start: 2, scale: over.rebasedScale * 0.5)
precondition(near(back.zoom, 2))
let under = range.pinch(start: 2, scale: 0.1)
precondition(under.zoom == 1 && near(under.rebasedScale, 0.5))
precondition(range.pinch(start: 2, scale: .nan).zoom == 2)
precondition(range.clamp(.infinity) == 1)

print("camera zoom contracts passed")
