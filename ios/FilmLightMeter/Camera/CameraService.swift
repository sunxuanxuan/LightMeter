import AVFoundation
import Combine
import CoreImage
import Foundation
import ImageIO
import os

enum CameraPermissionState {
    case unknown
    case granted
    case denied
}

struct CameraCapabilities: Sendable {
    let horizontalFieldOfViewDegrees: Double
    let minimumZoomFactor: Double
    let maximumZoomFactor: Double
    let currentZoomFactor: Double
}

struct CameraCapturedFrame: @unchecked Sendable {
    let snapshot: ExposureSnapshot
    let image: CGImage
}

final class CameraService: NSObject, ObservableObject, @unchecked Sendable {
    let session = AVCaptureSession()

    @Published private(set) var permissionState: CameraPermissionState = .unknown
    @Published private(set) var isRunning = false
    @Published private(set) var errorMessage: String?
    @Published private(set) var capabilities: CameraCapabilities?

    var onMeteringResult: (@Sendable (MeteringResult) -> Void)?
    var onFrameCaptured: (@Sendable (CameraCapturedFrame?) -> Void)?

    private let logger = Logger(subsystem: "com.lightmeter.app.ios", category: "camera")
    private let sessionQueue = DispatchQueue(label: "com.lightmeter.camera.session")
    private let outputQueue = DispatchQueue(label: "com.lightmeter.camera.output")
    private let stateLock = NSLock()
    private let analyzer = MeteringEngine()
    private let ciContext = CIContext(options: [.cacheIntermediates: false])
    private let output = AVCaptureVideoDataOutput()

    private var device: AVCaptureDevice?
    private var configuration = MeteringConfiguration()
    private var lastAnalysisNanoseconds: Int64 = 0
    private var sessionID = UUID()
    private var pendingFrameCaptureToken: UUID?

    override init() {
        super.init()
        permissionState = Self.currentPermissionState()
    }

    func requestAccessAndStart() async {
        let granted: Bool
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized:
            granted = true
        case .notDetermined:
            granted = await AVCaptureDevice.requestAccess(for: .video)
        default:
            granted = false
        }
        await MainActor.run {
            self.permissionState = granted ? .granted : .denied
        }
        guard granted else { return }
        start()
    }

    func start() {
        sessionQueue.async { [weak self] in
            guard let self else { return }
            do {
                if self.device == nil {
                    try self.configureSession()
                }
                guard !self.session.isRunning else { return }
                self.session.startRunning()
                Task { @MainActor in self.isRunning = true }
            } catch {
                self.logger.error("Camera start failed: \(error.localizedDescription)")
                Task { @MainActor in self.errorMessage = error.localizedDescription }
            }
        }
    }

    func stop() {
        cancelFrameCapture()
        sessionQueue.async { [weak self] in
            guard let self else { return }
            if self.session.isRunning {
                self.session.stopRunning()
            }
            Task { @MainActor in self.isRunning = false }
        }
    }

    func requestFrameCapture() {
        let token = UUID()
        stateLock.withLock {
            pendingFrameCaptureToken = token
        }
        outputQueue.asyncAfter(deadline: .now() + .milliseconds(800)) { [weak self] in
            guard let self else { return }
            let timedOut = self.stateLock.withLock {
                guard self.pendingFrameCaptureToken == token else { return false }
                self.pendingFrameCaptureToken = nil
                return true
            }
            if timedOut {
                self.onFrameCaptured?(nil)
            }
        }
    }

    func cancelFrameCapture() {
        stateLock.withLock {
            pendingFrameCaptureToken = nil
        }
    }

    func updateConfiguration(_ newConfiguration: MeteringConfiguration) {
        stateLock.withLock {
            configuration = newConfiguration
        }
    }

    func setZoomFactor(_ requested: Double) async -> Double? {
        await withCheckedContinuation { continuation in
            sessionQueue.async { [weak self] in
                guard let self, let device = self.device else {
                    continuation.resume(returning: nil)
                    return
                }
                let target = min(
                    max(requested, Double(device.minAvailableVideoZoomFactor)),
                    Double(device.maxAvailableVideoZoomFactor)
                )
                do {
                    try device.lockForConfiguration()
                    device.videoZoomFactor = CGFloat(target)
                    let applied = Double(device.videoZoomFactor)
                    device.unlockForConfiguration()
                    continuation.resume(returning: applied)
                } catch {
                    continuation.resume(returning: nil)
                }
            }
        }
    }

    private func configureSession() throws {
        session.beginConfiguration()
        defer { session.commitConfiguration() }
        session.sessionPreset = .high

        guard let camera = AVCaptureDevice.default(
            .builtInWideAngleCamera,
            for: .video,
            position: .back
        ) else {
            throw CameraError.noBackCamera
        }
        let input = try AVCaptureDeviceInput(device: camera)
        guard session.canAddInput(input) else { throw CameraError.cannotAddInput }
        session.addInput(input)

        output.alwaysDiscardsLateVideoFrames = true
        let fullRange = kCVPixelFormatType_420YpCbCr8BiPlanarFullRange
        let videoRange = kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange
        let supported = output.availableVideoPixelFormatTypes
        let format = supported.contains(fullRange) ? fullRange : videoRange
        output.videoSettings = [
            kCVPixelBufferPixelFormatTypeKey as String: format,
        ]
        output.setSampleBufferDelegate(self, queue: outputQueue)
        guard session.canAddOutput(output) else { throw CameraError.cannotAddOutput }
        session.addOutput(output)
        device = camera
        sessionID = UUID()
        publishCapabilities(camera)
        installNotifications()
    }

    private func publishCapabilities(_ device: AVCaptureDevice) {
        let value = CameraCapabilities(
            horizontalFieldOfViewDegrees: Double(device.activeFormat.videoFieldOfView),
            minimumZoomFactor: Double(device.minAvailableVideoZoomFactor),
            maximumZoomFactor: Double(device.maxAvailableVideoZoomFactor),
            currentZoomFactor: Double(device.videoZoomFactor)
        )
        Task { @MainActor in self.capabilities = value }
    }

    private func installNotifications() {
        NotificationCenter.default.addObserver(
            forName: .AVCaptureSessionRuntimeError,
            object: session,
            queue: nil
        ) { [weak self] notification in
            guard let self else { return }
            let message = (notification.userInfo?[AVCaptureSessionErrorKey] as? Error)?
                .localizedDescription ?? "相机会话发生错误"
            Task { @MainActor in self.errorMessage = message }
        }
        NotificationCenter.default.addObserver(
            forName: .AVCaptureSessionInterruptionEnded,
            object: session,
            queue: nil
        ) { [weak self] _ in self?.start() }
    }

    private static func currentPermissionState() -> CameraPermissionState {
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized: .granted
        case .notDetermined: .unknown
        default: .denied
        }
    }
}

extension CameraService: AVCaptureVideoDataOutputSampleBufferDelegate {
    func captureOutput(
        _ output: AVCaptureOutput,
        didOutput sampleBuffer: CMSampleBuffer,
        from connection: AVCaptureConnection
    ) {
        let timestamp = CMSampleBufferGetPresentationTimeStamp(sampleBuffer)
        let nanoseconds = Int64(CMTimeGetSeconds(timestamp) * 1_000_000_000)
        let captureToken = stateLock.withLock { pendingFrameCaptureToken }
        guard captureToken != nil
            || nanoseconds - lastAnalysisNanoseconds >= Self.analysisIntervalNanoseconds else {
            return
        }
        guard let pixelBuffer = CMSampleBufferGetImageBuffer(sampleBuffer),
              let device,
              let plane = copyLuminancePlane(pixelBuffer),
              let metadata = exposureMetadata(sampleBuffer: sampleBuffer, device: device) else {
            return
        }

        let currentConfiguration = stateLock.withLock { configuration }
        guard let result = analyzer.analyze(
            plane: plane,
            metadata: metadata,
            timestampNanoseconds: nanoseconds,
            configuration: currentConfiguration
        ) else {
            return
        }
        lastAnalysisNanoseconds = nanoseconds
        onMeteringResult?(result)

        guard let captureToken,
              let snapshot = analyzer.makeSnapshot(
                plane: plane,
                result: result,
                configuration: currentConfiguration
              ),
              let image = makePortraitImage(pixelBuffer) else {
            return
        }
        let shouldDeliver = stateLock.withLock {
            guard pendingFrameCaptureToken == captureToken else { return false }
            pendingFrameCaptureToken = nil
            return true
        }
        if shouldDeliver {
            onFrameCaptured?(CameraCapturedFrame(snapshot: snapshot, image: image))
        }
    }

    private func exposureMetadata(
        sampleBuffer: CMSampleBuffer,
        device: AVCaptureDevice
    ) -> CameraExposureMetadata? {
        if let attachments = CMCopyDictionaryOfAttachments(
            allocator: kCFAllocatorDefault,
            target: sampleBuffer,
            attachmentMode: kCMAttachmentMode_ShouldPropagate
        ) as NSDictionary?,
           let exif = attachments.object(
            forKey: kCGImagePropertyExifDictionary
           ) as? NSDictionary,
           let exposureSeconds = Self.numericValue(
            exif.object(forKey: kCGImagePropertyExifExposureTime)
           ),
           let sensitivityISO = Self.numericValue(
            exif.object(forKey: kCGImagePropertyExifISOSpeedRatings)
           ),
           let aperture = Self.numericValue(
            exif.object(forKey: kCGImagePropertyExifFNumber)
           ) {
            let metadata = CameraExposureMetadata(
                exposureSeconds: exposureSeconds,
                sensitivityISO: sensitivityISO,
                aperture: aperture
            )
            if metadata.isValid {
                return metadata
            }
        }

        guard !device.isAdjustingExposure else { return nil }
        let metadata = CameraExposureMetadata(
            exposureSeconds: CMTimeGetSeconds(device.exposureDuration),
            sensitivityISO: Double(device.iso),
            aperture: Double(device.lensAperture)
        )
        return metadata.isValid ? metadata : nil
    }

    private static func numericValue(_ value: Any?) -> Double? {
        if let number = value as? NSNumber {
            return number.doubleValue
        }
        if let numbers = value as? [NSNumber] {
            return numbers.first?.doubleValue
        }
        return nil
    }

    private func copyLuminancePlane(_ pixelBuffer: CVPixelBuffer) -> LuminancePlane? {
        CVPixelBufferLockBaseAddress(pixelBuffer, .readOnly)
        defer { CVPixelBufferUnlockBaseAddress(pixelBuffer, .readOnly) }
        guard CVPixelBufferGetPlaneCount(pixelBuffer) > 0,
              let baseAddress = CVPixelBufferGetBaseAddressOfPlane(pixelBuffer, 0) else {
            return nil
        }
        let width = CVPixelBufferGetWidthOfPlane(pixelBuffer, 0)
        let height = CVPixelBufferGetHeightOfPlane(pixelBuffer, 0)
        let bytesPerRow = CVPixelBufferGetBytesPerRowOfPlane(pixelBuffer, 0)
        let source = baseAddress.assumingMemoryBound(to: UInt8.self)
        var values = [UInt8](repeating: 0, count: width * height)
        for row in 0..<height {
            _ = values.withUnsafeMutableBytes { destination in
                memcpy(
                    destination.baseAddress!.advanced(by: row * width),
                    source.advanced(by: row * bytesPerRow),
                    width
                )
            }
        }
        let videoRange = CVPixelBufferGetPixelFormatType(pixelBuffer)
            == kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange
        return LuminancePlane(
            width: width,
            height: height,
            values: values,
            isVideoRange: videoRange
        )
    }

    private func makePortraitImage(_ pixelBuffer: CVPixelBuffer) -> CGImage? {
        let image = CIImage(cvPixelBuffer: pixelBuffer)
            .oriented(.right)
        return ciContext.createCGImage(image, from: image.extent)
    }

    private static let analysisIntervalNanoseconds: Int64 = 200_000_000
}

private enum CameraError: LocalizedError {
    case noBackCamera
    case cannotAddInput
    case cannotAddOutput

    var errorDescription: String? {
        switch self {
        case .noBackCamera: "未找到后置摄像头"
        case .cannotAddInput: "无法连接摄像头输入"
        case .cannotAddOutput: "无法创建视频分析输出"
        }
    }
}

private extension NSLock {
    func withLock<T>(_ action: () -> T) -> T {
        lock()
        defer { unlock() }
        return action()
    }
}
