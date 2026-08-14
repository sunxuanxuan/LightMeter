import SwiftUI

enum AppThemeStyle: String, CaseIterable, Identifiable {
    case light
    case dark

    var id: String { rawValue }

    var displayName: String {
        switch self {
        case .light: "浅色"
        case .dark: "深色"
        }
    }
}

@main
struct FilmLightMeterApp: App {
    @StateObject private var activation = ActivationStore()
    @StateObject private var metering = MeteringViewModel()
    @StateObject private var filmPreview = FilmPreviewViewModel()
    @AppStorage("app_theme_style") private var themeStyleRaw = AppThemeStyle.dark.rawValue

    var body: some Scene {
        WindowGroup {
            Group {
                if activation.isActivated {
                    AppRoute(
                        metering: metering,
                        filmPreview: filmPreview,
                        themeStyle: themeStyle,
                        onThemeStyleChanged: {
                            themeStyleRaw = $0.rawValue
                        }
                    )
                } else {
                    ActivationScreen(store: activation)
                }
            }
            .preferredColorScheme(themeStyle == .dark ? .dark : .light)
        }
    }

    private var themeStyle: AppThemeStyle {
        AppThemeStyle(rawValue: themeStyleRaw) ?? .dark
    }
}
