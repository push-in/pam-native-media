import AVFoundation
import CoreGraphics
import ImageIO
import PamNative
import UniformTypeIdentifiers
import XCTest
// Generated plugin target: PamPlugin<index>PushinbrPamNativeMedia (index = plugin order).
@testable import PamPlugin0PushinbrPamNativeMedia

/// XCTest mirror of MediaGeometryTest + MediaModuleTest. Uncompiled — needs Mac validation.
final class MediaModuleTests: XCTestCase {
    private let module = MediaModule()
    private let root = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        .appendingPathComponent("pam-files/media-tests")

    override func setUpWithError() throws {
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
    }

    override func tearDown() {
        try? FileManager.default.removeItem(at: root)
        super.tearDown()
    }

    private func call(_ method: String, _ values: [String: WireValue], timeout: TimeInterval = 20) -> (ok: Bool, values: [String: WireValue], message: String) {
        let done = expectation(description: method)
        var result: (Bool, [String: WireValue], String) = (false, [:], "")
        module.invoke(method: method, payload: (try? WireMap.encode(values)) ?? Data()) { status, payload in
            result = (status == .success, (try? WireMap.decode(payload)) ?? [:], String(decoding: payload, as: UTF8.self))
            done.fulfill()
        }
        wait(for: [done], timeout: timeout)
        return result
    }

    /// 4000x3000 JPEG with EXIF orientation 6 (stored landscape, displayed portrait).
    private func writeJpeg(_ name: String, width: Int = 400, height: Int = 300, orientation: Int = 1) throws {
        let context = CGContext(data: nil, width: width, height: height, bitsPerComponent: 8, bytesPerRow: 0,
                                space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue)!
        context.setFillColor(CGColor(red: 1, green: 0, blue: 0, alpha: 1))
        context.fill(CGRect(x: 0, y: 0, width: width / 2, height: height))
        let image = context.makeImage()!
        let url = root.appendingPathComponent(name)
        let destination = CGImageDestinationCreateWithURL(url as CFURL, UTType.jpeg.identifier as CFString, 1, nil)!
        CGImageDestinationAddImage(destination, image, [kCGImagePropertyOrientation: orientation] as CFDictionary)
        XCTAssertTrue(CGImageDestinationFinalize(destination))
    }

    func testResizeGeometryMatchesAndroid() {
        XCTAssertEqual(ImagePipeline.resizePlan(width: 4000, height: 3000, box: (1000, 1000, 1), onlyScaleDown: true),
                       .init(scaledWidth: 1000, scaledHeight: 750, cropWidth: 1000, cropHeight: 750))
        XCTAssertEqual(ImagePipeline.resizePlan(width: 4000, height: 3000, box: (300, 300, 2), onlyScaleDown: true),
                       .init(scaledWidth: 400, scaledHeight: 300, cropWidth: 300, cropHeight: 300))
        XCTAssertEqual(ImagePipeline.requiredScale(width: 4000, height: 3000, operations: [.crop(x: 0, y: 0, width: 2000, height: 2000), .resize(width: 1000, height: 1000, mode: 1)], onlyScaleDown: true), 0.5)
        XCTAssertEqual(VideoTranscoder.target(width: 3840, height: 2160, durationMillis: 1_000, options: TranscodeOptions(preset: 4, maxBitrate: 0, fastStart: true, audio: true)),
                       .init(width: 1920, height: 1080, bitrate: 2_500_000, audioBitrate: 128_000))
        XCTAssertEqual(VideoTranscoder.target(width: 1920, height: 1080, durationMillis: 600_000, options: TranscodeOptions(preset: 4, maxBitrate: 1_200_000, fastStart: true, audio: true)).bitrate, 1_200_000)
        XCTAssertEqual(try TranscodeOptions.parse(#"{"preset":2,"maxBitrate":1200000,"fastStart":true}"#), TranscodeOptions(preset: 2, maxBitrate: 1_200_000, fastStart: true, audio: true))
    }

    func testImageProbeReportsDisplayAndStoredDimensions() throws {
        try writeJpeg("rotated.jpg", orientation: 6)
        let probe = call("imageProbe", ["path": .text("media-tests/rotated.jpg")])
        XCTAssertTrue(probe.ok, probe.message)
        XCTAssertEqual(probe.values["width"], .integer(300))
        XCTAssertEqual(probe.values["height"], .integer(400))
        XCTAssertEqual(probe.values["storedWidth"], .integer(400))
        XCTAssertEqual(probe.values["orientation"], .integer(6))
    }

    func testImageProcessResizesCropsRotatesAndEncodes() throws {
        try writeJpeg("source.jpg", width: 400, height: 300)
        let result = call("imageProcess", [
            "source": .text("media-tests/source.jpg"), "destination": .text("media-tests/out.png"),
            "operations": .text(#"[{"op":"crop","x":0,"y":0,"width":300,"height":300},{"op":"resize","width":100,"height":100,"mode":1},{"op":"rotate","degrees":90}]"#),
            "onlyScaleDown": .flag(true), "format": .integer(2), "quality": .integer(90),
        ])
        XCTAssertTrue(result.ok, result.message)
        XCTAssertEqual(result.values["width"], .integer(100))
        XCTAssertEqual(result.values["height"], .integer(100))
        XCTAssertEqual(result.values["mimeType"], .text("image/png"))
        XCTAssertFalse(call("imageProcess", [
            "source": .text("../escape.jpg"), "destination": .text("media-tests/x.jpg"),
            "operations": .text("[]"), "format": .integer(1), "quality": .integer(80),
        ]).ok)
    }

    func testThumbnailBatchReportsPerItemResults() throws {
        try writeJpeg("a.jpg")
        let items = #"[{"source":"media-tests/a.jpg","destination":"media-tests/a-thumb.jpg","maxWidth":100,"maxHeight":100},{"source":"media-tests/missing.jpg","destination":"media-tests/b.jpg","maxWidth":100,"maxHeight":100}]"#
        let result = call("thumbnails", ["items": .text(items)])
        XCTAssertTrue(result.ok, result.message)
        guard case let .text(json)? = result.values["results"],
              let rows = try JSONSerialization.jsonObject(with: Data(json.utf8)) as? [[String: Any]] else {
            return XCTFail("results")
        }
        XCTAssertEqual(rows.count, 2)
        XCTAssertEqual(rows[0]["width"] as? Int, 100)
        XCTAssertNotNil(rows[1]["error"])
    }

    func testTranscodeProducesBoundedMp4WithProgress() throws {
        let source = root.appendingPathComponent("clip.mov")
        try makeVideo(source, width: 1280, height: 720, frames: 30)
        let start = call("transcodeStart", [
            "source": .text("media-tests/clip.mov"), "destination": .text("media-tests/clip.mp4"),
            "options": .text(#"{"preset":1,"fastStart":true}"#),
        ])
        XCTAssertTrue(start.ok, start.message)
        guard case let .integer(task)? = start.values["task"] else { return XCTFail("task") }
        var final: [String: WireValue] = [:]
        for _ in 0..<200 {
            let next = call("transcodeNext", ["task": .integer(task)], timeout: 30)
            XCTAssertTrue(next.ok, next.message)
            if next.values["state"] != .integer(1) {
                final = next.values
                break
            }
        }
        XCTAssertEqual(final["state"], .integer(2), "\(final)")
        XCTAssertEqual(final["width"], .integer(852))
        XCTAssertEqual(final["height"], .integer(480))
        XCTAssertEqual(final["mimeType"], .text("video/mp4"))
        XCTAssertTrue(FileManager.default.fileExists(atPath: root.appendingPathComponent("clip.mp4").path))
        XCTAssertTrue(NSClassFromString("PamMediaTranscoding") != nil)
    }

    private func makeVideo(_ url: URL, width: Int, height: Int, frames: Int) throws {
        let writer = try AVAssetWriter(outputURL: url, fileType: .mov)
        let input = AVAssetWriterInput(mediaType: .video, outputSettings: [
            AVVideoCodecKey: AVVideoCodecType.h264, AVVideoWidthKey: width, AVVideoHeightKey: height,
        ])
        let adaptor = AVAssetWriterInputPixelBufferAdaptor(assetWriterInput: input, sourcePixelBufferAttributes: [
            kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA,
            kCVPixelBufferWidthKey as String: width, kCVPixelBufferHeightKey as String: height,
        ])
        writer.add(input)
        writer.startWriting()
        writer.startSession(atSourceTime: .zero)
        for index in 0..<frames {
            while !input.isReadyForMoreMediaData { Thread.sleep(forTimeInterval: 0.01) }
            var buffer: CVPixelBuffer?
            CVPixelBufferPoolCreatePixelBuffer(nil, adaptor.pixelBufferPool!, &buffer)
            adaptor.append(buffer!, withPresentationTime: CMTime(value: CMTimeValue(index), timescale: 30))
        }
        input.markAsFinished()
        let done = expectation(description: "video")
        writer.finishWriting { done.fulfill() }
        wait(for: [done], timeout: 10)
    }
}
