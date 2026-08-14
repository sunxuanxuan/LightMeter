import SwiftUI
import UIKit

private enum AppDestination {
    case modeSelection
    case professional
    case filmPreview
}

struct AppRoute: View {
    @ObservedObject var metering: MeteringViewModel
    @ObservedObject var filmPreview: FilmPreviewViewModel
    let themeStyle: AppThemeStyle
    let onThemeStyleChanged: (AppThemeStyle) -> Void
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
                    },
                    themeStyle: themeStyle,
                    onThemeStyleChanged: onThemeStyleChanged
                )
            case .filmPreview:
                FilmPreviewScreen(
                    viewModel: filmPreview,
                    onSwitchMode: {
                        filmPreview.leaveFilmPreviewMode()
                        destination = .modeSelection
                    },
                    themeStyle: themeStyle,
                    onThemeStyleChanged: onThemeStyleChanged
                )
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
        if destination == .filmPreview, scenePhase == .active {
            filmPreview.start(
                calibrationOffset: metering.settings.calibrationOffset
            )
        } else {
            filmPreview.stop()
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
        .background(Color(uiColor: .systemBackground).ignoresSafeArea())
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
            .background(
                Color(uiColor: .secondarySystemBackground),
                in: RoundedRectangle(cornerRadius: 14)
            )
            .overlay {
                RoundedRectangle(cornerRadius: 14)
                    .stroke(Color(uiColor: .separator))
            }
        }
        .buttonStyle(.plain)
    }
}
