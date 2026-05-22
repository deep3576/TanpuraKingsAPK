import SwiftUI
import GoogleMobileAds


private func activeRootViewController() -> UIViewController? {
    let scenes = UIApplication.shared.connectedScenes
        .compactMap { $0 as? UIWindowScene }
        .filter { $0.activationState == .foregroundActive }

    for scene in scenes {
        if let keyWindow = scene.windows.first(where: { $0.isKeyWindow }),
           let root = keyWindow.rootViewController {
            return root
        }
        if let root = scene.windows.first(where: { !$0.isHidden })?.rootViewController {
            return root
        }
    }
    return nil
}

// MARK: - Banner Ad

struct BannerAdView: UIViewRepresentable {

    func makeCoordinator() -> Coordinator { Coordinator() }

    func makeUIView(context: Context) -> UIView {
        let container = UIView()
        container.backgroundColor = .clear

        let banner = BannerView(adSize: AdSizeBanner)
        banner.adUnitID = "ca-app-pub-3492509358962490/6841346333"
        banner.delegate = context.coordinator
        banner.translatesAutoresizingMaskIntoConstraints = false
        container.addSubview(banner)

        NSLayoutConstraint.activate([
            banner.centerXAnchor.constraint(equalTo: container.centerXAnchor),
            banner.bottomAnchor.constraint(equalTo: container.bottomAnchor),
        ])

        // Store the banner so updateUIView can set rootViewController
        // once the view is actually in the window hierarchy.
        context.coordinator.banner = banner
        return container
    }

    func updateUIView(_ uiView: UIView, context: Context) {
        guard let banner = context.coordinator.banner else { return }
        // rootViewController is only available once the view is in a window.
        if banner.rootViewController == nil,
           let rootVC = activeRootViewController() {
            banner.rootViewController = rootVC
            banner.load(Request())
        }
    }

    final class Coordinator: NSObject, BannerViewDelegate {
        var banner: BannerView?

        func bannerViewDidReceiveAd(_ bannerView: BannerView) {
            debugLog("Banner ad loaded successfully")
        }

        func bannerView(_ bannerView: BannerView, didFailToReceiveAdWithError error: Error) {
            debugLog("Banner ad failed: \(error.localizedDescription)")
            // Retry after 30 seconds
            DispatchQueue.main.asyncAfter(deadline: .now() + 30) { [weak bannerView] in
                bannerView?.load(Request())
            }
        }
    }
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
        // Don't reload if we already have one ready
        guard interstitialAd == nil else { return }
        InterstitialAd.load(
            with: "ca-app-pub-3492509358962490/4402885253",
            request: Request()
        ) { [weak self] ad, error in
            if let error = error {
                debugLog("Interstitial failed to load: \(error.localizedDescription)")
                // Retry after 60 seconds
                DispatchQueue.main.asyncAfter(deadline: .now() + 60) {
                    self?.load()
                }
                return
            }
            self?.interstitialAd = ad
            debugLog("Interstitial ad loaded successfully")
        }
    }

    func showIfReady() {
        guard Date().timeIntervalSince(lastShowTime) >= minInterval else { return }
        guard let ad = interstitialAd else {
            load()
            return
        }
        guard let rootVC = activeRootViewController() else { return }

        ad.present(from: rootVC)
        lastShowTime = Date()
        interstitialAd = nil
        // Pre-load next
        load()
    }
}
