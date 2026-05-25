import SwiftUI

/// Splash screen shown at launch while the app loads and an interstitial
/// ad is fetched. Transitions to the main app after the ad is dismissed
/// (or after a timeout if no ad is available).
struct SplashView: View {

    /// Called once when the splash is done (ad shown+dismissed, or timed out).
    let onFinished: () -> Void

    private let minDisplaySeconds: Double = 2.0
    private let adTimeoutSeconds:  Double = 4.0

    @State private var dotCount = 0
    private let timer = Timer.publish(every: 0.5, on: .main, in: .common).autoconnect()

    var body: some View {
        ZStack {
            // Same gradient as the main app.
            LinearGradient(
                colors: [
                    Color.blue.opacity(0.6),
                    Color(red: 0.5, green: 0, blue: 0.5).opacity(0.8)
                ],
                startPoint: .top,
                endPoint: .bottom
            )
            .ignoresSafeArea()

            VStack(spacing: 0) {
                Spacer()

                Image("Logo")
                    .resizable()
                    .scaledToFit()
                    .frame(width: 120, height: 120)

                Spacer().frame(height: 20)

                Text("Tanpura Kings")
                    .font(.system(size: 28, weight: .bold))
                    .foregroundColor(.white)

                Spacer().frame(height: 8)

                Text("by Kingsman Software Solutions")
                    .font(.system(size: 13))
                    .foregroundColor(.white.opacity(0.7))

                Spacer().frame(height: 48)

                // Animated loading indicator
                HStack(spacing: 8) {
                    ForEach(0..<3, id: \.self) { i in
                        Circle()
                            .fill(Color.orange)
                            .frame(width: 10, height: 10)
                            .opacity(dotCount == i ? 1.0 : 0.3)
                    }
                }
                .onReceive(timer) { _ in
                    dotCount = (dotCount + 1) % 3
                }

                Spacer()

                Text("© Kingsman Software Solutions")
                    .font(.system(size: 11))
                    .foregroundColor(.white.opacity(0.5))
                    .padding(.bottom, 24)
            }
        }
        .task {
            await runSplashSequence()
        }
    }

    private func runSplashSequence() async {
        let start = Date()

        // Wait minimum display time.
        try? await Task.sleep(nanoseconds: UInt64(minDisplaySeconds * 1_000_000_000))

        // Poll for ad ready up to the timeout.
        let waited = Date().timeIntervalSince(start)
        let remaining = max(0, adTimeoutSeconds - waited)
        let pollInterval: Double = 0.1
        var elapsed: Double = 0
        while elapsed < remaining && !InterstitialAdManager.shared.isReady {
            try? await Task.sleep(nanoseconds: UInt64(pollInterval * 1_000_000_000))
            elapsed += pollInterval
        }

        await MainActor.run {
            if InterstitialAdManager.shared.isReady {
                // Show ad — onFinished is called from showIfReady's completion.
                InterstitialAdManager.shared.showIfReady(onDismissed: onFinished, ignoreCooldown: true)
            } else {
                onFinished()
            }
        }
    }
}
