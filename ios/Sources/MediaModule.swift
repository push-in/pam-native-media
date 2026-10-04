import AVFoundation
import Foundation
import ImageIO
import PamNative
import UniformTypeIdentifiers

public final class MediaModule: NativeModule, @unchecked Sendable {
    public init() {}

    public func invoke(method: String, payload: Data, completion: @escaping ModuleCompletion) {
        do {
            let values = try WireMap.decode(payload)
            switch method {
            case "probe":
                guard case let .text(path)? = values["path"] else { throw MediaError.invalidRequest }
                try succeed(probe(try file(path, true)), completion)
            case "thumbnail":
                try thumbnail(values, completion)
            default:
                throw MediaError.invalidRequest
            }
        } catch {
            completion(.failure, Data(String(describing: error).utf8))
        }
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
              (1...2).contains(format), (1...100).contains(quality), time >= 0 else {
            throw MediaError.invalidRequest
        }

        let source = try file(sourcePath, true)
        let destination = try file(destinationPath, false)
        let type = try? source.resourceValues(forKeys: [.contentTypeKey]).contentType
        if type?.conforms(to: .movie) == true {
            let generator = AVAssetImageGenerator(asset: AVURLAsset(url: source))
            generator.appliesPreferredTrackTransform = true
            generator.maximumSize = CGSize(width: CGFloat(maxWidth), height: CGFloat(maxHeight))
            let requestedTime = CMTime(value: time, timescale: 1000)
            if #available(iOS 16.0, *) {
                generator.generateCGImageAsynchronously(for: requestedTime) { image, _, error in
                    guard let image else {
                        completion(.failure, Data(String(describing: error ?? MediaError.encoding).utf8)))
                        return
                    }
                    self.write(image, destination, destinationPath, Int(maxWidth), Int(maxHeight), format, quality, completion)
                }
            } else {
                generator.generateCGImagesAsynchronously(forTimes: [NSValue(time: requestedTime)]) { _, image, _, result, error in
                    guard result == .succeeded, let image else {
                        completion(.failure, Data(String(describing: error ?? MediaError.encoding).utf8)))
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
            let type = format == 2 ? UTType.png.identifier as CFString : UTType.jpeg.identifier as CFString
            guard let output = CGImageDestinationCreateWithURL(temporary as CFURL, type, 1, nil) else {
                throw MediaError.encoding
            }
            CGImageDestinationAddImage(output, fitted, [kCGImageDestinationLossyCompressionQuality: Double(quality) / 100] as CFDictionary)
            guard CGImageDestinationFinalize(output) else { throw MediaError.encoding }
            if FileManager.default.fileExists(atPath: destination.path) {
                _ = try FileManager.default.replaceItemAt(destination, withItemAt: temporary)
            } else {
                try FileManager.default.moveItem(at: temporary, to: destination)
            }
            try succeed(["path": .text(path)], completion)
        } catch {
            completion(.failure, Data(String(describing: error).utf8))
        }
    }

    private func file(_ path: String, _ exists: Bool) throws -> URL {
        let root = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
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

private enum MediaError: Error { case invalidRequest, encoding }
