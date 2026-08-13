import SwiftUI

@main
struct FilmLightMeterApp: App {
    @StateObject private var activation = ActivationStore()
    @StateObject private var metering = MeteringViewModel()
    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            Group {
                if activation.isActivated {
                    MeteringScreen(viewModel: metering)
                } else {
                    ActivationScreen(store: activation)
                }
            }
            .preferredColorScheme(.dark)
            .onChange(of: scenePhase) { _, phase in
                switch phase {
                case .active:
                    if activation.isActivated {
                        metering.start()
                    }
                case .inactive, .background:
                    metering.stop()
                @unknown default:
                    break
                }
            }
        }
    }
}
