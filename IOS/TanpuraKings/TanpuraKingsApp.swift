import SwiftUI
import GoogleMobileAds
import AppTrackingTransparency

@main
struct TanpuraKingsApp: App {
    init() {
        #if DEBUG
        MobileAds.shared.requestConfiguration.testDeviceIdentifiers = [
            "b3521832b95e96a2e53992aa7dcd94cf"
        ]
        #endif
        MobileAds.shared.start()
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
                .onReceive(NotificationCenter.default.publisher(
                    for: UIApplication.didBecomeActiveNotification
                )) { _ in
                    requestTrackingIfNeeded()
                }
        }
    }

    /// Request App Tracking Transparency permission, then load ads.
    /// ATT must be requested AFTER the app becomes active (Apple requirement).
    /// Ads are loaded regardless of the user's choice — AdMob will serve
    /// non-personalized ads if tracking is denied.
    private func requestTrackingIfNeeded() {
        ATTrackingManager.requestTrackingAuthorization { _ in
            DispatchQueue.main.async {
                InterstitialAdManager.shared.load()
            }
        }
    }
}
