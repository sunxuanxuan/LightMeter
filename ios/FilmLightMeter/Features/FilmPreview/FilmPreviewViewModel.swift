import Combine
import CoreGraphics
import Foundation

@MainActor
final class FilmPreviewViewModel: ObservableObject {
    @Published private(set) var presets: [DisposableCameraPreset]
    @Published private(set) var manualConfig: ManualCameraConfig
    @Published private(set) var selectedPreset: DisposableCameraPreset
    @Published private(set) var meteredEV100: Double?
    @Published private(set) var evaluation: FilmPreviewEvaluation
    @Published private(set) var frozenImage: CGImage?
    @Published private(set) var simulatedFrozenImage: CGImage?
    @Published private(set) var riskImage: CGImage?
    @Published private(set) var highlightRiskRatio = 0.0
    @Published private(set) var shadowRiskRatio = 0.0
    @Published private(set) var viewfinder = NormalizedRect.full
    @Published private(set) var isZoomReady = false
    @Published private(set) var isFrozen = false
    @Published private(set) var isFreezing = false
    @Published private(set) var isExposureSimulationEnabled = false
    @Published private(set) var errorMessage: String?
    @Published var showsSettings = false

    let camera: CameraService

    private let settingsStore: FilmPreviewSettingsStore
    private var configuration = MeteringConfiguration()
    private var frozenSnapshot: ExposureSnapshot?
    private var previewAspectRatio = 2.0 / 3.0
    private var calibrationOffset = 0.0
    private var cancellables = Set<AnyCancellable>()
    private var riskTask: Task<Void, Never>?
    private var simulationTask: Task<Void, Never>?
    private var zoomRequestID = UUID()

    init(
        camera: CameraService = CameraService(),
        settingsStore: FilmPreviewSettingsStore =
            UserDefaultsFilmPreviewSettingsStore()
    ) {
        self.camera = camera
        self.settingsStore = settingsStore
        let stored = settingsStore.load()
        let allPresets = [stored.manualConfig.makePreset()]
            + BuiltInDisposableCameraRepository.presets
        let initial = allPresets.first { $0.id == stored.selectedPresetID }
            ?? BuiltInDisposableCameraRepository.presets[0]
        presets = allPresets
        manualConfig = stored.manualConfig
        selectedPreset = initial
        evaluation = FilmPreviewEngine.evaluate(
            meteredEV100: nil,
            preset: initial
        )

        camera.onMeteringResult = { [weak self] result in
            Task { @MainActor in self?.receive(result) }
        }
        camera.onFrameCaptured = { [weak self] frame in
            Task { @MainActor in self?.receiveCapturedFrame(frame) }
        }
        camera.$errorMessage
            .receive(on: DispatchQueue.main)
            .sink { [weak self] message in
                if let message {
                    self?.errorMessage = message
                }
            }
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

    func start(calibrationOffset: Double) {
        if calibrationOffset.isFinite,
           self.calibrationOffset != calibrationOffset {
            self.calibrationOffset = calibrationOffset
            refreshConfiguration(invalidate: true)
        }
        Task {
            await camera.requestAccessAndStart()
            applyFocalLength()
        }
    }

    func stop() {
        zoomRequestID = UUID()
        isZoomReady = false
        riskTask?.cancel()
        simulationTask?.cancel()
        if isFreezing {
            camera.cancelFrameCapture()
            isFreezing = false
        }
        camera.stop()
    }

    func leaveFilmPreviewMode() {
        if isFrozen || isFreezing {
            resumeLive()
        }
        stop()
    }

    func updatePreviewSize(width: Double, height: Double) {
        guard width > 0, height > 0 else { return }
        let aspect = width / height
        guard abs(aspect - previewAspectRatio) > 0.001 else { return }
        previewAspectRatio = aspect
        applyFocalLength()
    }

    func savePresetSettings(
        selectedPresetID: String,
        manualConfig: ManualCameraConfig
    ) -> Bool {
        guard manualConfig.isValid else {
            errorMessage = "手动配置参数超出有效范围"
            return false
        }
        let allPresets = [manualConfig.makePreset()]
            + BuiltInDisposableCameraRepository.presets
        guard let selected = allPresets.first(
            where: { $0.id == selectedPresetID }
        ) else {
            return false
        }
        do {
            try settingsStore.save(
                FilmPreviewSettings(
                    selectedPresetID: selected.id,
                    manualConfig: manualConfig
                )
            )
        } catch {
            errorMessage = "预设保存失败：\(error.localizedDescription)"
            return false
        }
        resumeLive()
        presets = allPresets
        self.manualConfig = manualConfig
        selectedPreset = selected
        meteredEV100 = nil
        evaluation = FilmPreviewEngine.evaluate(
            meteredEV100: nil,
            preset: selected
        )
        errorMessage = nil
        showsSettings = false
        applyFocalLength()
        return true
    }

    func toggleFreeze() {
        if isFrozen || isFreezing {
            resumeLive()
        } else {
            freeze()
        }
    }

    func toggleExposureSimulation() {
        guard isFrozen, riskImage != nil, simulatedFrozenImage != nil else {
            return
        }
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
        meteredEV100 = result.meteredEV100
        evaluation = FilmPreviewEngine.evaluate(
            meteredEV100: result.meteredEV100,
            preset: selectedPreset
        )
    }

    private func freeze() {
        guard camera.isRunning, isZoomReady, meteredEV100 != nil else {
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
        meteredEV100 = frame.snapshot.meteredEV100
        evaluation = FilmPreviewEngine.evaluate(
            meteredEV100: frame.snapshot.meteredEV100,
            preset: selectedPreset
        )
        isFreezing = false
        isFrozen = true
        isExposureSimulationEnabled = false
        recalculateRisk()
        recalculateFrozenSimulation()
    }

    private func resumeLive() {
        riskTask?.cancel()
        simulationTask?.cancel()
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
        guard let snapshot = frozenSnapshot else {
            riskImage = nil
            return
        }
        let baseline = snapshot.exposureMap
        let currentViewfinder = viewfinder
        let preset = selectedPreset
        riskTask = Task {
            let mask = await Task.detached(priority: .userInitiated) {
                ExposureRiskEngine.calculate(
                    baseline: baseline,
                    viewfinder: currentViewfinder,
                    referenceEV100: FilmPreviewEngine.presetEV100(preset),
                    highlightLatitude: preset.film.highlightLatitudeStops,
                    shadowLatitude: preset.film.shadowLatitudeStops
                )
            }.value
            guard !Task.isCancelled else { return }
            riskImage = Self.makeImage(mask)
            highlightRiskRatio = mask.highlightRatio
            shadowRiskRatio = mask.shadowRatio
        }
    }

    private func recalculateFrozenSimulation() {
        simulationTask?.cancel()
        guard let frozenImage, let frozenSnapshot else {
            simulatedFrozenImage = nil
            return
        }
        let preset = selectedPreset
        let offset = calibrationOffset
        simulationTask = Task {
            let rendered = await Task.detached(priority: .userInitiated) {
                FilmExposureRenderer.render(
                    image: frozenImage,
                    snapshot: frozenSnapshot,
                    preset: preset,
                    calibrationOffset: offset
                )
            }.value
            guard !Task.isCancelled else { return }
            simulatedFrozenImage = rendered
        }
    }

    private func refreshConfiguration(invalidate: Bool) {
        configuration.mode = .centerWeighted
        configuration.spotPoint = nil
        configuration.centerAreaPercent = 30
        configuration.centerWeightPercent = 70
        configuration.previewAspectRatio = previewAspectRatio
        configuration.isZoomReady = isZoomReady
        configuration.calibrationOffset = calibrationOffset
        configuration.viewfinder = viewfinder
        if invalidate {
            configuration.revision += 1
            meteredEV100 = nil
            evaluation = FilmPreviewEngine.evaluate(
                meteredEV100: nil,
                preset: selectedPreset
            )
        }
        camera.updateConfiguration(configuration)
    }

    private func applyFocalLength() {
        guard let capabilities = camera.capabilities else { return }
        let projection = ViewfinderEngine.projection(
            previewAspectRatio: previewAspectRatio,
            frameFormat: .film135,
            targetFocalLengthMillimeters:
                selectedPreset.optics.focalLengthMillimeters,
            cameraHorizontalFieldOfViewDegrees:
                capabilities.horizontalFieldOfViewDegrees
        )
        let zoom = ViewfinderEngine.constrainedZoom(
            fitZoomFactor: projection.fitZoomFactor,
            minimum: capabilities.minimumZoomFactor,
            maximum: capabilities.maximumZoomFactor
        )
        isZoomReady = false
        viewfinder = projection.rect(actualZoomFactor: zoom)
        configuration.mode = .centerWeighted
        configuration.viewfinder = viewfinder
        configuration.previewAspectRatio = previewAspectRatio
        configuration.isZoomReady = false
        configuration.revision += 1
        meteredEV100 = nil
        evaluation = FilmPreviewEngine.evaluate(
            meteredEV100: nil,
            preset: selectedPreset
        )
        camera.updateConfiguration(configuration)

        let requestID = UUID()
        zoomRequestID = requestID
        Task {
            let applied = await camera.setZoomFactor(zoom)
            guard zoomRequestID == requestID else { return }
            let effectiveZoom = applied ?? capabilities.currentZoomFactor
            viewfinder = projection.rect(actualZoomFactor: effectiveZoom)
            isZoomReady = applied != nil
            configuration.viewfinder = viewfinder
            configuration.isZoomReady = isZoomReady
            camera.updateConfiguration(configuration)
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
