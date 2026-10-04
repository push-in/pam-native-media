import CoreGraphics
import Foundation

precondition(MediaThumbnailSizing.pixelLimit(sourceWidth: 4000, sourceHeight: 2000,
                                             orientation: 1, maxWidth: 100, maxHeight: 1000) == 100)
precondition(MediaThumbnailSizing.pixelLimit(sourceWidth: 4000, sourceHeight: 2000,
                                             orientation: 6, maxWidth: 100, maxHeight: 1000) == 200)
precondition(MediaThumbnailSizing.pixelLimit(sourceWidth: 500, sourceHeight: 250,
                                             orientation: 1, maxWidth: 1000, maxHeight: 1000) == 500)

let context = CGContext(data: nil, width: 400, height: 200, bitsPerComponent: 8, bytesPerRow: 0,
                        space: CGColorSpaceCreateDeviceRGB(),
                        bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
let image = context.makeImage()!
let fitted = try MediaThumbnailSizing.fit(image, maxWidth: 100, maxHeight: 1000)
precondition(fitted.width == 100 && fitted.height == 50)
let unchanged = try MediaThumbnailSizing.fit(image, maxWidth: 1000, maxHeight: 1000)
precondition(unchanged.width == 400 && unchanged.height == 200)

let testRoot = FileManager.default.temporaryDirectory.appendingPathComponent("pam-media-path-\(UUID().uuidString)")
let sandbox = testRoot.appendingPathComponent("sandbox")
let outside = testRoot.appendingPathComponent("outside")
defer { try? FileManager.default.removeItem(at: testRoot) }
try FileManager.default.createDirectory(at: sandbox, withIntermediateDirectories: true)
try FileManager.default.createDirectory(at: outside, withIntermediateDirectories: true)
let linked = sandbox.appendingPathComponent("linked")
try FileManager.default.createSymbolicLink(at: linked, withDestinationURL: outside)
let safe = try MediaSandboxPath.resolve("thumbnails/image.jpg", under: sandbox, mustExist: false)
precondition(safe.path.hasPrefix(sandbox.standardizedFileURL.resolvingSymlinksInPath().path + "/"))
for rejected in ["../outside/file.jpg", "linked/file.jpg", "/tmp/file.jpg"] {
    do {
        _ = try MediaSandboxPath.resolve(rejected, under: sandbox, mustExist: false)
        fatalError("Accepted escaping path: \(rejected)")
    } catch {}
}
print("9 iOS media sizing and sandbox checks passed")
