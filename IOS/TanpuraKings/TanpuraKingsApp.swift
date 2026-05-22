import SwiftUI
import GoogleMobileAds
import AppTrackingTransparency

@main
struct TanpuraKingsApp: App {
    /// Tracks whether we've already shown the ATT prompt this launch.
    @State private var didRequestATT = false

    init() {
        #if DEBUG
        MobileAds.shared.requestConfiguration.testDeviceIdentifiers = [
            "b3521832b95e96a2e53992aa7dcd94cf"
        ]
        #endif
        MobileAds.shared.start()
        // Pre-load interstitial immediately — AdMob handles consent
        // internally and serves non-personalized ads if needed.
        InterstitialAdManager.shared.load()
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
                .onReceive(NotificationCenter.default.publisher(
                    for: UIApplication.didBecomeActiveNotification
                )) { _ in
                    requestTrackingOnce()
                }
        }
    }

    /// Request ATT permission once per launch. ATT must be requested AFTER
    /// the app becomes active (Apple requirement). The dialog only shows once
    /// per install — subsequent calls return the stored status instantly.
    private func requestTrackingOnce() {
        guard !didRequestATT else { return }
        didRequestATT = true

        // Small delay ensures the app is fully active before presenting ATT.
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) {
            ATTrackingManager.requestTrackingAuthorization { _ in
                // Reload interstitial after consent — may get personalized ads now.
                DispatchQueue.main.async {
                    InterstitialAdManager.shared.load()
                }
            }
        }
    }
}
