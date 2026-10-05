import AVFoundation
import Foundation

struct TranscodeOptions: Equatable {
    let preset: Int
    let maxBitrate: Int
    let fastStart: Bool
    let audio: Bool

    static func parse(_ json: String) throws -> TranscodeOptions {
        guard let root = try JSONSerialization.jsonObject(with: Data(json.utf8)) as? [String: Any] else {
            throw MediaFailure("Invalid transcode options")
        }
        let preset = (root["preset"] as? NSNumber)?.intValue ?? 4
        guard (1...4).contains(preset) else { throw MediaFailure("Unknown video preset") }
        return TranscodeOptions(
            preset: preset,
            maxBitrate: min(max((root["maxBitrate"] as? NSNumber)?.intValue ?? 0, 0), 50_000_000),
            fastStart: (root["fastStart"] as? Bool) ?? true,
            audio: (root["audio"] as? Bool) ?? true
        )
    }
}

struct TranscodeOutput {
    let url: URL
    let width: Int
    let height: Int
    let durationMillis: Int64
    let bitrate: Int
    let fastStart: Bool

    var json: [String: Any] {
        [
            "path": url.path, "mimeType": "video/mp4", "width": width, "height": height,
            "durationMillis": durationMillis,
            "bytes": (try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0,
            "bitrate": bitrate, "fastStart": fastStart,
        ]
    }
}

/// Re-encodes video into H.264 Main 4.1 / AAC-LC MP4 with the Android preset
/// targets (bounded dimensions, bitrate tiers, 2 s keyframes, stereo audio)
/// using AVAssetReader → AVAssetWriter, so bitrates match Android instead of
/// the much larger AVAssetExportSession presets. `fastStart` writes the
/// `moov` atom first (`shouldOptimizeForNetworkUse`). Blocks the caller.
final class VideoTranscoder {
    static let longVideoMs: Int64 = 5 * 60 * 1_000

    struct Target: Equatable {
        let width: Int
        let height: Int
        let bitrate: Int
        let audioBitrate: Int
    }

    /// Same target table as Android `VideoTranscoder.target` (stored orientation).
    static func target(width: Int, height: Int, durationMillis: Int64, options: TranscodeOptions) -> Target {
        let preset = options.preset == 4 ? (durationMillis > longVideoMs ? 2 : 3) : options.preset
        let (maxLong, maxShort, cap, audio): (Double, Double, Int, Int)
        switch preset {
        case 1: (maxLong, maxShort, cap, audio) = (854, 480, 800_000, 96_000)
        case 2: (maxLong, maxShort, cap, audio) = (1_280, 720, 1_500_000, 128_000)
        default: (maxLong, maxShort, cap, audio) = (1_920, 1_080, 2_500_000, 128_000)
        }
        let longest = Double(max(width, height))
        let shortest = Double(min(width, height))
        let scale = min(1, maxLong / longest, maxShort / shortest)
        let targetWidth = even(Int(Double(width) * scale))
        let targetHeight = even(Int(Double(height) * scale))
        let tier: Int
        switch min(targetWidth, targetHeight) {
        case 1_080...: tier = 2_500_000
        case 720...: tier = 1_500_000
        default: tier = 1_000_000
        }
        var bitrate = min(tier, cap)
        if options.maxBitrate > 0 { bitrate = min(bitrate, options.maxBitrate) }
        return Target(width: targetWidth, height: targetHeight, bitrate: bitrate, audioBitrate: audio)
    }

    static func even(_ value: Int) -> Int { max(2, value - value % 2) }

    func transcode(
        source: URL,
        destination: URL,
        options: TranscodeOptions,
        cancelled: @escaping () -> Bool,
        progress: @escaping (Double) -> Void
    ) throws -> TranscodeOutput {
        let asset = AVURLAsset(url: source, options: [AVURLAssetPreferPreciseDurationAndTimingKey: true])
        guard let videoTrack = asset.tracks(withMediaType: .video).first else {
            throw MediaFailure("The source has no readable video track")
        }
        let natural = videoTrack.naturalSize
        let duration = asset.duration.seconds.isFinite ? asset.duration.seconds : 0
        let durationMillis = Int64(duration * 1_000)
        let target = Self.target(
            width: Int(abs(natural.width)),
            height: Int(abs(natural.height)),
            durationMillis: durationMillis,
            options: options
        )
        let directory = destination.deletingLastPathComponent()
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let staged = directory.appendingPathComponent(".\(destination.lastPathComponent).part.mp4")
        try? FileManager.default.removeItem(at: staged)

        let reader = try AVAssetReader(asset: asset)
        let writer = try AVAssetWriter(outputURL: staged, fileType: .mp4)
        writer.shouldOptimizeForNetworkUse = options.fastStart

        let videoOutput = AVAssetReaderTrackOutput(track: videoTrack, outputSettings: [
            kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange,
        ])
        videoOutput.alwaysCopiesSampleData = false
        guard reader.canAdd(videoOutput) else { throw MediaFailure("Cannot read the video track") }
        reader.add(videoOutput)
        let videoInput = AVAssetWriterInput(mediaType: .video, outputSettings: [
            AVVideoCodecKey: AVVideoCodecType.h264,
            AVVideoWidthKey: target.width,
            AVVideoHeightKey: target.height,
            AVVideoScalingModeKey: AVVideoScalingModeResizeAspectFill,
            AVVideoColorPropertiesKey: [
                AVVideoColorPrimariesKey: AVVideoColorPrimaries_ITU_R_709_2,
                AVVideoTransferFunctionKey: AVVideoTransferFunction_ITU_R_709_2,
                AVVideoYCbCrMatrixKey: AVVideoYCbCrMatrix_ITU_R_709_2,
            ],
            AVVideoCompressionPropertiesKey: [
                AVVideoAverageBitRateKey: target.bitrate,
                AVVideoProfileLevelKey: AVVideoProfileLevelH264Main41,
                AVVideoMaxKeyFrameIntervalDurationKey: 2,
                AVVideoAllowFrameReorderingKey: true,
                AVVideoExpectedSourceFrameRateKey: max(1, Int(videoTrack.nominalFrameRate.rounded())),
            ],
        ])
        // Display orientation is kept as the track transform (players honor it).
        videoInput.transform = videoTrack.preferredTransform
        videoInput.expectsMediaDataInRealTime = false
        guard writer.canAdd(videoInput) else { throw MediaFailure("Cannot encode H.264 video") }
        writer.add(videoInput)

        var audioPair: (AVAssetReaderTrackOutput, AVAssetWriterInput)?
        if options.audio, let audioTrack = asset.tracks(withMediaType: .audio).first {
            let sampleRate = 44_100.0
            let audioOutput = AVAssetReaderTrackOutput(track: audioTrack, outputSettings: [
                AVFormatIDKey: kAudioFormatLinearPCM,
                AVSampleRateKey: sampleRate,
                AVNumberOfChannelsKey: 2,
                AVLinearPCMBitDepthKey: 16,
                AVLinearPCMIsFloatKey: false,
                AVLinearPCMIsBigEndianKey: false,
                AVLinearPCMIsNonInterleaved: false,
            ])
            let audioInput = AVAssetWriterInput(mediaType: .audio, outputSettings: [
                AVFormatIDKey: kAudioFormatMPEG4AAC,
                AVSampleRateKey: sampleRate,
                AVNumberOfChannelsKey: 2,
                AVEncoderBitRateKey: target.audioBitrate,
            ])
            audioInput.expectsMediaDataInRealTime = false
            if reader.canAdd(audioOutput), writer.canAdd(audioInput) {
                reader.add(audioOutput)
                writer.add(audioInput)
                audioPair = (audioOutput, audioInput)
            }
        }

        guard reader.startReading() else { throw MediaFailure(reader.error?.localizedDescription ?? "Cannot read the source") }
        guard writer.startWriting() else { throw MediaFailure(writer.error?.localizedDescription ?? "Cannot start encoding") }
        writer.startSession(atSourceTime: .zero)

        let group = DispatchGroup()
        let state = TranscodeAbort()
        func pump(_ output: AVAssetReaderOutput, _ input: AVAssetWriterInput, label: String, reportsProgress: Bool) {
            group.enter()
            let queue = DispatchQueue(label: "pam.media.transcode.\(label)")
            input.requestMediaDataWhenReady(on: queue) {
                while input.isReadyForMoreMediaData {
                    if state.check(cancelled) {
                        input.markAsFinished()
                        group.leave()
                        return
                    }
                    guard let sample = output.copyNextSampleBuffer() else {
                        input.markAsFinished()
                        group.leave()
                        return
                    }
                    if reportsProgress, duration > 0 {
                        let time = CMSampleBufferGetPresentationTimeStamp(sample).seconds
                        if time.isFinite { progress(min(max(time / duration, 0), 0.99)) }
                    }
                    if !input.append(sample) {
                        state.abort()
                        input.markAsFinished()
                        group.leave()
                        return
                    }
                }
            }
        }
        pump(videoOutput, videoInput, label: "video", reportsProgress: true)
        if let (audioOutput, audioInput) = audioPair {
            pump(audioOutput, audioInput, label: "audio", reportsProgress: false)
        }
        group.wait()

        if state.aborted || cancelled() || reader.status == .failed {
            reader.cancelReading()
            writer.cancelWriting()
            try? FileManager.default.removeItem(at: staged)
            if cancelled() { throw MediaFailure("Transcode cancelled") }
            throw MediaFailure(writer.error?.localizedDescription ?? reader.error?.localizedDescription ?? "Transcode failed")
        }
        let finished = DispatchSemaphore(value: 0)
        writer.finishWriting { finished.signal() }
        finished.wait()
        guard writer.status == .completed else {
            try? FileManager.default.removeItem(at: staged)
            throw MediaFailure(writer.error?.localizedDescription ?? "Transcode failed")
        }
        if FileManager.default.fileExists(atPath: destination.path) {
            _ = try FileManager.default.replaceItemAt(destination, withItemAt: staged)
        } else {
            try FileManager.default.moveItem(at: staged, to: destination)
        }
        progress(1)
        let rotated = abs(videoTrack.preferredTransform.b) == 1 && abs(videoTrack.preferredTransform.c) == 1
        return TranscodeOutput(
            url: destination,
            width: rotated ? target.height : target.width,
            height: rotated ? target.width : target.height,
            durationMillis: durationMillis,
            bitrate: target.bitrate,
            fastStart: options.fastStart
        )
    }
}

/// Thread-safe abort flag shared by the video and audio pumps.
final class TranscodeAbort: @unchecked Sendable {
    private let lock = NSLock()
    private var value = false

    var aborted: Bool {
        lock.lock()
        defer { lock.unlock() }
        return value
    }

    func abort() {
        lock.lock()
        value = true
        lock.unlock()
    }

    /// Latches the abort when [cancelled] reports true.
    func check(_ cancelled: () -> Bool) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        if !value && cancelled() { value = true }
        return value
    }
}

/// Stable Objective-C entry point used by other plugins (notably
/// `pushinbr/pam-native-background-transfer` 0.4+) through
/// `NSClassFromString("PamMediaTranscoding")`. Contract version 1:
/// request `source`, `destination` (absolute paths), `options` (JSON),
/// `cancelled` (block `() -> Bool`), `progress` (block `(Double) -> Void`);
/// result is the TranscodeResult dictionary or `["error": message]`.
@objc(PamMediaTranscoding)
public final class PamMediaTranscoding: NSObject {
    @objc public static let contractVersion = 1

    @objc(transcode:)
    public static func transcode(_ request: NSDictionary) -> NSDictionary {
        typealias CancelBlock = @convention(block) () -> Bool
        typealias ProgressBlock = @convention(block) (Double) -> Void
        guard let source = request["source"] as? String, let destination = request["destination"] as? String else {
            return ["error": "source and destination are required"]
        }
        var cancelled: () -> Bool = { false }
        if let object = request["cancelled"] as AnyObject? {
            let block = unsafeBitCast(object, to: CancelBlock.self)
            cancelled = { block() }
        }
        var progress: (Double) -> Void = { _ in }
        if let object = request["progress"] as AnyObject? {
            let block = unsafeBitCast(object, to: ProgressBlock.self)
            progress = { block($0) }
        }
        do {
            let options = try TranscodeOptions.parse(request["options"] as? String ?? "{}")
            let output = try VideoTranscoder().transcode(
                source: URL(fileURLWithPath: source),
                destination: URL(fileURLWithPath: destination),
                options: options,
                cancelled: cancelled,
                progress: progress
            )
            return output.json as NSDictionary
        } catch {
            return ["error": error.localizedDescription]
        }
    }
}
