import AVFoundation
import Foundation
import PamNative
import UIKit

public final class CameraViewFactory:NativeViewFactory,@unchecked Sendable{
    public init(){}
    public func create(context:AnyObject?,emit:@escaping(Data)->Void)->UIView{CameraPreview(emit:emit)}
    public func update(view:UIView,properties:[String:WireValue]){(view as? CameraPreview)?.update(properties)}
    public func release(view:UIView){(view as? CameraPreview)?.releaseCamera()}
}

private final class CameraPreview:UIView,AVCapturePhotoCaptureDelegate,AVCaptureFileOutputRecordingDelegate,@unchecked Sendable{
    private let emit:(Data)->Void;private let session=AVCaptureSession();private let queue=DispatchQueue(label:"dev.pam.media.camera",qos:.userInitiated);private lazy var preview=AVCaptureVideoPreviewLayer(session:session);private let photos=AVCapturePhotoOutput();private let movies=AVCaptureMovieFileOutput()
    private let recordingZoom = CameraRecordingZoom()
    private var zoomTarget = 1.0
    private var zoomDurationMillis: Int64 = 0
    private var recordedZoomTarget = 1.0
    private var recordedZoomDurationMillis: Int64 = 0
    private var recordingStopping = false
    private var recordingURL: URL?
    private var released = false
    private var facing:Int64=1;private var mode:Int64=1;private var flash:Int64=1;private var enabled=true;private var audioEnabled=true;private var captureRevision:Int64=0;private var recordRevision:Int64=0;private var stopRevision:Int64=0;private var maxDuration:Int64=60;private var configured=false;private var recordingStartedAt=Date()
    init(emit:@escaping(Data)->Void){self.emit=emit;super.init(frame:.zero);preview.videoGravity = .resizeAspectFill;layer.addSublayer(preview);authorize()}
    required init?(coder:NSCoder){nil}
    override func layoutSubviews(){super.layoutSubviews();preview.frame=bounds}
    func update(_ v: [String: WireValue]) {
        guard !released else { return }
        zoomTarget = v.decimal("zoomTarget", 1)
        zoomDurationMillis = max(0, min(600_000, v.integer("zoomDurationMillis", 0)))
        let nextFacing = v.integer("facing", 1)
        let nextMode = max(1,min(3,v.integer("mode",1)))
        let nextCapture = v.integer("captureRevision", 0)
        let nextRecord = v.integer("recordRevision", 0)
        let nextStop = v.integer("stopRevision", 0)
        enabled = v.flag("enabled", true)
        audioEnabled = v.flag("audioEnabled", true)
        flash = v.integer("flashMode", 1)
        maxDuration = max(1, min(600, v.integer("maxDurationSeconds", 60)))
        if !enabled { queue.async { self.stopRecording() } }
        if nextFacing != facing || nextMode != mode {
            facing = nextFacing
            mode = nextMode
            if configured { queue.async { self.configure() } }
        }
        if nextCapture > captureRevision {
            captureRevision = nextCapture
            queue.async { self.photo() }
        }
        if nextRecord > recordRevision {
            recordRevision = nextRecord
            queue.async { self.startRecording() }
        }
        if nextStop > stopRevision {
            stopRevision = nextStop
            queue.async { self.stopRecording() }
        }
    }
    private func authorize(){switch AVCaptureDevice.authorizationStatus(for:.video){case.authorized:queue.async{self.configure()};case.notDetermined:AVCaptureDevice.requestAccess(for:.video){granted in if granted{self.queue.async{self.configure()}}else{self.send(["event":.integer(5),"message":.text("Camera permission was denied")])}};default:send(["event":.integer(5),"message":.text("Camera permission is required")])}}
    private func configure(){guard !released else{return};stopRecording();if session.isRunning{session.stopRunning()};session.beginConfiguration();session.inputs.forEach(session.removeInput);session.outputs.forEach(session.removeOutput);session.sessionPreset = .high;do{let position:AVCaptureDevice.Position=facing==2 ? .front:.back;guard let device=AVCaptureDevice.default(.builtInWideAngleCamera,for:.video,position:position)else{throw CameraError.unavailable};let input=try AVCaptureDeviceInput(device:device);guard session.canAddInput(input)else{throw CameraError.configuration};session.addInput(input);if mode != 1,audioEnabled,AVCaptureDevice.authorizationStatus(for:.audio) == .authorized,let microphone=AVCaptureDevice.default(for:.audio){let audio=try AVCaptureDeviceInput(device:microphone);if session.canAddInput(audio){session.addInput(audio)}};if mode != 2{guard session.canAddOutput(photos)else{throw CameraError.configuration};session.addOutput(photos)};if mode != 1{if session.canAddOutput(movies){session.addOutput(movies);movies.maxRecordedDuration=CMTime(seconds:Double(maxDuration),preferredTimescale:600);if let connection=movies.connection(with:.video),connection.isVideoOrientationSupported{connection.videoOrientation = .portrait}}else if mode==2{throw CameraError.configuration}};session.commitConfiguration();configured=true;session.startRunning();send(["event":.integer(1)])}catch{session.commitConfiguration();failure(String(describing:error))}}
    private func photo(){guard configured,enabled else{return};let settings=AVCapturePhotoSettings();if photos.supportedFlashModes.contains(nativeFlash()){settings.flashMode=nativeFlash()};photos.capturePhoto(with:settings,delegate:self)}
    func photoOutput(_ output:AVCapturePhotoOutput,didFinishProcessingPhoto photo:AVCapturePhoto,error:Error?){if let error{failure(error.localizedDescription);return};guard let data=photo.fileDataRepresentation()else{failure("Photo encoding failed");return};do{let url=try captureURL("jpg");try data.write(to:url,options:.atomic);sendCapture(2,url,"image/jpeg",0)}catch{failure(error.localizedDescription)}}
    private func startRecording() {
        guard configured, enabled, !released, recordingURL == nil, !movies.isRecording else { return }
        guard session.outputs.contains(movies) else { failure("Camera is not ready"); return }
        do {
            let url = try captureURL("mp4")
            recordingStartedAt = Date()
            recordingURL = url
            recordingStopping = false
            recordedZoomTarget = zoomTarget
            recordedZoomDurationMillis = zoomDurationMillis
            movies.maxRecordedDuration = CMTime(seconds: Double(maxDuration), preferredTimescale: 600)
            movies.startRecording(to: url, recordingDelegate: self)
        } catch {
            failure(error.localizedDescription)
        }
    }

    private func stopRecording() {
        recordingStopping = true
        recordingZoom.reset()
        if movies.isRecording { movies.stopRecording() }
    }
    func fileOutput(_ output:AVCaptureFileOutput,didStartRecordingTo fileURL:URL,from connections:[AVCaptureConnection]) {
        queue.async {
            guard !self.released, self.recordingURL == fileURL else { return }
            if self.recordingStopping { self.stopRecording(); return }
            self.recordingStartedAt = Date()
            self.applyTorch(self.flash == 2)
            let device = self.session.inputs.compactMap { $0 as? AVCaptureDeviceInput }.first { $0.device.hasMediaType(.video) }?.device
            self.recordingZoom.start(device: device, target: self.recordedZoomTarget, durationMillis: self.recordedZoomDurationMillis)
            self.send(["event": .integer(3)])
        }
    }
    func fileOutput(_ output:AVCaptureFileOutput,didFinishRecordingTo outputFileURL:URL,from connections:[AVCaptureConnection],error:Error?) {
        queue.async {
            guard self.recordingURL == outputFileURL else { return }
            self.recordingURL = nil
            self.recordingZoom.reset()
            self.applyTorch(false)
            guard !self.released else { return }
            if let error, (error as NSError).userInfo[AVErrorRecordingSuccessfullyFinishedKey] as? Bool != true {
                self.failure(error.localizedDescription)
                return
            }
            self.sendCapture(4, outputFileURL, "video/mp4", Int64(Date().timeIntervalSince(self.recordingStartedAt) * 1000))
        }
    }
    private func nativeFlash()->AVCaptureDevice.FlashMode{flash==2 ? .on:flash==3 ? .auto:.off}
    private func applyTorch(_ on:Bool){guard let device=(session.inputs.first as? AVCaptureDeviceInput)?.device,device.hasTorch else{return};do{try device.lockForConfiguration();device.torchMode=on ? .on:.off;device.unlockForConfiguration()}catch{failure(error.localizedDescription)}}
    private func captureURL(_ extensionName:String)throws->URL{let base=FileManager.default.urls(for:.applicationSupportDirectory,in:.userDomainMask)[0].appendingPathComponent("pam-files/captures",isDirectory:true);try FileManager.default.createDirectory(at:base,withIntermediateDirectories:true);return base.appendingPathComponent("pam-camera-\(UUID().uuidString).\(extensionName)")}
    private func sendCapture(_ event:Int64,_ url:URL,_ mime:String,_ duration:Int64){send(["event":.integer(event),"path":.text("captures/\(url.lastPathComponent)"),"mimeType":.text(mime),"width":.integer(0),"height":.integer(0),"durationMillis":.integer(duration)])}
    private func failure(_ message:String){send(["event":.integer(6),"message":.text(message)])};private func send(_ values:[String:WireValue]){if let data=try?WireMap.encode(values){DispatchQueue.main.async{if !self.released{self.emit(data)}}}}
    func releaseCamera() {
        released = true
        queue.async {
            self.stopRecording()
            if self.session.isRunning { self.session.stopRunning() }
            self.session.inputs.forEach(self.session.removeInput)
            self.session.outputs.forEach(self.session.removeOutput)
        }
    }
    deinit {
        let session = session
        let movies = movies
        let zoom = recordingZoom
        queue.async {
            zoom.reset()
            if movies.isRecording { movies.stopRecording() }
            if session.isRunning { session.stopRunning() }
        }
    }
}
private extension Dictionary where Key==String,Value==WireValue{func decimal(_ k:String,_ f:Double)->Double{switch self[k]{case let .decimal(v)?:return v.isFinite ? max(1,v):f;case let .integer(v)?:return max(1,Double(v));default:return f}};func integer(_ k:String,_ f:Int64)->Int64{if case let.integer(v)?=self[k]{return v};return f};func flag(_ k:String,_ f:Bool)->Bool{if case let.flag(v)?=self[k]{return v};return f}}
private enum CameraError:Error{case unavailable;case configuration}
