import Combine
import CoreGraphics
import CoreImage
import Foundation

@MainActor
final class MeteringViewModel: ObservableObject {
    @Published var settings: AppSettings
    @Published private(set) var displayedEV100: Double?
    @Published private(set) var recommendation: ExposureRecommendation?
    @Published private(set) var frozenImage: CGImage?
    @Published private(set) var simulatedFrozenImage: CGImage?
    @Published private(set) var riskImage: CGImage?
    @Published private(set) var highlightRiskRatio = 0.0
    @Published private(set) var shadowRiskRatio = 0.0
    @Published private(set) var viewfinder = NormalizedRect.full
    @Published private(set) var spotMeteringPoint: NormalizedPoint?
    @Published private(set) var focalLengthRange: ClosedRange<Double> = 20...150
    @Published private(set) var actualZoomFactor = 1.0
    @Published private(set) var isZoomLimited = false
    @Published private(set) var isZoomReady = false
    @Published private(set) var isExposureSimulationEnabled = false
    @Published private(set) var isFrozen = false
    @Published private(set) var isFreezing = false
    @Published private(set) var errorMessage: String?
    @Published var showsSettings = false

    let camera: CameraService

    private let settingsStore: SettingsStore
    private var configuration = MeteringConfiguration()
    private var selectedAperture: Double?
    private var frozenSnapshot: ExposureSnapshot?
    private var previewAspectRatio = 9.0 / 16.0
    private var previousDisplayedEV: Double?
    private var cancellables = Set<AnyCancellable>()
    private var riskTask: Task<Void, Never>?
    private var simulationTask: Task<Void, Never>?
    private var zoomRequestID = UUID()

    init(
        camera: CameraService = CameraService(),
        settingsStore: SettingsStore = UserDefaultsSettingsStore()
    ) {
        self.camera = camera
        self.settingsStore = settingsStore
        settings = settingsStore.load()
        camera.onMeteringResult = { [weak self] result in
            Task { @MainActor in self?.receive(result) }
        }
        camera.onFrameCaptured = { [weak self] frame in
            Task { @MainActor in self?.receiveCapturedFrame(frame) }
        }
        camera.$errorMessage
            .receive(on: DispatchQueue.main)
            .sink { [weak self] in self?.errorMessage = $0 }
            .store(in: &cancellables)
        camera.objectWillChange
            .receive(on: DispatchQueue.main)
            .sink { [weak self] _ in self?.objectWillChange.send() }
            .store(in: &cancellables)
        camera.$capabilities
            .compactMap { $0 }
            .receive(on: DispatchQueue.main)
            .sink { [weak self] _ in self?.applyFocalLength() }
            .store(in: &cancellables)
        refreshConfiguration(invalidate: false)
    }

    func start() {
        Task {
            await camera.requestAccessAndStart()
            applyFocalLength(invalidateMetering: !isFrozen)
        }
    }

    func stop() {
        zoomRequestID = UUID()
        isZoomReady = false
        previousDisplayedEV = nil
        riskTask?.cancel()
        simulationTask?.cancel()
        if isFreezing {
            camera.cancelFrameCapture()
            isFreezing = false
        }
        camera.stop()
    }

    func leaveProfessionalMode() {
        if isFrozen || isFreezing {
            resumeLive()
        }
        restoreMeteringMode()
        stop()
    }

    func updatePreviewSize(width: Double, height: Double) {
        guard width > 0, height > 0 else { return }
        let aspect = width / height
        guard abs(aspect - previewAspectRatio) > 0.001 else { return }
        previewAspectRatio = aspect
        applyFocalLength()
    }

    func selectSpot(x: Double, y: Double) {
        guard !isFrozen, !isFreezing, viewfinder.contains(x: x, y: y) else { return }
        let point = NormalizedPoint(x: x, y: y)
        configuration.mode = .spot
        configuration.spotPoint = point
        configuration.revision += 1
        spotMeteringPoint = point
        camera.updateConfiguration(configuration)
    }

    func restoreMeteringMode() {
        configuration.mode = settings.meteringMode
        configuration.spotPoint = nil
        configuration.revision += 1
        spotMeteringPoint = nil
        camera.updateConfiguration(configuration)
    }

    func setISO(_ value: Int) {
        settings.selectedISO = value
        settings.normalize()
        persistSettings()
        updateRecommendation()
    }

    func setExposureCompensation(_ value: Double) {
        settings.exposureCompensation = value
        settings.normalize()
        persistSettings()
        updateRecommendation()
        if isFrozen {
            recalculateRisk()
            recalculateFrozenSimulation()
        }
    }

    func stepAperture(_ delta: Int) {
        guard delta != 0,
              let recommendation,
              let currentIndex = ExposureEngine.apertureLabels.firstIndex(
                of: recommendation.primary.apertureLabel
              ) else {
            return
        }
        let nextIndex = min(
            max(currentIndex + delta, 0),
            ExposureEngine.apertureLabels.count - 1
        )
        guard let pair = ExposureEngine.closestPair(
            apertureLabel: ExposureEngine.apertureLabels[nextIndex],
            targetEV: recommendation.targetEV
        ) else {
            return
        }
        selectedAperture = pair.aperture
        self.recommendation = ExposureRecommendation(
            targetEV: recommendation.targetEV,
            primary: pair,
            equivalents: recommendation.equivalents
        )
    }

    func stepShutter(_ delta: Int) {
        guard delta != 0,
              let recommendation,
              let currentIndex = ExposureEngine.shutterLabels.firstIndex(
                of: recommendation.primary.shutterLabel
              ) else {
            return
        }
        let nextIndex = min(
            max(currentIndex + delta, 0),
            ExposureEngine.shutterLabels.count - 1
        )
        guard let pair = ExposureEngine.closestPair(
            shutterLabel: ExposureEngine.shutterLabels[nextIndex],
            targetEV: recommendation.targetEV
        ) else {
            return
        }
        selectedAperture = pair.aperture
        self.recommendation = ExposureRecommendation(
            targetEV: recommendation.targetEV,
            primary: pair,
            equivalents: recommendation.equivalents
        )
    }

    func setFocalLength(_ value: Double) {
        guard !isFrozen, !isFreezing else { return }
        settings.focalLengthMillimeters = min(
            max(value, focalLengthRange.lowerBound),
            focalLengthRange.upperBound
        )
        settings.normalize()
        persistSettings()
        restoreMeteringMode()
        applyFocalLength()
    }

    func applySettings(_ newSettings: AppSettings) {
        var normalized = newSettings
        normalized.normalize()
        do {
            try settingsStore.save(normalized)
        } catch {
            errorMessage = "设置保存失败：\(error.localizedDescription)"
            return
        }
        if isFreezing {
            resumeLive()
        }
        let preserveFrozenFrame = isFrozen
        settings = normalized
        showsSettings = false
        refreshConfiguration(invalidate: !preserveFrozenFrame)
        applyFocalLength(invalidateMetering: !preserveFrozenFrame)
        updateRecommendation()
        if preserveFrozenFrame {
            recalculateRisk()
            recalculateFrozenSimulation()
        }
    }

    func toggleFreeze() {
        if isFrozen || isFreezing {
            resumeLive()
        } else {
            freeze()
        }
    }

    func toggleExposureSimulation() {
        guard isFrozen, riskImage != nil, simulatedFrozenImage != nil else { return }
        isExposureSimulationEnabled.toggle()
    }

    func dismissError() {
        errorMessage = nil
    }

    private func receive(_ result: MeteringResult) {
        guard isZoomReady, !isFrozen, !isFreezing,
              result.revision == configuration.revision else {
            return
        }
        let current = result.meteredEV100
        displayedEV100 = previousDisplayedEV.map {
            $0 * (1 - Self.smoothingWeight) + current * Self.smoothingWeight
        } ?? current
        previousDisplayedEV = displayedEV100
        updateRecommendation()
    }

    private func freeze() {
        guard camera.isRunning, isZoomReady, displayedEV100 != nil else {
            errorMessage = "当前没有可冻结的稳定测光帧"
            return
        }
        isFreezing = true
        errorMessage = nil
        camera.requestFrameCapture()
    }

    private func receiveCapturedFrame(_ frame: CameraCapturedFrame?) {
        guard isFreezing else { return }
        guard let frame, frame.snapshot.revision == configuration.revision else {
            isFreezing = false
            errorMessage = "无法定格当前预览，请重试"
            return
        }
        frozenImage = frame.image
        frozenSnapshot = frame.snapshot
        displayedEV100 = frame.snapshot.meteredEV100
        previousDisplayedEV = displayedEV100
        isFreezing = false
        isFrozen = true
        isExposureSimulationEnabled = false
        updateRecommendation()
        recalculateRisk()
        recalculateFrozenSimulation()
    }

    private func resumeLive() {
        riskTask?.cancel()
        riskTask = nil
        simulationTask?.cancel()
        simulationTask = nil
        camera.cancelFrameCapture()
        isFreezing = false
        isFrozen = false
        frozenImage = nil
        simulatedFrozenImage = nil
        frozenSnapshot = nil
        riskImage = nil
        highlightRiskRatio = 0
        shadowRiskRatio = 0
        isExposureSimulationEnabled = false
    }

    private func recalculateRisk() {
        riskTask?.cancel()
        guard settings.exposureRiskEnabled, let snapshot = frozenSnapshot else {
            riskImage = nil
            isExposureSimulationEnabled = false
            return
        }
        let reference = ExposureRiskEngine.referenceEV100(
            frozenMeteredEV100: snapshot.meteredEV100,
            exposureCompensation: settings.exposureCompensation
        )
        let baseline = snapshot.exposureMap
        let currentViewfinder = viewfinder
        let highlightLatitude = settings.highlightLatitude
        let shadowLatitude = settings.shadowLatitude
        riskTask = Task {
            let mask = await Task.detached(priority: .userInitiated) {
                ExposureRiskEngine.calculate(
                    baseline: baseline,
                    viewfinder: currentViewfinder,
                    referenceEV100: reference,
                    highlightLatitude: highlightLatitude,
                    shadowLatitude: shadowLatitude
                )
            }.value
            guard !Task.isCancelled else { return }
            riskImage = Self.makeImage(mask)
            highlightRiskRatio = mask.highlightRatio
            shadowRiskRatio = mask.shadowRatio
        }
    }

    private func updateRecommendation() {
        guard let displayedEV100 else {
            recommendation = nil
            return
        }
        guard let generated = ExposureEngine.recommendation(
            meteredEV100: displayedEV100,
            filmISO: settings.selectedISO,
            exposureCompensation: settings.exposureCompensation,
            focalLengthMillimeters: settings.focalLengthMillimeters
        ) else {
            recommendation = nil
            return
        }
        let selected = selectedAperture.flatMap { aperture in
            generated.equivalents.min(by: {
                abs($0.aperture - aperture) < abs($1.aperture - aperture)
            })
        } ?? generated.primary
        recommendation = ExposureRecommendation(
            targetEV: generated.targetEV,
            primary: selected,
            equivalents: generated.equivalents
        )
    }

    private func recalculateFrozenSimulation() {
        simulationTask?.cancel()
        guard let frozenImage else {
            simulatedFrozenImage = nil
            return
        }
        let compensation = settings.exposureCompensation
        guard compensation != 0 else {
            simulatedFrozenImage = frozenImage
            return
        }
        simulationTask = Task {
            let rendered = await Task.detached(priority: .userInitiated) {
                ExposureCompensationRenderer.render(
                    image: frozenImage,
                    stops: compensation
                )
            }.value
            guard !Task.isCancelled else { return }
            simulatedFrozenImage = rendered ?? frozenImage
        }
    }

    @discardableResult
    private func persistSettings() -> Bool {
        do {
            try settingsStore.save(settings)
            return true
        } catch {
            errorMessage = "设置保存失败：\(error.localizedDescription)"
            return false
        }
    }

    private func refreshConfiguration(invalidate: Bool) {
        configuration.mode = settings.meteringMode
        configuration.spotPoint = nil
        spotMeteringPoint = nil
        configuration.spotAreaPercent = settings.spotAreaPercent
        configuration.centerAreaPercent = settings.centerAreaPercent
        configuration.centerWeightPercent = settings.centerWeightPercent
        configuration.previewAspectRatio = previewAspectRatio
        configuration.isZoomReady = isZoomReady
        configuration.calibrationOffset = settings.calibrationOffset
        configuration.viewfinder = viewfinder
        if invalidate {
            configuration.revision += 1
            displayedEV100 = nil
            previousDisplayedEV = nil
        }
        camera.updateConfiguration(configuration)
    }

    private func applyFocalLength(invalidateMetering: Bool = true) {
        guard let capabilities = camera.capabilities else { return }
        let supportedRange = ViewfinderEngine.supportedFocalLengthRange(
            previewAspectRatio: previewAspectRatio,
            frameFormat: settings.frameFormat,
            cameraHorizontalFieldOfViewDegrees: capabilities.horizontalFieldOfViewDegrees,
            minimumZoomFactor: capabilities.minimumZoomFactor,
            maximumZoomFactor: capabilities.maximumZoomFactor,
            allowedRange: 20...150
        )
        focalLengthRange = supportedRange ?? 20...20
        let supportedFocalLength = min(
            max(settings.focalLengthMillimeters, focalLengthRange.lowerBound),
            focalLengthRange.upperBound
        )
        if supportedFocalLength != settings.focalLengthMillimeters {
            settings.focalLengthMillimeters = supportedFocalLength
            persistSettings()
        }
        let projection = ViewfinderEngine.projection(
            previewAspectRatio: previewAspectRatio,
            frameFormat: settings.frameFormat,
            targetFocalLengthMillimeters: supportedFocalLength,
            cameraHorizontalFieldOfViewDegrees: capabilities.horizontalFieldOfViewDegrees
        )
        isZoomLimited = projection.fitZoomFactor < capabilities.minimumZoomFactor - 0.01
            || projection.fitZoomFactor > capabilities.maximumZoomFactor + 0.01
        let zoom = ViewfinderEngine.constrainedZoom(
            fitZoomFactor: projection.fitZoomFactor,
            minimum: capabilities.minimumZoomFactor,
            maximum: capabilities.maximumZoomFactor
        )
        viewfinder = projection.rect(actualZoomFactor: zoom)
        isZoomReady = false
        configuration.mode = settings.meteringMode
        configuration.spotPoint = nil
        spotMeteringPoint = nil
        configuration.viewfinder = viewfinder
        configuration.previewAspectRatio = previewAspectRatio
        configuration.isZoomReady = false
        configuration.revision += 1
        if invalidateMetering {
            displayedEV100 = nil
            previousDisplayedEV = nil
        }
        camera.updateConfiguration(configuration)
        let requestID = UUID()
        zoomRequestID = requestID
        Task {
            let applied = await camera.setZoomFactor(zoom)
            guard zoomRequestID == requestID else { return }
            let effectiveZoom = applied ?? actualZoomFactor
            actualZoomFactor = effectiveZoom
            viewfinder = projection.rect(actualZoomFactor: effectiveZoom)
            isZoomLimited = applied == nil
                || projection.fitZoomFactor < capabilities.minimumZoomFactor - 0.01
                || projection.fitZoomFactor > capabilities.maximumZoomFactor + 0.01
            isZoomReady = true
            configuration.viewfinder = viewfinder
            configuration.isZoomReady = true
            camera.updateConfiguration(configuration)
            if isFrozen {
                recalculateRisk()
            }
        }
    }

    private static func makeImage(_ mask: ExposureRiskMask) -> CGImage? {
        guard let provider = CGDataProvider(data: Data(mask.bgra) as CFData) else {
            return nil
        }
        return CGImage(
            width: mask.width,
            height: mask.height,
            bitsPerComponent: 8,
            bitsPerPixel: 32,
            bytesPerRow: mask.width * 4,
            space: CGColorSpaceCreateDeviceRGB(),
            bitmapInfo: CGBitmapInfo(
                rawValue: CGImageAlphaInfo.premultipliedLast.rawValue
                    | CGBitmapInfo.byteOrder32Little.rawValue
            ),
            provider: provider,
            decode: nil,
            shouldInterpolate: false,
            intent: .defaultIntent
        )
    }

    private static let smoothingWeight = 0.44
}

private enum ExposureCompensationRenderer {
    static func render(
        image: CGImage,
        stops: Double
    ) -> CGImage? {
        guard stops.isFinite else { return nil }
        if stops == 0 { return image }
        let input = CIImage(cgImage: image)
        let output = input.applyingFilter(
            "CIExposureAdjust",
            parameters: [kCIInputEVKey: stops]
        )
        return simulationContext.createCGImage(output, from: input.extent)
    }

    private static let simulationContext = CIContext(
        options: [.cacheIntermediates: false]
    )
}
