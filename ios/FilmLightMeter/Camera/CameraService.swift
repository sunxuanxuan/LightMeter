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

struct CameraAnalyzedFrame: @unchecked Sendable {
    let snapshot: ExposureSnapshot
    let image: CGImage
}

final class CameraService: NSObject, ObservableObject, @unchecked Sendable {
    let session = AVCaptureSession()

    @Published private(set) var permissionState: CameraPermissionState = .unknown
    @Published private(set) var isRunning = false
    @Published private(set) var errorMessage: String?
    @Published private(set) var capabilities: CameraCapabilities?

    var onFrame: (@Sendable (CameraAnalyzedFrame) -> Void)?

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
    private var probeContinuation: CheckedContinuation<ExposureSnapshot?, Never>?
    private var probeDiscardCount = 0
    private var probeToken: UUID?
    private var probeBaselineSettingEV: Double?
    private var probeDirection = 0.0
    private var bracketOriginalBias: Float?
    private var latestSnapshot: ExposureSnapshot?

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
        cancelProbe()
        sessionQueue.async { [weak self] in
            guard let self else { return }
            if self.session.isRunning {
                self.session.stopRunning()
            }
            Task { @MainActor in self.isRunning = false }
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
                    device.unlockForConfiguration()
                    continuation.resume(returning: target)
                } catch {
                    continuation.resume(returning: nil)
                }
            }
        }
    }

    func captureProbe(offsetStops: Float) async -> ExposureSnapshot? {
        guard abs(offsetStops) >= 1 else { return nil }
        let originalBias = stateLock.withLock { () -> Float in
            if let bracketOriginalBias { return bracketOriginalBias }
            let value = device?.exposureTargetBias ?? 0
            bracketOriginalBias = value
            return value
        }
        let baselineSettingEV = stateLock.withLock {
            latestSnapshot.map { MeteringEngine.cameraSettingEV100($0.metadata) }
        }
        let applied = await setExposureBias(originalBias + offsetStops)
        guard applied else { return nil }
        return await withTaskCancellationHandler {
            await withCheckedContinuation { continuation in
                let token = UUID()
                stateLock.withLock {
                    probeContinuation?.resume(returning: nil)
                    probeContinuation = continuation
                    probeDiscardCount = 2
                    probeToken = token
                    probeBaselineSettingEV = baselineSettingEV
                    probeDirection = offsetStops < 0 ? 1 : -1
                }
                outputQueue.asyncAfter(deadline: .now() + .milliseconds(800)) {
                    self.stateLock.withLock {
                        guard self.probeToken == token else { return }
                        self.probeContinuation?.resume(returning: nil)
                        self.clearProbeState()
                    }
                }
            }
        } onCancel: {
            self.cancelProbe()
        }
    }

    func restoreExposureBias() async {
        let original = stateLock.withLock { () -> Float? in
            defer { bracketOriginalBias = nil }
            return bracketOriginalBias
        }
        guard let original else { return }
        _ = await setExposureBias(original)
    }

    func cancelProbe() {
        stateLock.withLock {
            probeContinuation?.resume(returning: nil)
            clearProbeState()
        }
        Task { await restoreExposureBias() }
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
            maximumZoomFactor: min(Double(device.maxAvailableVideoZoomFactor), 12),
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

    private func setExposureBias(_ requested: Float) async -> Bool {
        await withCheckedContinuation { continuation in
            sessionQueue.async { [weak self] in
                guard let device = self?.device else {
                    continuation.resume(returning: false)
                    return
                }
                let target = min(
                    max(requested, device.minExposureTargetBias),
                    device.maxExposureTargetBias
                )
                guard abs(target) >= 1 || requested == 0 else {
                    continuation.resume(returning: false)
                    return
                }
                do {
                    try device.lockForConfiguration()
                    device.setExposureTargetBias(target) { _ in
                        continuation.resume(returning: true)
                    }
                    device.unlockForConfiguration()
                } catch {
                    continuation.resume(returning: false)
                }
            }
        }
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
        guard nanoseconds - lastAnalysisNanoseconds >= 100_000_000 else { return }
        guard let pixelBuffer = CMSampleBufferGetImageBuffer(sampleBuffer),
              let device,
              let plane = copyLuminancePlane(pixelBuffer),
              let metadata = exposureMetadata(sampleBuffer: sampleBuffer, device: device) else {
            return
        }

        let currentConfiguration = stateLock.withLock { configuration }
        guard let snapshot = analyzer.analyze(
            plane: plane,
            metadata: metadata,
            timestampNanoseconds: nanoseconds,
            configuration: currentConfiguration
        ) else {
            return
        }
        lastAnalysisNanoseconds = nanoseconds
        stateLock.withLock {
            latestSnapshot = snapshot
        }

        if let image = makePortraitImage(pixelBuffer) {
            onFrame?(CameraAnalyzedFrame(snapshot: snapshot, image: image))
        }
        completeProbeIfReady(snapshot)
    }

    private func exposureMetadata(
        sampleBuffer: CMSampleBuffer,
        device: AVCaptureDevice
    ) -> CameraExposureMetadata? {
        if let attachments = CMCopyDictionaryOfAttachments(
            allocator: kCFAllocatorDefault,
            target: sampleBuffer,
            attachmentMode: kCMAttachmentMode_ShouldPropagate
        ) as? NSDictionary,
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
            values.withUnsafeMutableBytes { destination in
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

    private func completeProbeIfReady(_ snapshot: ExposureSnapshot) {
        stateLock.withLock {
            guard probeContinuation != nil else { return }
            if probeDiscardCount > 0 || device?.isAdjustingExposure == true {
                probeDiscardCount = max(probeDiscardCount - 1, 0)
                return
            }
            if let baseline = probeBaselineSettingEV {
                let current = MeteringEngine.cameraSettingEV100(snapshot.metadata)
                guard (current - baseline) * probeDirection >= 0.8 else { return }
            }
            probeContinuation?.resume(returning: snapshot)
            clearProbeState()
        }
    }

    private func clearProbeState() {
        probeContinuation = nil
        probeDiscardCount = 0
        probeToken = nil
        probeBaselineSettingEV = nil
        probeDirection = 0
    }
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
