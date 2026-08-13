import Combine
import CoreGraphics
import Foundation

@MainActor
final class MeteringViewModel: ObservableObject {
    @Published var settings: AppSettings
    @Published private(set) var displayedEV100: Double?
    @Published private(set) var recommendation: ExposureRecommendation?
    @Published private(set) var frozenImage: CGImage?
    @Published private(set) var riskImage: CGImage?
    @Published private(set) var highlightRiskRatio = 0.0
    @Published private(set) var shadowRiskRatio = 0.0
    @Published private(set) var viewfinder = NormalizedRect.full
    @Published private(set) var actualZoomFactor = 1.0
    @Published private(set) var isFrozen = false
    @Published private(set) var isFreezing = false
    @Published private(set) var errorMessage: String?
    @Published var showsSettings = false

    let camera: CameraService

    private let settingsStore: SettingsStore
    private var configuration = MeteringConfiguration()
    private var latestFrame: CameraAnalyzedFrame?
    private var frozenSnapshot: ExposureSnapshot?
    private var shadowProbe: ExposureMap?
    private var highlightProbe: ExposureMap?
    private var previewAspectRatio = 9.0 / 16.0
    private var previousDisplayedEV: Double?
    private var cancellables = Set<AnyCancellable>()
    private var freezeTask: Task<Void, Never>?
    private var riskTask: Task<Void, Never>?

    init(
        camera: CameraService = CameraService(),
        settingsStore: SettingsStore = UserDefaultsSettingsStore()
    ) {
        self.camera = camera
        self.settingsStore = settingsStore
        settings = settingsStore.load()
        camera.onFrame = { [weak self] frame in
            Task { @MainActor in self?.receive(frame) }
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
        Task { await camera.requestAccessAndStart() }
    }

    func stop() {
        freezeTask?.cancel()
        riskTask?.cancel()
        camera.stop()
    }

    func updatePreviewSize(width: Double, height: Double) {
        guard width > 0, height > 0 else { return }
        let aspect = width / height
        guard abs(aspect - previewAspectRatio) > 0.001 else { return }
        previewAspectRatio = aspect
        applyFocalLength()
    }

    func selectSpot(x: Double, y: Double) {
        guard !isFrozen else { return }
        configuration.mode = .spot
        configuration.spotPoint = NormalizedPoint(x: x, y: y)
        configuration.revision += 1
        camera.updateConfiguration(configuration)
    }

    func restoreMeteringMode() {
        configuration.mode = settings.meteringMode
        configuration.spotPoint = nil
        configuration.revision += 1
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
        }
    }

    func setFocalLength(_ value: Double) {
        guard !isFrozen else { return }
        settings.focalLengthMillimeters = value
        settings.normalize()
        persistSettings()
        applyFocalLength()
    }

    func applySettings(_ newSettings: AppSettings) {
        settings = newSettings
        settings.normalize()
        if persistSettings() {
            showsSettings = false
            refreshConfiguration(invalidate: true)
            applyFocalLength()
            updateRecommendation()
        }
    }

    func toggleFreeze() {
        if isFrozen || isFreezing {
            resumeLive()
        } else {
            freeze()
        }
    }

    func dismissError() {
        errorMessage = nil
    }

    private func receive(_ frame: CameraAnalyzedFrame) {
        latestFrame = frame
        guard !isFrozen, !isFreezing,
              frame.snapshot.revision == configuration.revision else {
            return
        }
        let current = frame.snapshot.meteredEV100
        displayedEV100 = previousDisplayedEV.map { $0 * 0.75 + current * 0.25 } ?? current
        previousDisplayedEV = displayedEV100
        updateRecommendation()
    }

    private func freeze() {
        guard let frame = latestFrame,
              frame.snapshot.revision == configuration.revision else {
            errorMessage = "当前没有可冻结的稳定测光帧"
            return
        }
        isFreezing = true
        frozenImage = frame.image
        frozenSnapshot = frame.snapshot
        shadowProbe = nil
        highlightProbe = nil
        recalculateRisk()

        freezeTask = Task { [weak self] in
            guard let self, let snapshot = self.frozenSnapshot else { return }
            let reference = ExposureRiskEngine.referenceEV100(
                frozenMeteredEV100: snapshot.meteredEV100,
                exposureCompensation: self.settings.exposureCompensation
            )
            let requirements = ExposureRiskEngine.probeRequirements(
                map: snapshot.exposureMap,
                viewfinder: self.viewfinder,
                referenceEV100: reference,
                highlightLatitude: self.settings.highlightLatitude,
                shadowLatitude: self.settings.shadowLatitude
            )

            if requirements.highlight, !Task.isCancelled {
                self.highlightProbe = await self.camera.captureProbe(offsetStops: -2)?
                    .exposureMap
            }
            if requirements.shadow, !Task.isCancelled {
                self.shadowProbe = await self.camera.captureProbe(offsetStops: 2)?
                    .exposureMap
            }
            await self.camera.restoreExposureBias()
            guard !Task.isCancelled else { return }
            self.isFreezing = false
            self.isFrozen = true
            self.recalculateRisk()
        }
    }

    private func resumeLive() {
        freezeTask?.cancel()
        freezeTask = nil
        riskTask?.cancel()
        riskTask = nil
        camera.cancelProbe()
        isFreezing = false
        isFrozen = false
        frozenImage = nil
        frozenSnapshot = nil
        riskImage = nil
        shadowProbe = nil
        highlightProbe = nil
        highlightRiskRatio = 0
        shadowRiskRatio = 0
    }

    private func recalculateRisk() {
        riskTask?.cancel()
        guard settings.exposureRiskEnabled, let snapshot = frozenSnapshot else {
            riskImage = nil
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
        let currentShadowProbe = shadowProbe
        let currentHighlightProbe = highlightProbe
        riskTask = Task {
            let mask = await Task.detached(priority: .userInitiated) {
                ExposureRiskEngine.calculate(
                    baseline: baseline,
                    viewfinder: currentViewfinder,
                    referenceEV100: reference,
                    highlightLatitude: highlightLatitude,
                    shadowLatitude: shadowLatitude,
                    shadowProbe: currentShadowProbe,
                    highlightProbe: currentHighlightProbe
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
        recommendation = ExposureEngine.recommendation(
            meteredEV100: displayedEV100,
            filmISO: settings.selectedISO,
            exposureCompensation: settings.exposureCompensation,
            focalLengthMillimeters: settings.focalLengthMillimeters
        )
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
        configuration.spotAreaPercent = settings.spotAreaPercent
        configuration.centerAreaPercent = settings.centerAreaPercent
        configuration.centerWeightPercent = settings.centerWeightPercent
        configuration.previewAspectRatio = previewAspectRatio
        configuration.calibrationOffset = settings.calibrationOffset
        configuration.viewfinder = viewfinder
        if invalidate {
            configuration.revision += 1
            displayedEV100 = nil
            previousDisplayedEV = nil
        }
        camera.updateConfiguration(configuration)
    }

    private func applyFocalLength() {
        guard let capabilities = camera.capabilities else { return }
        let projection = ViewfinderEngine.projection(
            previewAspectRatio: previewAspectRatio,
            frameFormat: settings.frameFormat,
            targetFocalLengthMillimeters: settings.focalLengthMillimeters,
            cameraHorizontalFieldOfViewDegrees: capabilities.horizontalFieldOfViewDegrees
        )
        let zoom = ViewfinderEngine.constrainedZoom(
            fitZoomFactor: projection.fitZoomFactor,
            minimum: capabilities.minimumZoomFactor,
            maximum: capabilities.maximumZoomFactor
        )
        viewfinder = projection.rect(actualZoomFactor: zoom)
        configuration.viewfinder = viewfinder
        configuration.previewAspectRatio = previewAspectRatio
        configuration.revision += 1
        displayedEV100 = nil
        previousDisplayedEV = nil
        camera.updateConfiguration(configuration)
        Task {
            actualZoomFactor = await camera.setZoomFactor(zoom) ?? actualZoomFactor
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
}
