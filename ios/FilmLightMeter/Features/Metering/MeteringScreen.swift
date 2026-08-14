import SwiftUI
import UIKit

struct MeteringScreen: View {
    @ObservedObject var viewModel: MeteringViewModel
    let onSwitchMode: () -> Void

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            switch viewModel.camera.permissionState {
            case .denied:
                PermissionRequiredView()
            case .unknown:
                ProgressView("正在请求相机权限")
            case .granted:
                meteringContent
            }
            VStack {
                HStack {
                    Button(action: onSwitchMode) {
                        Label("模式", systemImage: "square.grid.2x2")
                            .padding(.horizontal, 10)
                            .padding(.vertical, 8)
                            .background(.ultraThinMaterial, in: Capsule())
                    }
                    .accessibilityLabel("切换模式")
                    Spacer()
                }
                Spacer()
            }
            .padding()
        }
        .sheet(isPresented: $viewModel.showsSettings) {
            SettingsScreen(
                initialSettings: viewModel.settings,
                onSave: { viewModel.applySettings($0) }
            )
        }
        .alert(
            "FilmLightMeter",
            isPresented: Binding(
                get: { viewModel.errorMessage != nil },
                set: { _ in }
            )
        ) {
            Button("确定", role: .cancel) {
                viewModel.dismissError()
            }
        } message: {
            Text(viewModel.errorMessage ?? "")
        }
    }

    private var meteringContent: some View {
        GeometryReader { geometry in
            VStack(spacing: 0) {
                preview
                    .frame(maxWidth: .infinity)
                    .frame(height: geometry.size.height * 0.64)
                    .clipped()
                    .onAppear {
                        viewModel.updatePreviewSize(
                            width: geometry.size.width,
                            height: geometry.size.height * 0.64
                        )
                    }
                controlPanel
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .background(Color(red: 0.08, green: 0.08, blue: 0.09))
            }
        }
    }

    private var preview: some View {
        GeometryReader { geometry in
            ZStack {
                CameraPreview(session: viewModel.camera.session)
                if let frozen = viewModel.isExposureSimulationEnabled
                    ? viewModel.simulatedFrozenImage ?? viewModel.frozenImage
                    : viewModel.frozenImage {
                    Image(decorative: frozen, scale: 1)
                        .resizable()
                        .scaledToFill()
                }
                if !viewModel.isExposureSimulationEnabled,
                   let risk = viewModel.riskImage {
                    Image(decorative: risk, scale: 1)
                        .resizable()
                        .scaledToFill()
                        .allowsHitTesting(false)
                }
                ViewfinderOverlay(rect: viewModel.viewfinder)
                SpotMeteringOverlay(
                    viewfinder: viewModel.viewfinder,
                    point: viewModel.spotMeteringPoint,
                    areaPercent: viewModel.settings.spotAreaPercent,
                    isVisible: viewModel.settings.meteringMode == .spot
                        || viewModel.spotMeteringPoint != nil
                )
                if let pair = viewModel.recommendation?.primary {
                    ExposureScaleOverlay(
                        apertureLabels: ExposureEngine.apertureLabels,
                        shutterLabels: ExposureEngine.shutterLabels,
                        selectedPair: pair,
                        onApertureStep: { viewModel.stepAperture($0) },
                        onShutterStep: { viewModel.stepShutter($0) }
                    )
                    .padding(.horizontal, 10)
                    .padding(.vertical, 56)
                }
                topStatus
                freezeButton
            }
            .contentShape(Rectangle())
            .onTapGesture { point in
                viewModel.selectSpot(
                    x: point.x / geometry.size.width,
                    y: point.y / geometry.size.height
                )
            }
        }
    }

    private var topStatus: some View {
        VStack {
            HStack {
                Color.clear.frame(width: 70, height: 1)
                if (viewModel.isFrozen || viewModel.isFreezing),
                   !viewModel.isExposureSimulationEnabled,
                   viewModel.riskImage != nil {
                    Label(
                        String(format: "高光 %.1f%%", viewModel.highlightRiskRatio * 100),
                        systemImage: "sun.max.fill"
                    )
                    .foregroundStyle(.red)
                    Label(
                        String(format: "暗部 %.1f%%", viewModel.shadowRiskRatio * 100),
                        systemImage: "moon.fill"
                    )
                    .foregroundStyle(.green)
                }
                Spacer()
                Button {
                    viewModel.showsSettings = true
                } label: {
                    Image(systemName: "gearshape.fill")
                        .padding(10)
                        .background(.ultraThinMaterial, in: Circle())
                }
                .accessibilityLabel("设置")
            }
            .font(.caption.bold())
            .padding()
            Spacer()
        }
    }

    private var freezeButton: some View {
        VStack {
            Spacer()
            HStack(spacing: 12) {
                if !viewModel.isFrozen, viewModel.spotMeteringPoint != nil {
                    Button {
                        viewModel.restoreMeteringMode()
                    } label: {
                        Image(systemName: "scope")
                            .font(.title3)
                            .frame(width: 50, height: 50)
                            .background(.ultraThinMaterial, in: Circle())
                    }
                    .accessibilityLabel("恢复默认测光")
                }
                Button {
                    viewModel.toggleFreeze()
                } label: {
                    Image(systemName: viewModel.isFrozen ? "play.fill" : "pause.fill")
                        .font(.title3)
                        .frame(width: 50, height: 50)
                        .background(.ultraThinMaterial, in: Circle())
                }
                .overlay {
                    if viewModel.isFreezing {
                        ProgressView()
                    }
                }
                .accessibilityLabel(viewModel.isFrozen ? "恢复实时测光" : "冻结画面")
                if viewModel.isFrozen,
                   viewModel.riskImage != nil,
                   viewModel.simulatedFrozenImage != nil {
                    Button {
                        viewModel.toggleExposureSimulation()
                    } label: {
                        Image(
                            systemName: viewModel.isExposureSimulationEnabled
                                ? "eye.slash.fill" : "eye.fill"
                        )
                        .font(.title3)
                        .frame(width: 50, height: 50)
                        .background(
                            viewModel.isExposureSimulationEnabled
                                ? Color.orange : Color.clear,
                            in: Circle()
                        )
                        .background(.ultraThinMaterial, in: Circle())
                    }
                    .accessibilityLabel(
                        viewModel.isExposureSimulationEnabled
                            ? "关闭曝光模拟" : "开启曝光模拟"
                    )
                }
            }
            .padding(.bottom, 14)
        }
    }

    private var controlPanel: some View {
        ScrollView(.vertical, showsIndicators: false) {
            VStack(spacing: 12) {
                HStack(alignment: .firstTextBaseline) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("EV100")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                        Text(viewModel.displayedEV100.map { String(format: "%.1f", $0) } ?? "--")
                            .font(.system(size: 36, weight: .semibold, design: .rounded))
                    }
                    Spacer()
                    VStack(alignment: .trailing, spacing: 2) {
                        Text("曝光组合")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                        Text(recommendationText)
                            .font(.title2.bold())
                            .foregroundStyle(.orange)
                    }
                }

                HStack(spacing: 12) {
                    Menu {
                        ForEach(Array(stride(from: 100, through: 1600, by: 50)), id: \.self) {
                            value in
                            Button("ISO \(value)") { viewModel.setISO(value) }
                        }
                    } label: {
                        ParameterCard(title: "ISO", value: "\(viewModel.settings.selectedISO)")
                    }

                    Menu {
                        ForEach(-9...9, id: \.self) { step in
                            let value = Double(step) / 3
                            Button(String(format: "%+.1f EV", value)) {
                                viewModel.setExposureCompensation(value)
                            }
                        }
                    } label: {
                        ParameterCard(
                            title: "曝光补偿",
                            value: String(
                                format: "%+.1f",
                                viewModel.settings.exposureCompensation
                            )
                        )
                    }
                }

                VStack(spacing: 4) {
                    HStack {
                        Text(viewModel.settings.frameFormat.displayName)
                        Spacer()
                        Text(
                            String(
                                format: "%.0f mm · %.2fx",
                                viewModel.settings.focalLengthMillimeters,
                                viewModel.actualZoomFactor
                            )
                        )
                    }
                    .font(.caption)
                    Slider(
                        value: Binding(
                            get: { viewModel.settings.focalLengthMillimeters },
                            set: { viewModel.setFocalLength($0) }
                        ),
                        in: viewModel.focalLengthRange,
                        step: 1
                    )
                    .tint(.orange)
                    .disabled(
                        viewModel.isFrozen
                            || viewModel.isFreezing
                            || viewModel.focalLengthRange.lowerBound
                                == viewModel.focalLengthRange.upperBound
                    )
                    if viewModel.isZoomLimited {
                        Text("当前设备变焦能力受限")
                            .font(.caption2)
                            .foregroundStyle(.red)
                    }
                }
            }
            .padding(16)
        }
    }

    private var recommendationText: String {
        guard let pair = viewModel.recommendation?.primary else { return "--" }
        return "\(pair.apertureLabel) · \(pair.shutterLabel)"
    }
}

private struct ExposureScaleOverlay: View {
    let apertureLabels: [String]
    let shutterLabels: [String]
    let selectedPair: ExposurePair
    let onApertureStep: (Int) -> Void
    let onShutterStep: (Int) -> Void

    var body: some View {
        HStack {
            if let index = apertureLabels.firstIndex(of: selectedPair.apertureLabel) {
                ExposureSideScale(
                    title: "光圈",
                    values: apertureLabels,
                    selectedIndex: index,
                    onStep: onApertureStep
                )
            }
            Spacer()
            if let index = shutterLabels.firstIndex(of: selectedPair.shutterLabel) {
                ExposureSideScale(
                    title: "快门",
                    values: shutterLabels,
                    selectedIndex: index,
                    onStep: onShutterStep
                )
            }
        }
    }
}

private struct ExposureSideScale: View {
    let title: String
    let values: [String]
    let selectedIndex: Int
    let onStep: (Int) -> Void

    @State private var dragOffset: CGFloat = 0
    @State private var emittedSteps = 0

    var body: some View {
        VStack(spacing: 1) {
            Text(title)
                .font(.caption2)
                .foregroundStyle(.white.opacity(0.58))
            VStack(spacing: 1) {
                ForEach(-4...4, id: \.self) { offset in
                    let distance = abs(offset)
                    Text(values[safe: selectedIndex + offset] ?? " ")
                        .font(offset == 0 ? .headline : .subheadline)
                        .foregroundStyle(offset == 0 ? .orange : .white)
                        .opacity(itemOpacity(distance))
                        .scaleEffect(itemScale(distance))
                        .frame(width: 60, height: 22)
                        .lineLimit(1)
                }
            }
            .offset(y: dragOffset)
        }
        .padding(.horizontal, 4)
        .padding(.vertical, 9)
        .background(.black.opacity(0.54), in: RoundedRectangle(cornerRadius: 12))
        .gesture(
            DragGesture(minimumDistance: 3)
                .onChanged { value in
                    let threshold: CGFloat = 28
                    let steps = Int(-value.translation.height / threshold)
                    if steps != emittedSteps {
                        onStep(steps - emittedSteps)
                        emittedSteps = steps
                    }
                    dragOffset = value.translation.height
                        .truncatingRemainder(dividingBy: threshold)
                }
                .onEnded { _ in
                    dragOffset = 0
                    emittedSteps = 0
                }
        )
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("\(title) \(values[selectedIndex])")
        .accessibilityAdjustableAction { direction in
            onStep(direction == .increment ? 1 : -1)
        }
    }

    private func itemScale(_ distance: Int) -> CGFloat {
        switch distance {
        case 0: 1.2
        case 1: 1
        case 2: 0.9
        case 3: 0.8
        default: 0.72
        }
    }

    private func itemOpacity(_ distance: Int) -> Double {
        switch distance {
        case 0: 1
        case 1: 0.86
        case 2: 0.68
        case 3: 0.5
        default: 0.36
        }
    }
}

private struct SpotMeteringOverlay: View {
    let viewfinder: NormalizedRect
    let point: NormalizedPoint?
    let areaPercent: Int
    let isVisible: Bool

    var body: some View {
        GeometryReader { geometry in
            if isVisible {
                let frameWidth = viewfinder.width * geometry.size.width
                let frameHeight = viewfinder.height * geometry.size.height
                let center = CGPoint(
                    x: (point?.x ?? viewfinder.centerX) * geometry.size.width,
                    y: (point?.y ?? viewfinder.centerY) * geometry.size.height
                )
                let radius = sqrt(
                    frameWidth * frameHeight * CGFloat(areaPercent) / 100 / .pi
                ) * 0.25
                Circle()
                    .stroke(.white, lineWidth: 2)
                    .frame(width: radius * 2, height: radius * 2)
                    .position(center)
            }
        }
        .allowsHitTesting(false)
    }
}

private struct ParameterCard: View {
    let title: String
    let value: String

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(title)
                .font(.caption)
                .foregroundStyle(.secondary)
            Text(value)
                .font(.title3.bold())
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(12)
        .background(Color.white.opacity(0.07), in: RoundedRectangle(cornerRadius: 12))
    }
}

private extension Collection {
    subscript(safe index: Index) -> Element? {
        indices.contains(index) ? self[index] : nil
    }
}

private struct ViewfinderOverlay: View {
    let rect: NormalizedRect

    var body: some View {
        GeometryReader { geometry in
            let frame = CGRect(
                x: rect.left * geometry.size.width,
                y: rect.top * geometry.size.height,
                width: rect.width * geometry.size.width,
                height: rect.height * geometry.size.height
            )
            Path { path in
                path.addRect(CGRect(origin: .zero, size: geometry.size))
                path.addRect(frame)
            }
            .fill(.black.opacity(0.42), style: FillStyle(eoFill: true))
            Path { $0.addRect(frame) }
                .stroke(.white, lineWidth: 1.5)
        }
        .allowsHitTesting(false)
    }
}

private struct PermissionRequiredView: View {
    var body: some View {
        VStack(spacing: 18) {
            Image(systemName: "camera.fill")
                .font(.system(size: 54))
            Text("需要相机权限")
                .font(.title2.bold())
            Text("请在系统设置中允许 FilmLightMeter 使用相机。")
                .foregroundStyle(.secondary)
            Button("打开设置") {
                guard let url = URL(string: UIApplication.openSettingsURLString) else { return }
                UIApplication.shared.open(url)
            }
            .buttonStyle(.borderedProminent)
            .tint(.orange)
        }
        .padding()
    }
}
