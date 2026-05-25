import SwiftUI

private let tunerLoadingStages: [(Float, String)] = [
    (0.25, "Stopping drone..."),
    (0.55, "Starting microphone..."),
    (0.80, "Loading advertisement..."),
    (1.00, "Ready!")
]

/// Full-screen overlay shown when switching from the Drone tab to the Tuner tab.
/// Mirrors the game-style splash screen: dark background, radial glow, animated
/// orange progress bar, then an interstitial ad before calling `onFinished`.
struct TabTransitionView: View {
    let onFinished: () -> Void

    @State private var progress: Float = 0
    @State private var stageLabel: String = tunerLoadingStages.first!.1

    var body: some View {
        ZStack {
            // Deep dark base
            Color(red: 0.04, green: 0.04, blue: 0.10)
                .ignoresSafeArea()

            // Radial glow
            RadialGradient(
                colors: [
                    Color(red: 0.33, green: 0, blue: 0.67).opacity(0.5),
                    Color.clear
                ],
                center: .center,
                startRadius: 0,
                endRadius: 160
            )
            .frame(width: 320, height: 320)

            VStack(spacing: 0) {
                Spacer()

                // Logo
                Image("Logo")
                    .resizable()
                    .scaledToFit()
                    .frame(width: 110, height: 110)

                Spacer().frame(height: 16)

                Text("TANPURA KINGS")
                    .font(.system(size: 26, weight: .bold))
                    .tracking(4)
                    .foregroundColor(.white)

                Spacer().frame(height: 4)

                Text("Switching to Tuner...")
                    .font(.system(size: 12))
                    .foregroundColor(Color(white: 0.67))

                Spacer()

                // Progress bar section
                VStack(alignment: .leading, spacing: 8) {
                    Text(stageLabel)
                        .font(.system(size: 12))
                        .foregroundColor(Color(red: 1.0, green: 0.65, blue: 0))
                        .animation(.easeInOut(duration: 0.3), value: stageLabel)

                    GeometryReader { geo in
                        ZStack(alignment: .leading) {
                            RoundedRectangle(cornerRadius: 3)
                                .fill(Color.white.opacity(0.1))
                                .frame(height: 6)

                            RoundedRectangle(cornerRadius: 3)
                                .fill(
                                    LinearGradient(
                                        colors: [
                                            Color(red: 1.0, green: 0.65, blue: 0),
                                            Color(red: 1.0, green: 0.40, blue: 0)
                                        ],
                                        startPoint: .leading,
                                        endPoint: .trailing
                                    )
                                )
                                .frame(width: geo.size.width * CGFloat(progress), height: 6)
                                .animation(.easeInOut(duration: 0.4), value: progress)
                        }
                    }
                    .frame(height: 6)

                    HStack {
                        Spacer()
                        Text("\(Int(progress * 100))%")
                            .font(.system(size: 11))
                            .foregroundColor(Color(white: 0.53))
                    }
                }
                .padding(.horizontal, 40)

                Spacer().frame(height: 40)
            }
        }
        .task {
            await runTransitionSequence()
        }
    }

    private func runTransitionSequence() async {
        // Step through each loading stage
        for (target, label) in tunerLoadingStages.dropLast() {
            await MainActor.run {
                progress = target
                stageLabel = label
            }
            try? await Task.sleep(nanoseconds: 350_000_000)
        }

        // Poll for ad ready (up to 2 more seconds)
        var waited: Double = 0
        while waited < 2.0 && !InterstitialAdManager.shared.isReady {
            try? await Task.sleep(nanoseconds: 100_000_000)
            waited += 0.1
        }

        // Final step
        await MainActor.run {
            progress = 1.0
            stageLabel = "Ready!"
        }
        try? await Task.sleep(nanoseconds: 300_000_000)

        await MainActor.run {
            if InterstitialAdManager.shared.isReady {
                InterstitialAdManager.shared.showIfReady(onDismissed: onFinished, ignoreCooldown: false)
            } else {
                onFinished()
            }
        }
    }
}
