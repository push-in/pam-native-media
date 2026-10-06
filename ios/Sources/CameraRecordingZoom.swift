import AVFoundation
import Foundation

/** The recording queue owns this helper; AVFoundation performs the ramp natively. */
final class CameraRecordingZoom: @unchecked Sendable {
    private var device: AVCaptureDevice?

    func start(device: AVCaptureDevice?, target: Double, durationMillis: Int64) {
        reset()
        guard let device, target.isFinite, durationMillis > 0 else { return }
        let minimum = max(1, device.minAvailableVideoZoomFactor)
        let maximum = min(device.maxAvailableVideoZoomFactor, device.activeFormat.videoMaxZoomFactor)
        guard maximum >= minimum else { return }
        let end = min(maximum, max(minimum, CGFloat(target)))
        guard end > minimum else { return }
        // AVFoundation rates are powers of two per second, not zoom units/s.
        let seconds = Double(min(600_000, durationMillis)) / 1000
        let rate = Float(log2(Double(end / minimum)) / seconds)
        do {
            try device.lockForConfiguration()
            defer { device.unlockForConfiguration() }
            device.cancelVideoZoomRamp()
            device.videoZoomFactor = minimum
            device.ramp(toVideoZoomFactor: end, withRate: rate)
            self.device = device
        } catch {
            // Capturing remains available when the device cannot lock optional zoom.
            self.device = nil
        }
    }

    func reset() {
        guard let device else { return }
        self.device = nil
        do {
            try device.lockForConfiguration()
            defer { device.unlockForConfiguration() }
            device.cancelVideoZoomRamp()
            device.videoZoomFactor = min(device.maxAvailableVideoZoomFactor, max(1, device.minAvailableVideoZoomFactor))
        } catch {
            // A device removed while closing has no remaining zoom to restore.
        }
    }
}
