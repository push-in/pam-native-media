import XCTest
// Generated plugin target: PamPlugin<index>PushinbrPamNativeMedia (index = plugin order).
@testable import PamPlugin0PushinbrPamNativeMedia

/// `enabled` drives the capture session like Android's bind/unbind.
final class CameraSessionGateTests: XCTestCase {
    func testDisabledCameraNeverOpensAndEnablingStartsIt() {
        XCTAssertEqual(CameraSessionGate.action(enabled: false, released: false, active: false, recording: false), .none)
        XCTAssertEqual(CameraSessionGate.action(enabled: true, released: false, active: false, recording: false), .start)
        XCTAssertEqual(CameraSessionGate.action(enabled: true, released: false, active: true, recording: false), .none)
    }

    func testDisablingStopsTheSessionAfterAnyRecording() {
        XCTAssertEqual(CameraSessionGate.action(enabled: false, released: false, active: true, recording: false), .stop)
        XCTAssertEqual(CameraSessionGate.action(enabled: false, released: false, active: true, recording: true), .none)
    }

    func testReleasedViewOnlyStops() {
        XCTAssertEqual(CameraSessionGate.action(enabled: true, released: true, active: false, recording: false), .none)
        XCTAssertEqual(CameraSessionGate.action(enabled: true, released: true, active: true, recording: false), .stop)
    }
}
