import SwiftUI
import GoogleMobileAds
import AppTrackingTransparency

@main
struct TanpuraKingsApp: App {
    @State private var didRequestATT = false
    @State private var splashDone = false

    init() {
        #if DEBUG
        MobileAds.shared.requestConfiguration.testDeviceIdentifiers = [
            "b3521832b95e96a2e53992aa7dcd94cf", // Android test device
            "b20f11d7d93dbfea6f6ea7ed2365fae4"  // iOS test device
        ]
        #endif
        MobileAds.shared.start()
        // Start loading the interstitial immediately so it's ready by the
        // time the splash finishes.
        InterstitialAdManager.shared.load()
    }

    var body: some Scene {
        WindowGroup {
            if splashDone {
                ContentView()
                    .onReceive(NotificationCenter.default.publisher(
                        for: UIApplication.didBecomeActiveNotification
                    )) { _ in
                        requestTrackingOnce()
                    }
            } else {
                SplashView {
                    splashDone = true
                    // Request ATT after splash — app is fully active by now.
                    requestTrackingOnce()
                }
            }
        }
    }

    private func requestTrackingOnce() {
        guard !didRequestATT else { return }
        didRequestATT = true
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) {
            ATTrackingManager.requestTrackingAuthorization { _ in
                DispatchQueue.main.async {
                    InterstitialAdManager.shared.load()
                }
            }
        }
    }
}
