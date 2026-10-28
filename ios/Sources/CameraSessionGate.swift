/// Lifecycle of the iOS capture session from `enabled`, mirroring Android's
/// `CameraViewFactory` bind/unbind (`fix/camera-enable-bind`): a disabled
/// camera (hosts gate it on its permission, or hide it behind another
/// screen) must not keep the device open, the camera indicator lit and the
/// sensor drawing power. Pure so it is testable without a camera.
enum CameraSessionAction: Equatable {
    case none
    /// Authorize (once) and configure + start the session.
    case start
    /// Stop the session and release its inputs and outputs.
    case stop
}

enum CameraSessionGate {
    /// - Parameters:
    ///   - enabled: the host's `enabled` property.
    ///   - released: the view was released by the renderer.
    ///   - active: the session is configured (running or starting).
    ///   - recording: a movie file is still being written; it finishes first
    ///     and the stop happens when it is delivered.
    static func action(enabled: Bool, released: Bool, active: Bool, recording: Bool) -> CameraSessionAction {
        if released { return active ? .stop : .none }
        if enabled { return active ? .none : .start }
        return active && !recording ? .stop : .none
    }
}
