import CoreGraphics
import Foundation
import ImageIO
import UniformTypeIdentifiers

struct ImageProbeInfo: Equatable {
    let width: Int
    let height: Int
    let storedWidth: Int
    let storedHeight: Int
    let orientation: Int
    let mimeType: String
    let bytes: Int64
}

struct ImageOutputInfo: Equatable {
    let width: Int
    let height: Int
    let bytes: Int64
    let mimeType: String
}

enum ImageOperation: Equatable {
    case resize(width: Int, height: Int, mode: Int)
    case crop(x: Int, y: Int, width: Int, height: Int)
    case rotate(degrees: Int)
    case flip(vertical: Bool)

    static func parse(_ json: String) throws -> [ImageOperation] {
        guard let array = try JSONSerialization.jsonObject(with: Data(json.utf8)) as? [[String: Any]] else {
            throw MediaFailure("Invalid image operations")
        }
        guard array.count <= 32 else { throw MediaFailure("Too many image operations") }
        func int(_ object: [String: Any], _ key: String, _ fallback: Int? = nil) throws -> Int {
            if let value = (object[key] as? NSNumber)?.intValue { return value }
            if let fallback { return fallback }
            throw MediaFailure("\(key) is required")
        }
        return try array.map { op in
            switch op["op"] as? String {
            case "resize":
                return .resize(
                    width: min(max(try int(op, "width", 0), 0), 16_384),
                    height: min(max(try int(op, "height", 0), 0), 16_384),
                    mode: try int(op, "mode", 1)
                )
            case "crop":
                return .crop(x: try int(op, "x"), y: try int(op, "y"), width: try int(op, "width"), height: try int(op, "height"))
            case "rotate":
                let degrees = try int(op, "degrees")
                guard degrees % 90 == 0 else { throw MediaFailure("Rotation must be a multiple of 90") }
                return .rotate(degrees: degrees)
            case "flip":
                return .flip(vertical: try int(op, "direction", 1) == 2)
            default:
                throw MediaFailure("Unknown image operation")
            }
        }
    }
}

struct MediaFailure: LocalizedError {
    let message: String
    init(_ message: String) { self.message = message }
    var errorDescription: String? { message }
}

/// Decode (subsampled, EXIF-oriented) → operations in call order → encode,
/// mirroring the Android ImagePipeline geometry exactly.
enum ImagePipeline {
    static let maxDecodedPixels = 48_000_000.0

    struct ResizePlan: Equatable {
        let scaledWidth: Int
        let scaledHeight: Int
        let cropWidth: Int
        let cropHeight: Int
    }

    static func probe(_ url: URL) throws -> ImageProbeInfo {
        guard let source = CGImageSourceCreateWithURL(url as CFURL, nil),
              let properties = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any],
              let width = (properties[kCGImagePropertyPixelWidth] as? NSNumber)?.intValue,
              let height = (properties[kCGImagePropertyPixelHeight] as? NSNumber)?.intValue,
              width > 0, height > 0 else { throw MediaFailure("Unsupported image") }
        let orientation = min(max((properties[kCGImagePropertyOrientation] as? NSNumber)?.intValue ?? 1, 1), 8)
        let swap = (5...8).contains(orientation)
        let type = CGImageSourceGetType(source).flatMap { UTType($0 as String) }
        let bytes = (try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize).map { Int64($0) } ?? 0
        return ImageProbeInfo(
            width: swap ? height : width,
            height: swap ? width : height,
            storedWidth: width,
            storedHeight: height,
            orientation: orientation,
            mimeType: type?.preferredMIMEType ?? "application/octet-stream",
            bytes: bytes
        )
    }

    static func process(
        source: URL,
        destination: URL,
        operations: [ImageOperation],
        onlyScaleDown: Bool,
        format: Int,
        quality: Int
    ) throws -> ImageOutputInfo {
        let info = try probe(source)
        let scale = requiredScale(width: info.width, height: info.height, operations: operations, onlyScaleDown: onlyScaleDown)
        var image = try decode(source, info: info, scale: scale)
        var ratio = Double(image.width) / Double(info.width)
        var logicalWidth = Double(info.width)
        var logicalHeight = Double(info.height)
        for operation in operations {
            switch operation {
            case let .crop(x, y, width, height):
                let cx = min(max(Int((Double(x) * ratio).rounded()), 0), image.width - 1)
                let cy = min(max(Int((Double(y) * ratio).rounded()), 0), image.height - 1)
                let cw = min(max(Int((Double(width) * ratio).rounded()), 1), image.width - cx)
                let ch = min(max(Int((Double(height) * ratio).rounded()), 1), image.height - cy)
                guard let cropped = image.cropping(to: CGRect(x: cx, y: cy, width: cw, height: ch)) else {
                    throw MediaFailure("Image crop failed")
                }
                image = cropped
                logicalWidth = Double(cw) / ratio
                logicalHeight = Double(ch) / ratio
            case let .resize(width, height, mode):
                let plan = resizePlan(width: logicalWidth, height: logicalHeight, box: (width, height, mode), onlyScaleDown: onlyScaleDown)
                if plan.scaledWidth != image.width || plan.scaledHeight != image.height {
                    image = try draw(width: plan.scaledWidth, height: plan.scaledHeight) { context in
                        context.draw(image, in: CGRect(x: 0, y: 0, width: plan.scaledWidth, height: plan.scaledHeight))
                    }
                }
                if plan.cropWidth < image.width || plan.cropHeight < image.height {
                    let x = (image.width - plan.cropWidth) / 2
                    let y = (image.height - plan.cropHeight) / 2
                    guard let cropped = image.cropping(to: CGRect(x: x, y: y, width: plan.cropWidth, height: plan.cropHeight)) else {
                        throw MediaFailure("Image crop failed")
                    }
                    image = cropped
                }
                ratio = 1
                logicalWidth = Double(image.width)
                logicalHeight = Double(image.height)
            case let .rotate(degrees):
                image = try rotate(image, degrees: degrees)
                if degrees % 180 != 0 { swap(&logicalWidth, &logicalHeight) }
            case let .flip(vertical):
                let source = image
                image = try draw(width: image.width, height: image.height) { context in
                    if vertical {
                        context.translateBy(x: 0, y: CGFloat(source.height))
                        context.scaleBy(x: 1, y: -1)
                    } else {
                        context.translateBy(x: CGFloat(source.width), y: 0)
                        context.scaleBy(x: -1, y: 1)
                    }
                    context.draw(source, in: CGRect(x: 0, y: 0, width: source.width, height: source.height))
                }
            }
        }
        return try encode(image, to: destination, format: format, quality: quality)
    }

    /// Writes [image] atomically; JPEG output is flattened onto white.
    static func encode(_ image: CGImage, to destination: URL, format: Int, quality: Int) throws -> ImageOutputInfo {
        let type: UTType
        switch format {
        case 2: type = .png
        case 3: type = UTType("org.webmproject.webp") ?? .jpeg
        default: type = .jpeg
        }
        let supported = (CGImageDestinationCopyTypeIdentifiers() as? [String]) ?? []
        guard supported.contains(type.identifier) else {
            throw MediaFailure("\(mimeType(format)) encoding is not supported on this iOS version")
        }
        var output = image
        if format == 1, image.alphaInfo != .none, image.alphaInfo != .noneSkipFirst, image.alphaInfo != .noneSkipLast {
            output = try draw(width: image.width, height: image.height, opaque: true) { context in
                context.setFillColor(CGColor(red: 1, green: 1, blue: 1, alpha: 1))
                context.fill(CGRect(x: 0, y: 0, width: image.width, height: image.height))
                context.draw(image, in: CGRect(x: 0, y: 0, width: image.width, height: image.height))
            }
        }
        let directory = destination.deletingLastPathComponent()
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let staged = directory.appendingPathComponent(".\(destination.lastPathComponent).part")
        try? FileManager.default.removeItem(at: staged)
        guard let writer = CGImageDestinationCreateWithURL(staged as CFURL, type.identifier as CFString, 1, nil) else {
            throw MediaFailure("Image encoding failed")
        }
        let options: [CFString: Any] = [kCGImageDestinationLossyCompressionQuality: Double(min(max(quality, 1), 100)) / 100]
        CGImageDestinationAddImage(writer, output, options as CFDictionary)
        guard CGImageDestinationFinalize(writer) else {
            try? FileManager.default.removeItem(at: staged)
            throw MediaFailure("Image encoding failed")
        }
        if FileManager.default.fileExists(atPath: destination.path) {
            _ = try FileManager.default.replaceItemAt(destination, withItemAt: staged)
        } else {
            try FileManager.default.moveItem(at: staged, to: destination)
        }
        let bytes = (try? destination.resourceValues(forKeys: [.fileSizeKey]).fileSize).map { Int64($0) } ?? 0
        return ImageOutputInfo(width: output.width, height: output.height, bytes: bytes, mimeType: mimeType(format))
    }

    static func mimeType(_ format: Int) -> String {
        switch format {
        case 2: return "image/png"
        case 3: return "image/webp"
        default: return "image/jpeg"
        }
    }

    /// Pure geometry of one resize (Android `resizePlan`).
    static func resizePlan(width: Double, height: Double, box: (width: Int, height: Int, mode: Int), onlyScaleDown: Bool) -> ResizePlan {
        let boxW = Double(box.width)
        let boxH = Double(box.height)
        let mode = box.mode == 2 && (boxW == 0 || boxH == 0) ? 1 : box.mode
        switch mode {
        case 3:
            var w = boxW > 0 ? boxW : width
            var h = boxH > 0 ? boxH : height
            if onlyScaleDown {
                w = min(w, width)
                h = min(h, height)
            }
            return ResizePlan(scaledWidth: px(w), scaledHeight: px(h), cropWidth: px(w), cropHeight: px(h))
        case 2:
            var scale = max(boxW / width, boxH / height)
            if onlyScaleDown { scale = min(scale, 1) }
            let sw = px(width * scale)
            let sh = px(height * scale)
            let aspect = boxW / boxH
            return ResizePlan(
                scaledWidth: sw,
                scaledHeight: sh,
                cropWidth: min(sw, px(boxW), px(Double(sh) * aspect)),
                cropHeight: min(sh, px(boxH), px(Double(sw) / aspect))
            )
        default:
            var scale = min(boxW > 0 ? boxW / width : .greatestFiniteMagnitude, boxH > 0 ? boxH / height : .greatestFiniteMagnitude)
            if onlyScaleDown { scale = min(scale, 1) }
            return ResizePlan(scaledWidth: px(width * scale), scaledHeight: px(height * scale), cropWidth: px(width * scale), cropHeight: px(height * scale))
        }
    }

    /// Linear scale (relative to the oriented source) decoding must keep.
    static func requiredScale(width: Int, height: Int, operations: [ImageOperation], onlyScaleDown: Bool) -> Double {
        var w = Double(width)
        var h = Double(height)
        for operation in operations {
            switch operation {
            case let .crop(_, _, cropWidth, cropHeight):
                w = min(Double(cropWidth), w)
                h = min(Double(cropHeight), h)
            case let .resize(boxWidth, boxHeight, mode):
                let plan = resizePlan(width: w, height: h, box: (boxWidth, boxHeight, mode), onlyScaleDown: onlyScaleDown)
                return min(1, max(Double(plan.scaledWidth) / w, Double(plan.scaledHeight) / h))
            case let .rotate(degrees):
                if degrees % 180 != 0 { swap(&w, &h) }
            case .flip:
                break
            }
        }
        return 1
    }

    private static func px(_ value: Double) -> Int { max(1, Int(value.rounded())) }

    /// ImageIO thumbnail decode: applies EXIF orientation and subsamples to the
    /// needed resolution, capped at 48 MP.
    private static func decode(_ url: URL, info: ImageProbeInfo, scale: Double) throws -> CGImage {
        guard let source = CGImageSourceCreateWithURL(url as CFURL, nil) else { throw MediaFailure("Image decode failed") }
        let longest = Double(max(info.storedWidth, info.storedHeight))
        var target = longest * min(max(scale, 0.0001), 1)
        let pixels = Double(info.storedWidth) * Double(info.storedHeight) * pow(target / longest, 2)
        if pixels > maxDecodedPixels { target *= (maxDecodedPixels / pixels).squareRoot() }
        let options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceShouldCacheImmediately: true,
            kCGImageSourceThumbnailMaxPixelSize: max(1, Int(target.rounded(.up))),
        ]
        guard let image = CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary) else {
            throw MediaFailure("Image decode failed")
        }
        return image
    }

    private static func rotate(_ image: CGImage, degrees: Int) throws -> CGImage {
        let normalized = ((degrees % 360) + 360) % 360
        guard normalized != 0 else { return image }
        let swapped = normalized % 180 != 0
        let width = swapped ? image.height : image.width
        let height = swapped ? image.width : image.height
        return try draw(width: width, height: height) { context in
            context.translateBy(x: CGFloat(width) / 2, y: CGFloat(height) / 2)
            // CoreGraphics is y-up: a clockwise rotation is a negative angle.
            context.rotate(by: -CGFloat(normalized) * .pi / 180)
            context.draw(image, in: CGRect(x: -CGFloat(image.width) / 2, y: -CGFloat(image.height) / 2, width: CGFloat(image.width), height: CGFloat(image.height)))
        }
    }

    private static func draw(width: Int, height: Int, opaque: Bool = false, _ body: (CGContext) -> Void) throws -> CGImage {
        guard let context = CGContext(
            data: nil,
            width: width,
            height: height,
            bitsPerComponent: 8,
            bytesPerRow: 0,
            space: CGColorSpace(name: CGColorSpace.sRGB) ?? CGColorSpaceCreateDeviceRGB(),
            bitmapInfo: opaque ? CGImageAlphaInfo.noneSkipLast.rawValue : CGImageAlphaInfo.premultipliedLast.rawValue
        ) else { throw MediaFailure("Image buffer allocation failed") }
        context.interpolationQuality = .high
        body(context)
        guard let image = context.makeImage() else { throw MediaFailure("Image rendering failed") }
        return image
    }
}
