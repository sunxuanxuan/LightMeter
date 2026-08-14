import SwiftUI

@main
struct FilmLightMeterApp: App {
    @StateObject private var activation = ActivationStore()
    @StateObject private var metering = MeteringViewModel()

    var body: some Scene {
        WindowGroup {
            Group {
                if activation.isActivated {
                    AppRoute(metering: metering)
                } else {
                    ActivationScreen(store: activation)
                }
            }
            .preferredColorScheme(.dark)
        }
    }
}
