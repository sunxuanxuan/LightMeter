import SwiftUI

private enum AppDestination {
    case modeSelection
    case professional
    case filmPreview
}

struct AppRoute: View {
    @ObservedObject var metering: MeteringViewModel
    @Environment(\.scenePhase) private var scenePhase
    @State private var destination = AppDestination.modeSelection

    var body: some View {
        Group {
            switch destination {
            case .modeSelection:
                ModeSelectionScreen(
                    onProfessionalSelected: { destination = .professional },
                    onFilmPreviewSelected: { destination = .filmPreview }
                )
            case .professional:
                MeteringScreen(
                    viewModel: metering,
                    onSwitchMode: {
                        metering.leaveProfessionalMode()
                        destination = .modeSelection
                    }
                )
            case .filmPreview:
                FilmPreviewPendingScreen {
                    destination = .modeSelection
                }
            }
        }
        .onAppear(perform: updateCameraState)
        .onChange(of: destination) { _, _ in updateCameraState() }
        .onChange(of: scenePhase) { _, _ in updateCameraState() }
    }

    private func updateCameraState() {
        if destination == .professional, scenePhase == .active {
            metering.start()
        } else {
            metering.stop()
        }
    }
}

private struct ModeSelectionScreen: View {
    let onProfessionalSelected: () -> Void
    let onFilmPreviewSelected: () -> Void

    var body: some View {
        VStack(spacing: 0) {
            Spacer()
            Text("FilmLightMeter")
                .font(.system(size: 38, weight: .bold, design: .rounded))
                .foregroundStyle(.orange)
                .frame(maxWidth: .infinity)
            Text("体验胶片摄影魅力")
                .font(.title2.bold())
                .padding(.top, 10)
                .padding(.bottom, 32)
            VStack(spacing: 14) {
                ModeCard(
                    systemImage: "film.stack",
                    title: "胶片预览",
                    description: "固定参数下的曝光效果与宽容度风险",
                    badge: "迁移中",
                    action: onFilmPreviewSelected
                )
                ModeCard(
                    systemImage: "camera.metering.center.weighted",
                    title: "专业测光",
                    description: "完整测光、曝光组合与画幅控制",
                    action: onProfessionalSelected
                )
            }
            Spacer()
        }
        .padding(.horizontal, 24)
        .padding(.vertical, 28)
        .background(Color(red: 0.06, green: 0.06, blue: 0.07).ignoresSafeArea())
    }
}

private struct ModeCard: View {
    let systemImage: String
    let title: String
    let description: String
    var badge: String?
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 16) {
                Image(systemName: systemImage)
                    .font(.title2)
                    .frame(width: 48, height: 48)
                    .foregroundStyle(.orange)
                    .background(.orange.opacity(0.14), in: RoundedRectangle(cornerRadius: 10))
                VStack(alignment: .leading, spacing: 5) {
                    HStack(spacing: 8) {
                        Text(title)
                            .font(.title3.bold())
                        if let badge {
                            Text(badge)
                                .font(.caption2.bold())
                                .foregroundStyle(.secondary)
                        }
                    }
                    Text(description)
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }
                Spacer()
                Label("进入", systemImage: "arrow.right")
                    .labelStyle(.titleAndIcon)
                    .font(.caption.bold())
                    .foregroundStyle(.orange)
            }
            .padding(.horizontal, 18)
            .padding(.vertical, 16)
            .frame(maxWidth: .infinity, minHeight: 116)
            .background(Color.white.opacity(0.07), in: RoundedRectangle(cornerRadius: 14))
            .overlay {
                RoundedRectangle(cornerRadius: 14)
                    .stroke(Color.white.opacity(0.12))
            }
        }
        .buttonStyle(.plain)
    }
}

private struct FilmPreviewPendingScreen: View {
    let onExit: () -> Void

    var body: some View {
        VStack(spacing: 18) {
            Image(systemName: "film.stack")
                .font(.system(size: 56))
                .foregroundStyle(.orange)
            Text("胶片预览迁移中")
                .font(.title2.bold())
            Text("该模式的预设、曝光仿真和风险分析将在下一阶段迁移。")
                .multilineTextAlignment(.center)
                .foregroundStyle(.secondary)
            Button("返回模式选择", action: onExit)
                .buttonStyle(.borderedProminent)
                .tint(.orange)
        }
        .padding(28)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Color(red: 0.06, green: 0.06, blue: 0.07).ignoresSafeArea())
    }
}
