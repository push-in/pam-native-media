import Foundation

enum MediaSandboxPath {
    static func resolve(_ path: String, under rootURL: URL, mustExist: Bool) throws -> URL {
        guard !path.isEmpty, path.utf8.count <= 1024, !path.contains("\0"), !path.hasPrefix("/") else {
            throw MediaSandboxPathError.invalidPath
        }
        let root = rootURL.standardizedFileURL.resolvingSymlinksInPath()
        let target = root.appendingPathComponent(path).standardizedFileURL.resolvingSymlinksInPath()
        guard target.path.hasPrefix(root.path + "/") else { throw MediaSandboxPathError.invalidPath }
        if mustExist {
            var isDirectory: ObjCBool = false
            guard FileManager.default.fileExists(atPath: target.path, isDirectory: &isDirectory),
                  !isDirectory.boolValue else { throw MediaSandboxPathError.notFound }
        }
        return target
    }
}

private enum MediaSandboxPathError: Error { case invalidPath, notFound }
