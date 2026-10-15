import Foundation

/// Effective zoom bounds: the device range capped by the `maxZoom` property. Pure, no AVFoundation.
struct CameraZoomRange: Equatable {
    static let defaultMaxZoom = 16.0

    let minimum: Double
    let maximum: Double

    static func create(hardwareMinimum: Double, hardwareMaximum: Double, maxZoom: Double) -> CameraZoomRange {
        let minimum = hardwareMinimum.isFinite && hardwareMinimum > 0 ? hardwareMinimum : 1
        let hardware = hardwareMaximum.isFinite && hardwareMaximum >= minimum ? hardwareMaximum : minimum
        let requested = maxZoom.isFinite && maxZoom > 0 ? maxZoom : defaultMaxZoom
        return CameraZoomRange(minimum: minimum, maximum: min(hardware, max(minimum, requested)))
    }

    func clamp(_ value: Double) -> Double {
        value.isFinite ? min(maximum, max(minimum, value)) : minimum
    }

    /// `zoomResetRevision` target: 1x when the lens supports it.
    var resetValue: Double { clamp(1) }

    /// UIPinchGestureRecognizer.scale is cumulative since the gesture began. Returns the zoom to apply
    /// and the recognizer scale to write back, so pinching back from a clamp reacts immediately.
    func pinch(start: Double, scale: Double) -> (zoom: Double, rebasedScale: Double) {
        let base = start.isFinite && start > 0 ? start : 1
        guard scale.isFinite, scale > 0 else { return (clamp(base), 1) }
        let zoom = clamp(base * scale)
        return (zoom, zoom / base)
    }
}
