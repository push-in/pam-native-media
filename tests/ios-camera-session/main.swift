import Foundation

// iOS mirror of Android `fix/camera-enable-bind`: enabled binds, disabled unbinds.
typealias Gate = CameraSessionGate

// A view created disabled (permission gate) does not open the camera.
precondition(Gate.action(enabled: false, released: false, active: false, recording: false) == .none)
// Enabling starts it (authorize + configure + startRunning).
precondition(Gate.action(enabled: true, released: false, active: false, recording: false) == .start)
// Already running: nothing to do.
precondition(Gate.action(enabled: true, released: false, active: true, recording: false) == .none)
// Disabling stops the session and frees the device (indicator light off).
precondition(Gate.action(enabled: false, released: false, active: true, recording: false) == .stop)
// Disabling during a recording waits for the clip, then stops.
precondition(Gate.action(enabled: false, released: false, active: true, recording: true) == .none)
// A released view never starts again and stops when still active.
precondition(Gate.action(enabled: true, released: true, active: false, recording: false) == .none)
precondition(Gate.action(enabled: true, released: true, active: true, recording: true) == .stop)

print("camera session contracts: ok")
