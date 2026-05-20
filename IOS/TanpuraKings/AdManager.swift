import SwiftUI
import GoogleMobileAds

// MARK: - Banner Ad

struct BannerAdView: UIViewRepresentable {
    func makeUIView(context: Context) -> BannerView {
        let banner = BannerView(adSize: AdSizeBanner)
        banner.adUnitID = "ca-app-pub-3492509358962490/6841346333"
        if let windowScene = UIApplication.shared.connectedScenes.first as? UIWindowScene,
           let rootVC = windowScene.windows.first?.rootViewController {
            banner.rootViewController = rootVC
        }
        banner.load(Request())
        return banner
    }

    func updateUIView(_ uiView: BannerView, context: Context) {}
}

// MARK: - Interstitial Ad

@MainActor
final class InterstitialAdManager: ObservableObject {
    static let shared = InterstitialAdManager()

    private var interstitialAd: InterstitialAd?
    private var lastShowTime: Date = .distantPast
    private let minInterval: TimeInterval = 180 // 3 minutes

    private init() {}

    func load() {
        InterstitialAd.load(
            with: "ca-app-pub-3492509358962490/4402885253",
            request: Request()
        ) { [weak self] ad, error in
            if let error = error {
                print("Interstitial failed to load: \(error.localizedDescription)")
                return
            }
            self?.interstitialAd = ad
        }
    }

    func showIfReady() {
        guard Date().timeIntervalSince(lastShowTime) >= minInterval else { return }
        guard let ad = interstitialAd else {
            load()
            return
        }
        guard let windowScene = UIApplication.shared.connectedScenes.first as? UIWindowScene,
              let rootVC = windowScene.windows.first?.rootViewController else { return }

        ad.present(from: rootVC)
        lastShowTime = Date()
        interstitialAd = nil
        // Pre-load next
        load()
    }
}
