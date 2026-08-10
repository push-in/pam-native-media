import AVFoundation
import Foundation
import ImageIO
import PamNative
import UniformTypeIdentifiers

public final class MediaModule: NativeModule, @unchecked Sendable {
    public init() {}
    public func invoke(method:String,payload:Data,completion:@escaping ModuleCompletion){
        do{let values=try WireMap.decode(payload);switch method{
        case "probe":guard case let .text(path)?=values["path"] else{throw MediaError.invalidRequest};try succeed(probe(try file(path,true)),completion)
        case "thumbnail":try thumbnail(values,completion)
        default:throw MediaError.invalidRequest}}catch{completion(.failure,Data(String(describing:error).utf8))}
    }
    private func probe(_ url:URL)throws->[String:WireValue]{let bytes=(try FileManager.default.attributesOfItem(atPath:url.path)[.size] as? NSNumber)?.int64Value ?? 0;let type=(try? url.resourceValues(forKeys:[.contentTypeKey]).contentType);let mime=type?.preferredMIMEType ?? "application/octet-stream"
        if type?.conforms(to:.image)==true,let source=CGImageSourceCreateWithURL(url as CFURL,nil),let p=CGImageSourceCopyPropertiesAtIndex(source,0,nil)as?[CFString:Any]{let w=(p[kCGImagePropertyPixelWidth]as?NSNumber)?.int64Value ?? 0;let h=(p[kCGImagePropertyPixelHeight]as?NSNumber)?.int64Value ?? 0;let o=(p[kCGImagePropertyOrientation]as?NSNumber)?.intValue ?? 1;let degrees:Int64=[3:180,6:90,8:270][o] ?? 0;return info(1,mime,bytes,(degrees==90||degrees==270) ? h:w,(degrees==90||degrees==270) ? w:h,0,degrees)}
        if type?.conforms(to:.movie)==true||type?.conforms(to:.audio)==true{let asset=AVURLAsset(url:url);let track=asset.tracks(withMediaType:.video).first;let size=track?.naturalSize.applying(track?.preferredTransform ?? .identity) ?? .zero;return info(type?.conforms(to:.movie)==true ? 3:2,mime,bytes,Int64(abs(size.width)),Int64(abs(size.height)),Int64(CMTimeGetSeconds(asset.duration)*1000),rotation(track?.preferredTransform))}
        return info(4,mime,bytes,0,0,0,0)
    }
    private func thumbnail(_ v:[String:WireValue],_ completion:@escaping ModuleCompletion)throws{guard case let .text(sourcePath)?=v["source"],case let .text(destinationPath)?=v["destination"],case let .integer(maxWidth)?=v["maxWidth"],case let .integer(maxHeight)?=v["maxHeight"],case let .integer(format)?=v["format"],case let .integer(quality)?=v["quality"],case let .integer(time)?=v["timeMillis"]else{throw MediaError.invalidRequest};let source=try file(sourcePath,true);let destination=try file(destinationPath,false);try FileManager.default.createDirectory(at:destination.deletingLastPathComponent(),withIntermediateDirectories:true);let type=(try?source.resourceValues(forKeys:[.contentTypeKey]).contentType)
        if type?.conforms(to:.movie)==true{let generator=AVAssetImageGenerator(asset:AVURLAsset(url:source));generator.appliesPreferredTrackTransform=true;generator.maximumSize=CGSize(width:CGFloat(maxWidth),height:CGFloat(maxHeight));let requestedTime=CMTime(value:time,timescale:1000)
            if #available(iOS 16.0, *){generator.generateCGImageAsynchronously(for:requestedTime){image,_,error in guard let image else{completion(.failure,Data(String(describing:error ?? MediaError.encoding).utf8));return};self.write(image,destination,destinationPath,format,quality,completion)}}else{generator.generateCGImagesAsynchronously(forTimes:[NSValue(time:requestedTime)]){_,image,_,result,error in guard result == .succeeded,let image else{completion(.failure,Data(String(describing:error ?? MediaError.encoding).utf8));return};self.write(image,destination,destinationPath,format,quality,completion)}};return}
        guard let imageSource=CGImageSourceCreateWithURL(source as CFURL,nil),let image=CGImageSourceCreateThumbnailAtIndex(imageSource,0,[kCGImageSourceCreateThumbnailFromImageAlways:true,kCGImageSourceCreateThumbnailWithTransform:true,kCGImageSourceThumbnailMaxPixelSize:max(maxWidth,maxHeight)]as CFDictionary)else{throw MediaError.encoding};write(image,destination,destinationPath,format,quality,completion)
    }
    private func write(_ image:CGImage,_ destination:URL,_ path:String,_ format:Int64,_ quality:Int64,_ completion:ModuleCompletion){let type:CFString=format==2 ? UTType.png.identifier as CFString:UTType.jpeg.identifier as CFString;guard let output=CGImageDestinationCreateWithURL(destination as CFURL,type,1,nil)else{completion(.failure,Data("Cannot create thumbnail".utf8));return};CGImageDestinationAddImage(output,image,[kCGImageDestinationLossyCompressionQuality:Double(quality)/100]as CFDictionary);guard CGImageDestinationFinalize(output)else{completion(.failure,Data("Cannot encode thumbnail".utf8));return};try? succeed(["path":.text(path)],completion)}
    private func file(_ path:String,_ exists:Bool)throws->URL{let root=FileManager.default.urls(for:.applicationSupportDirectory,in:.userDomainMask)[0].standardizedFileURL;let target=root.appendingPathComponent(path).standardizedFileURL;guard target.path.hasPrefix(root.path+"/") else{throw MediaError.invalidPath};if exists && !FileManager.default.fileExists(atPath:target.path){throw MediaError.notFound};return target}
    private func info(_ kind:Int64,_ mime:String,_ bytes:Int64,_ width:Int64,_ height:Int64,_ duration:Int64,_ orientation:Int64)->[String:WireValue]{["kind":.integer(kind),"mimeType":.text(mime),"bytes":.integer(bytes),"width":.integer(width),"height":.integer(height),"durationMillis":.integer(duration),"orientationDegrees":.integer(orientation)]}
    private func rotation(_ transform:CGAffineTransform?)->Int64{guard let t=transform else{return 0};let angle=Int(round(atan2(t.b,t.a)*180 / .pi));return Int64((angle+360)%360)}
    private func succeed(_ values:[String:WireValue],_ completion:ModuleCompletion)throws{completion(.success,try WireMap.encode(values))}
}
private enum MediaError:Error{case invalidRequest;case invalidPath;case notFound;case encoding}
