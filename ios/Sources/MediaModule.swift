import AVFoundation
import Foundation
import ImageIO
import PamNative
import UniformTypeIdentifiers

public final class MediaModule: NativeModule, ClosableNativeModule, @unchecked Sendable {
    private let work: OperationQueue = {
        let queue = OperationQueue()
        queue.name = "pam-media"
        queue.maxConcurrentOperationCount = 2
        queue.qualityOfService = .userInitiated
        return queue
    }()
    private let lock = NSLock()
    private var transcodes: [Int64: TranscodeJob] = [:]
    private var nextTask: Int64 = 1

    public init() {}

    public func invoke(method: String, payload: Data, completion: @escaping ModuleCompletion) {
        do {
            let values = try WireMap.decode(payload)
            switch method {
            case "transcodeStart":
                try succeed(startTranscode(values), completion)
            case "transcodeNext":
                guard case let .integer(task)? = values["task"], let job = job(task) else {
                    throw MediaFailure("Transcode task not found")
                }
                job.next(completion)
            case "transcodeCancel":
                if case let .integer(task)? = values["task"] { job(task)?.cancel() }
                try succeed([:], completion)
            case "probe", "thumbnail", "imageProbe", "imageProcess", "thumbnails":
                work.addOperation { [weak self] in
                    guard let self else { return }
                    do {
                        switch method {
                        case "probe":
                            guard case let .text(path)? = values["path"] else { throw MediaError.invalidRequest }
                            try self.succeed(self.probe(try self.file(path, true)), completion)
                        case "thumbnail":
                            try self.thumbnail(values, completion)
                        case "imageProbe":
                            guard case let .text(path)? = values["path"] else { throw MediaError.invalidRequest }
                            let info = try ImagePipeline.probe(try self.file(path, true))
                            try self.succeed([
                                "width": .integer(Int64(info.width)), "height": .integer(Int64(info.height)),
                                "storedWidth": .integer(Int64(info.storedWidth)), "storedHeight": .integer(Int64(info.storedHeight)),
                                "orientation": .integer(Int64(info.orientation)), "mimeType": .text(info.mimeType),
                                "bytes": .integer(info.bytes),
                            ], completion)
                        case "imageProcess":
                            try self.succeed(try self.imageProcess(values), completion)
                        default:
                            guard case let .text(items)? = values["items"] else { throw MediaError.invalidRequest }
                            try self.succeed(try self.thumbnails(items), completion)
                        }
                    } catch {
                        completion(.failure, Data(Self.message(error).utf8))
                    }
                }
            default:
                throw MediaError.invalidRequest
            }
        } catch {
            completion(.failure, Data(Self.message(error).utf8))
        }
    }

    public func close() {
        lock.lock()
        let jobs = Array(transcodes.values)
        lock.unlock()
        jobs.forEach { $0.cancel() }
        work.cancelAllOperations()
    }

    static func message(_ error: Error) -> String {
        (error as? LocalizedError)?.errorDescription ?? String(describing: error)
    }

    private func job(_ id: Int64) -> TranscodeJob? {
        lock.lock()
        defer { lock.unlock() }
        return transcodes[id]
    }

    private func startTranscode(_ values: [String: WireValue]) throws -> [String: WireValue] {
        guard case let .text(sourcePath)? = values["source"], case let .text(destinationPath)? = values["destination"] else {
            throw MediaError.invalidRequest
        }
        let source = try file(sourcePath, true)
        let destination = try file(destinationPath, false)
        var optionsJson = "{}"
        if case let .text(value)? = values["options"] { optionsJson = value }
        let options = try TranscodeOptions.parse(optionsJson)
        lock.lock()
        let id = nextTask
        nextTask += 1
        let job = TranscodeJob { [weak self] in
            guard let self else { return }
            self.lock.lock()
            self.transcodes[id] = nil
            self.lock.unlock()
        }
        transcodes[id] = job
        lock.unlock()
        DispatchQueue.global(qos: .userInitiated).async {
            do {
                let output = try VideoTranscoder().transcode(
                    source: source, destination: destination, options: options,
                    cancelled: { job.isCancelled }, progress: { job.progress($0) }
                )
                job.finish([
                    "state": .integer(2), "path": .text(destinationPath), "mimeType": .text("video/mp4"),
                    "width": .integer(Int64(output.width)), "height": .integer(Int64(output.height)),
                    "durationMillis": .integer(output.durationMillis),
                    "bytes": .integer(Int64((try? destination.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0)),
                    "bitrate": .integer(Int64(output.bitrate)), "fastStart": .flag(output.fastStart),
                ])
            } catch {
                job.finish(["state": .integer(3), "message": .text(Self.message(error))])
            }
        }
        return ["task": .integer(id)]
    }

    private func imageProcess(_ values: [String: WireValue]) throws -> [String: WireValue] {
        guard case let .text(sourcePath)? = values["source"], case let .text(destinationPath)? = values["destination"],
              case let .text(operations)? = values["operations"], case let .integer(format)? = values["format"],
              case let .integer(quality)? = values["quality"], (1...3).contains(format) else {
            throw MediaFailure("Invalid image request")
        }
        var onlyScaleDown = false
        if case let .flag(flag)? = values["onlyScaleDown"] { onlyScaleDown = flag }
        let output = try ImagePipeline.process(
            source: try file(sourcePath, true),
            destination: try file(destinationPath, false),
            operations: try ImageOperation.parse(operations),
            onlyScaleDown: onlyScaleDown,
            format: Int(format),
            quality: Int(quality)
        )
        return [
            "path": .text(destinationPath), "width": .integer(Int64(output.width)), "height": .integer(Int64(output.height)),
            "bytes": .integer(output.bytes), "mimeType": .text(output.mimeType),
        ]
    }

    private func thumbnails(_ items: String) throws -> [String: WireValue] {
        guard let requests = try JSONSerialization.jsonObject(with: Data(items.utf8)) as? [[String: Any]],
              (1...100).contains(requests.count) else {
            throw MediaFailure("A thumbnail batch needs between 1 and 100 requests")
        }
        var results: [[String: Any]] = []
        for request in requests {
            let done = DispatchSemaphore(value: 0)
            var row: [String: Any] = [:]
            var values: [String: WireValue] = [:]
            for key in ["source", "destination"] {
                if let text = request[key] as? String { values[key] = .text(text) }
            }
            values["maxWidth"] = .integer((request["maxWidth"] as? NSNumber)?.int64Value ?? 0)
            values["maxHeight"] = .integer((request["maxHeight"] as? NSNumber)?.int64Value ?? 0)
            values["format"] = .integer((request["format"] as? NSNumber)?.int64Value ?? 1)
            values["quality"] = .integer((request["quality"] as? NSNumber)?.int64Value ?? 80)
            values["timeMillis"] = .integer((request["timeMillis"] as? NSNumber)?.int64Value ?? 0)
            do {
                try thumbnail(values) { status, payload in
                    if status == .success, let decoded = try? WireMap.decode(payload) {
                        var result: [String: Any] = ["path": request["destination"] as? String ?? ""]
                        if case let .integer(width)? = decoded["width"] { result["width"] = width }
                        if case let .integer(height)? = decoded["height"] { result["height"] = height }
                        row = result
                    } else {
                        row = ["error": String(decoding: payload, as: UTF8.self)]
                    }
                    done.signal()
                }
                done.wait()
            } catch {
                row = ["error": Self.message(error)]
            }
            results.append(row)
        }
        let data = try JSONSerialization.data(withJSONObject: results)
        return ["results": .text(String(decoding: data, as: UTF8.self))]
    }

    private func probe(_ url: URL) throws -> [String: WireValue] {
        let bytes = (try FileManager.default.attributesOfItem(atPath: url.path)[.size] as? NSNumber)?.int64Value ?? 0
        let type = try? url.resourceValues(forKeys: [.contentTypeKey]).contentType
        let mime = type?.preferredMIMEType ?? "application/octet-stream"
        if type?.conforms(to: .image) == true,
           let source = CGImageSourceCreateWithURL(url as CFURL, nil),
           let properties = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any] {
            let width = (properties[kCGImagePropertyPixelWidth] as? NSNumber)?.int64Value ?? 0
            let height = (properties[kCGImagePropertyPixelHeight] as? NSNumber)?.int64Value ?? 0
            let orientation = (properties[kCGImagePropertyOrientation] as? NSNumber)?.intValue ?? 1
            let degrees: Int64 = [3: 180, 6: 90, 8: 270][orientation] ?? 0
            let swapsAxes = (5...8).contains(orientation)
            return info(1, mime, bytes, swapsAxes ? height : width, swapsAxes ? width : height, 0, degrees)
        }
        if type?.conforms(to: .movie) == true || type?.conforms(to: .audio) == true {
            let asset = AVURLAsset(url: url)
            let track = asset.tracks(withMediaType: .video).first
            let size = track?.naturalSize.applying(track?.preferredTransform ?? .identity) ?? .zero
            return info(type?.conforms(to: .movie) == true ? 3 : 2, mime, bytes,
                        safeNonnegativeInt64(Double(abs(size.width))), safeNonnegativeInt64(Double(abs(size.height))),
                        safeNonnegativeInt64(CMTimeGetSeconds(asset.duration) * 1000), rotation(track?.preferredTransform))
        }
        return info(4, mime, bytes, 0, 0, 0, 0)
    }

    private func thumbnail(_ values: [String: WireValue], _ completion: @escaping ModuleCompletion) throws {
        guard case let .text(sourcePath)? = values["source"],
              case let .text(destinationPath)? = values["destination"],
              case let .integer(maxWidth)? = values["maxWidth"],
              case let .integer(maxHeight)? = values["maxHeight"],
              case let .integer(format)? = values["format"],
              case let .integer(quality)? = values["quality"],
              case let .integer(time)? = values["timeMillis"],
              (1...8192).contains(maxWidth), (1...8192).contains(maxHeight),
              (1...3).contains(format), (1...100).contains(quality), time >= 0 else {
            throw MediaError.invalidRequest
        }

        let remote = sourcePath.hasPrefix("https://")
        let source = remote ? URL(string: sourcePath) : try file(sourcePath, true)
        guard let source else { throw MediaError.invalidRequest }
        let destination = try file(destinationPath, false)
        let type = remote ? UTType.movie : (try? source.resourceValues(forKeys: [.contentTypeKey]).contentType)
        if type?.conforms(to: .movie) == true {
            let generator = AVAssetImageGenerator(asset: AVURLAsset(url: source))
            generator.appliesPreferredTrackTransform = true
            generator.maximumSize = CGSize(width: CGFloat(maxWidth), height: CGFloat(maxHeight))
            let requestedTime = CMTime(value: time, timescale: 1000)
            if #available(iOS 16.0, *) {
                generator.generateCGImageAsynchronously(for: requestedTime) { image, _, error in
                    guard let image else {
                        completion(.failure, Data(String(describing: error ?? MediaError.encoding).utf8))
                        return
                    }
                    self.write(image, destination, destinationPath, Int(maxWidth), Int(maxHeight), format, quality, completion)
                }
            } else {
                generator.generateCGImagesAsynchronously(forTimes: [NSValue(time: requestedTime)]) { _, image, _, result, error in
                    guard result == .succeeded, let image else {
                        completion(.failure, Data(String(describing: error ?? MediaError.encoding).utf8))
                        return
                    }
                    self.write(image, destination, destinationPath, Int(maxWidth), Int(maxHeight), format, quality, completion)
                }
            }
            return
        }

        guard let imageSource = CGImageSourceCreateWithURL(source as CFURL, nil),
              let properties = CGImageSourceCopyPropertiesAtIndex(imageSource, 0, nil) as? [CFString: Any],
              let sourceWidth = (properties[kCGImagePropertyPixelWidth] as? NSNumber)?.intValue,
              let sourceHeight = (properties[kCGImagePropertyPixelHeight] as? NSNumber)?.intValue,
              sourceWidth > 0, sourceHeight > 0 else { throw MediaError.encoding }
        let orientation = (properties[kCGImagePropertyOrientation] as? NSNumber)?.intValue ?? 1
        let pixelLimit = MediaThumbnailSizing.pixelLimit(sourceWidth: sourceWidth, sourceHeight: sourceHeight,
                                                          orientation: orientation, maxWidth: Int(maxWidth), maxHeight: Int(maxHeight))
        let options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceThumbnailMaxPixelSize: pixelLimit,
        ]
        guard let image = CGImageSourceCreateThumbnailAtIndex(imageSource, 0, options as CFDictionary) else {
            throw MediaError.encoding
        }
        write(image, destination, destinationPath, Int(maxWidth), Int(maxHeight), format, quality, completion)
    }

    private func write(_ image: CGImage, _ destination: URL, _ path: String,
                       _ maxWidth: Int, _ maxHeight: Int, _ format: Int64, _ quality: Int64,
                       _ completion: ModuleCompletion) {
        let directory = destination.deletingLastPathComponent()
        let temporary = directory.appendingPathComponent(".pam-thumbnail-\(UUID().uuidString).tmp")
        defer { try? FileManager.default.removeItem(at: temporary) }
        do {
            let fitted = try MediaThumbnailSizing.fit(image, maxWidth: maxWidth, maxHeight: maxHeight)
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
            let output = try ImagePipeline.encode(fitted, to: destination, format: Int(format), quality: Int(quality))
            try succeed([
                "path": .text(path),
                "width": .integer(Int64(output.width)),
                "height": .integer(Int64(output.height)),
            ], completion)
        } catch {
            completion(.failure, Data(String(describing: error).utf8))
        }
    }

    /// PAM file sandbox (`Application Support/pam-files`, the `FileReference` space).
    private func file(_ path: String, _ exists: Bool) throws -> URL {
        let root = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("pam-files", isDirectory: true)
        try? FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        return try MediaSandboxPath.resolve(path, under: root, mustExist: exists)
    }

    private func info(_ kind: Int64, _ mime: String, _ bytes: Int64, _ width: Int64, _ height: Int64,
                      _ duration: Int64, _ orientation: Int64) -> [String: WireValue] {
        ["kind": .integer(kind), "mimeType": .text(mime), "bytes": .integer(bytes),
         "width": .integer(width), "height": .integer(height),
         "durationMillis": .integer(duration), "orientationDegrees": .integer(orientation)]
    }

    private func rotation(_ transform: CGAffineTransform?) -> Int64 {
        guard let transform else { return 0 }
        let degrees = atan2(transform.b, transform.a) * 180 / .pi
        guard degrees.isFinite else { return 0 }
        let angle = Int(round(degrees))
        return Int64((angle + 360) % 360)
    }

    private func safeNonnegativeInt64(_ value: Double) -> Int64 {
        guard value.isFinite, value >= 0, value < Double(Int64.max) else { return 0 }
        return Int64(value)
    }

    private func succeed(_ values: [String: WireValue], _ completion: ModuleCompletion) throws {
        completion(.success, try WireMap.encode(values))
    }
}

private enum MediaError: LocalizedError {
    case invalidRequest, encoding

    var errorDescription: String? {
        switch self {
        case .invalidRequest: return "Invalid media request"
        case .encoding: return "Media encoding failed"
        }
    }
}

/// Conflated progress channel for one transcode (`transcodeNext` long-polls).
final class TranscodeJob: @unchecked Sendable {
    private let lock = NSLock()
    private var pending: [String: WireValue]?
    private var terminal: [String: WireValue]?
    private var waiter: ModuleCompletion?
    private var lastPercent = -1
    private var cancelled = false
    private let onTerminalDelivered: () -> Void

    init(onTerminalDelivered: @escaping () -> Void) {
        self.onTerminalDelivered = onTerminalDelivered
    }

    var isCancelled: Bool {
        lock.lock()
        defer { lock.unlock() }
        return cancelled
    }

    func cancel() {
        lock.lock()
        cancelled = true
        lock.unlock()
    }

    func progress(_ fraction: Double) {
        let percent = min(max(Int(fraction * 100), 0), 100)
        lock.lock()
        guard terminal == nil, percent != lastPercent else {
            lock.unlock()
            return
        }
        lastPercent = percent
        let payload: [String: WireValue] = ["state": .integer(1), "progress": .decimal(Double(percent) / 100)]
        let current = waiter
        if current == nil { pending = payload } else { waiter = nil }
        lock.unlock()
        if let current { deliver(current, payload) }
    }

    func finish(_ payload: [String: WireValue]) {
        lock.lock()
        terminal = payload
        pending = nil
        let current = waiter
        waiter = nil
        lock.unlock()
        if let current { deliver(current, payload) }
    }

    func next(_ completion: @escaping ModuleCompletion) {
        lock.lock()
        if let ready = pending ?? terminal {
            if pending != nil { pending = nil }
            lock.unlock()
            deliver(completion, ready)
            return
        }
        guard waiter == nil else {
            lock.unlock()
            completion(.failure, Data("Transcode observation already pending".utf8))
            return
        }
        waiter = completion
        lock.unlock()
    }

    private func deliver(_ completion: ModuleCompletion, _ payload: [String: WireValue]) {
        if case .integer(1)? = payload["state"] {} else { onTerminalDelivered() }
        completion(.success, (try? WireMap.encode(payload)) ?? Data())
    }
}
