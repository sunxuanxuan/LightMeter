import SwiftUI
import UIKit

struct MeteringScreen: View {
    @ObservedObject var viewModel: MeteringViewModel

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
        }
        .task { viewModel.start() }
        .sheet(isPresented: $viewModel.showsSettings) {
            SettingsScreen(
                initialSettings: viewModel.settings,
                onSave: viewModel.applySettings
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
                if let frozen = viewModel.frozenImage {
                    Image(decorative: frozen, scale: 1)
                        .resizable()
                        .scaledToFill()
                }
                if let risk = viewModel.riskImage {
                    Image(decorative: risk, scale: 1)
                        .resizable()
                        .scaledToFill()
                        .allowsHitTesting(false)
                }
                ViewfinderOverlay(rect: viewModel.viewfinder)
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
                if viewModel.isFrozen || viewModel.isFreezing {
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
            .padding(.bottom, 14)
            .accessibilityLabel(viewModel.isFrozen ? "恢复实时测光" : "冻结画面")
        }
    }

    private var controlPanel: some View {
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
                    Text("推荐")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                    Text(recommendationText)
                        .font(.title2.bold())
                        .foregroundStyle(.orange)
                }
            }

            HStack(spacing: 12) {
                Menu {
                    ForEach(stride(from: 100, through: 1600, by: 50), id: \.self) {
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
                        set: viewModel.setFocalLength
                    ),
                    in: 20...120,
                    step: 1
                )
                .tint(.orange)
                .disabled(viewModel.isFrozen || viewModel.isFreezing)
            }
        }
        .padding(16)
    }

    private var recommendationText: String {
        guard let pair = viewModel.recommendation?.primary else { return "--" }
        return "\(pair.apertureLabel) · \(pair.shutterLabel)"
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
