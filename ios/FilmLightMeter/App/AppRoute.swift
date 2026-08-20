import SwiftUI
import UIKit

private enum AppDestination {
    case modeSelection
    case filmPreview
    case instantCamera
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
                    onFilmPreviewSelected: { destination = .filmPreview },
                    onInstantCameraSelected: { destination = .instantCamera }
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
            case .instantCamera:
                InstantCameraEntryScreen {
                    destination = .modeSelection
                }
            }
        }
        .onAppear(perform: updateCameraState)
        .onChange(of: destination) { _, _ in updateCameraState() }
        .onChange(of: scenePhase) { _, _ in updateCameraState() }
    }

    private func updateCameraState() {
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
    let onFilmPreviewSelected: () -> Void
    let onInstantCameraSelected: () -> Void

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
                    title: "胶片模拟",
                    description: "拍前曝光风险与闪光灯建议",
                    action: onFilmPreviewSelected
                )
                ModeCard(
                    systemImage: "camera.instant",
                    title: "拍立得预览",
                    description: "拍前曝光风险与档位建议",
                    action: onInstantCameraSelected
                )
            }
            Spacer()
        }
        .padding(.horizontal, 24)
        .padding(.vertical, 28)
        .background(Color(uiColor: .systemBackground).ignoresSafeArea())
    }
}

private struct InstantCameraEntryScreen: View {
    let onExit: () -> Void

    var body: some View {
        VStack(spacing: 0) {
            Spacer()
            Text("拍立得预览")
                .font(.title.bold())
            Text("机型预设正在准备中")
                .font(.body)
                .foregroundStyle(.secondary)
                .padding(.top, 8)
                .padding(.bottom, 20)
            Button("返回", action: onExit)
                .buttonStyle(.borderedProminent)
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
