import SwiftUI
import UIKit

struct FilmPreviewScreen: View {
    @ObservedObject var viewModel: FilmPreviewViewModel
    let onSwitchMode: () -> Void
    let themeStyle: AppThemeStyle
    let onThemeStyleChanged: (AppThemeStyle) -> Void

    @State private var frozenChromeVisible = true

    var body: some View {
        ZStack {
            Color(uiColor: .systemBackground).ignoresSafeArea()
            switch viewModel.camera.permissionState {
            case .denied:
                FilmPreviewPermissionView(onSwitchMode: onSwitchMode)
            case .unknown:
                ProgressView("正在请求相机权限")
            case .granted:
                content
            }
        }
        .sheet(isPresented: $viewModel.showsSettings) {
            FilmPreviewSettingsScreen(
                presets: viewModel.presets,
                selectedPreset: viewModel.selectedPreset,
                manualConfig: viewModel.manualConfig,
                themeStyle: themeStyle,
                onThemeStyleChanged: onThemeStyleChanged,
                onSave: viewModel.savePresetSettings
            )
        }
        .onChange(of: viewModel.isFrozen) { _, _ in
            frozenChromeVisible = true
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

    private var content: some View {
        GeometryReader { geometry in
            ScrollView(.vertical, showsIndicators: false) {
                VStack(spacing: 0) {
                    preview
                        .aspectRatio(2.0 / 3.0, contentMode: .fit)
                        .frame(maxWidth: .infinity)
                        .padding(.horizontal, 10)
                        .padding(.vertical, 8)
                        .onAppear {
                            viewModel.updatePreviewSize(
                                width: geometry.size.width - 20,
                                height: (geometry.size.width - 20) * 1.5
                            )
                        }
                    statusPanel
                }
            }
        }
    }

    private var preview: some View {
        GeometryReader { _ in
            ZStack {
                Color.black
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
                FilmPreviewViewfinderOverlay(rect: viewModel.viewfinder)
                riskLegend
                if chromeVisible {
                    topControls
                    freezeControls
                }
            }
            .contentShape(Rectangle())
            .onTapGesture {
                if viewModel.isFrozen {
                    frozenChromeVisible.toggle()
                }
            }
        }
        .clipShape(RoundedRectangle(cornerRadius: 8))
        .overlay {
            RoundedRectangle(cornerRadius: 8)
                .stroke(Color(red: 0.83, green: 0.67, blue: 0.37).opacity(0.72))
        }
    }

    @ViewBuilder
    private var riskLegend: some View {
        if viewModel.isFrozen,
           !viewModel.isExposureSimulationEnabled,
           viewModel.riskImage != nil {
            VStack {
                HStack(spacing: 12) {
                    FilmRiskLegendItem(
                        color: .red,
                        text: String(
                            format: "高光 %.1f%%",
                            viewModel.highlightRiskRatio * 100
                        )
                    )
                    FilmRiskLegendItem(
                        color: .green,
                        text: String(
                            format: "暗部 %.1f%%",
                            viewModel.shadowRiskRatio * 100
                        )
                    )
                }
                .padding(.horizontal, 10)
                .padding(.vertical, 7)
                .background(.black.opacity(0.68), in: RoundedRectangle(cornerRadius: 8))
                .padding(.top, 10)
                Spacer()
            }
            .allowsHitTesting(false)
        }
    }

    private var topControls: some View {
        VStack {
            HStack {
                Button(action: onSwitchMode) {
                    Label("模式", systemImage: "square.grid.2x2")
                        .font(.caption.bold())
                        .foregroundStyle(filmGold)
                        .padding(.horizontal, 10)
                        .padding(.vertical, 7)
                        .background(.black.opacity(0.68), in: Capsule())
                        .overlay {
                            Capsule().stroke(.white.opacity(0.12))
                        }
                }
                .accessibilityLabel("切换模式")
                Spacer()
                Button {
                    viewModel.showsSettings = true
                } label: {
                    Image(systemName: "gearshape.fill")
                        .font(.system(size: 16))
                        .foregroundStyle(filmGold)
                        .frame(width: 36, height: 36)
                        .background(.black.opacity(0.68), in: Circle())
                        .overlay {
                            Circle().stroke(.white.opacity(0.12))
                        }
                }
                .accessibilityLabel("设置")
            }
            .padding(10)
            Spacer()
        }
    }

    private var freezeControls: some View {
        VStack {
            Spacer()
            HStack(spacing: 10) {
                Button {
                    viewModel.toggleFreeze()
                } label: {
                    Image(systemName: viewModel.isFrozen ? "play.fill" : "pause.fill")
                        .font(.title3)
                        .foregroundStyle(.black)
                        .frame(width: 52, height: 52)
                        .background(.white.opacity(viewModel.isZoomReady ? 1 : 0.35), in: Circle())
                }
                .disabled(
                    !viewModel.isFrozen
                        && (!viewModel.isZoomReady || viewModel.meteredEV100 == nil)
                )
                .overlay {
                    if viewModel.isFreezing {
                        ProgressView()
                    }
                }
                .accessibilityLabel(viewModel.isFrozen ? "恢复实时预览" : "冻结画面")

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
                        .foregroundStyle(.black)
                        .frame(width: 52, height: 52)
                        .background(
                            viewModel.isExposureSimulationEnabled
                                ? filmGold : .white,
                            in: Circle()
                        )
                    }
                    .accessibilityLabel(
                        viewModel.isExposureSimulationEnabled
                            ? "关闭曝光模拟" : "开启曝光模拟"
                    )
                }
            }
            .padding(.bottom, 12)
        }
    }

    private var statusPanel: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(viewModel.selectedPreset.displayName)
                .font(.headline)
            Text("参数由预设锁定")
                .font(.caption)
                .foregroundStyle(.secondary)
            Spacer().frame(height: 4)
            Text("胶片预设固定曝光：\(formatEV(viewModel.evaluation.presetEV100))")
            Text(
                "场景推荐曝光：\(viewModel.meteredEV100.map(formatEV) ?? "--")"
            )
            Text(
                "相对差值：\(viewModel.evaluation.sceneDeltaEV.map(formatSignedEV) ?? "--")"
            )
            Text(adviceText)
                .font(.caption)
                .foregroundStyle(adviceIsWarning ? .red : .secondary)
                .padding(.top, 2)
            HStack(spacing: 8) {
                ReadOnlyFilmParameter(
                    label: "ISO",
                    value: "\(viewModel.selectedPreset.film.iso)"
                )
                ReadOnlyFilmParameter(
                    label: "光圈",
                    value: "f/\(formatDecimal(viewModel.selectedPreset.optics.aperture))"
                )
                ReadOnlyFilmParameter(
                    label: "快门",
                    value: shutterLabel(viewModel.selectedPreset.shutterSeconds)
                )
                ReadOnlyFilmParameter(
                    label: "焦距",
                    value: "\(formatDecimal(viewModel.selectedPreset.optics.focalLengthMillimeters)) mm"
                )
            }
            .padding(.top, 8)
        }
        .font(.subheadline)
        .padding(.horizontal, 18)
        .padding(.vertical, 12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color(uiColor: .secondarySystemBackground))
        .overlay(alignment: .top) {
            Divider()
        }
    }

    private var adviceText: String {
        switch viewModel.evaluation.adviceCode {
        case .suitable:
            "当前环境亮度适合这组固定参数。"
        case .useFlash:
            viewModel.selectedPreset.flash.map {
                "环境偏暗，建议开启闪光灯并让主体保持在 "
                    + "\(formatDecimal($0.effectiveDistanceMinMeters))-"
                    + "\(formatDecimal($0.effectiveDistanceMaxMeters)) m。"
            } ?? "环境偏暗，建议增加现场光线。"
        case .ambientTooDark:
            "环境超出暗部宽容度，成片可能明显欠曝。"
        case .ambientTooBright:
            "环境超出高光宽容度，亮部细节可能丢失。"
        case .unavailable:
            "正在读取当前场景亮度。"
        }
    }

    private var adviceIsWarning: Bool {
        switch viewModel.evaluation.adviceCode {
        case .suitable, .unavailable: false
        default: true
        }
    }

    private var chromeVisible: Bool {
        !viewModel.isFrozen || frozenChromeVisible
    }

    private var filmGold: Color {
        Color(red: 0.83, green: 0.67, blue: 0.37)
    }
}

private struct FilmRiskLegendItem: View {
    let color: Color
    let text: String

    var body: some View {
        HStack(spacing: 5) {
            Circle().fill(color).frame(width: 8, height: 8)
            Text(text)
                .font(.caption2.bold())
                .foregroundStyle(.white)
        }
    }
}

private struct ReadOnlyFilmParameter: View {
    let label: String
    let value: String

    var body: some View {
        VStack(spacing: 2) {
            Text(label)
                .font(.caption2)
                .foregroundStyle(.secondary)
            Text(value)
                .font(.caption.bold())
                .lineLimit(1)
                .minimumScaleFactor(0.7)
        }
        .frame(maxWidth: .infinity, minHeight: 42)
        .background(
            Color(uiColor: .tertiarySystemBackground),
            in: RoundedRectangle(cornerRadius: 6)
        )
        .overlay {
            RoundedRectangle(cornerRadius: 6)
                .stroke(Color(uiColor: .separator))
        }
    }
}

private struct FilmPreviewViewfinderOverlay: View {
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

private struct FilmPreviewPermissionView: View {
    let onSwitchMode: () -> Void

    var body: some View {
        VStack(spacing: 18) {
            Image(systemName: "camera.fill")
                .font(.system(size: 54))
            Text("需要相机权限")
                .font(.title2.bold())
            Text("请在系统设置中允许 FilmLightMeter 使用相机。")
                .foregroundStyle(.secondary)
            Button("打开设置") {
                guard let url = URL(string: UIApplication.openSettingsURLString) else {
                    return
                }
                UIApplication.shared.open(url)
            }
            .buttonStyle(.borderedProminent)
            .tint(.orange)
            Button("返回模式选择", action: onSwitchMode)
        }
        .padding()
    }
}

func formatDecimal(_ value: Double) -> String {
    value == value.rounded()
        ? String(format: "%.0f", value)
        : String(format: "%.1f", value)
}

func shutterLabel(_ seconds: Double) -> String {
    seconds >= 1
        ? "\(formatDecimal(seconds)) s"
        : "1/\(Int((1 / seconds).rounded())) s"
}

private func formatEV(_ value: Double) -> String {
    String(format: "%.1f EV", value)
}

private func formatSignedEV(_ value: Double) -> String {
    String(format: "%+.1f EV", value)
}
