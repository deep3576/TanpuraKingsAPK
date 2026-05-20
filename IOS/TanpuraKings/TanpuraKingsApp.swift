import SwiftUI
import GoogleMobileAds

@main
struct TanpuraKingsApp: App {
    init() {
        MobileAds.shared.start()
        InterstitialAdManager.shared.load()
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}
