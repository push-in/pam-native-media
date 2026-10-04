import CoreGraphics
import Foundation

enum MediaThumbnailSizing {
    static func pixelLimit(sourceWidth: Int, sourceHeight: Int, orientation: Int,
                           maxWidth: Int, maxHeight: Int) -> Int {
        let swapsAxes = (5...8).contains(orientation)
        let width = Double(swapsAxes ? sourceHeight : sourceWidth)
        let height = Double(swapsAxes ? sourceWidth : sourceHeight)
        let scale = min(1, Double(maxWidth) / width, Double(maxHeight) / height)
        return max(1, Int(floor(Double(max(sourceWidth, sourceHeight)) * scale)))
    }

    static func fit(_ image: CGImage, maxWidth: Int, maxHeight: Int) throws -> CGImage {
        guard image.width > maxWidth || image.height > maxHeight else { return image }
        let scale = min(Double(maxWidth) / Double(image.width), Double(maxHeight) / Double(image.height))
        let width = max(1, Int(floor(Double(image.width) * scale)))
        let height = max(1, Int(floor(Double(image.height) * scale)))
        guard let context = CGContext(data: nil, width: width, height: height,
                                      bitsPerComponent: 8, bytesPerRow: 0,
                                      space: CGColorSpaceCreateDeviceRGB(),
                                      bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else {
            throw MediaThumbnailSizingError.encoding
        }
        context.interpolationQuality = .high
        context.draw(image, in: CGRect(x: 0, y: 0, width: width, height: height))
        guard let fitted = context.makeImage() else { throw MediaThumbnailSizingError.encoding }
        return fitted
    }
}

private enum MediaThumbnailSizingError: Error { case encoding }
