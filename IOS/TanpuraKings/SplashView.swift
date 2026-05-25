import SwiftUI

private let loadingStages: [(Float, String)] = [
    (0.15, "Initializing audio engine..."),
    (0.35, "Loading tanpura samples..."),
    (0.55, "Preparing sound effects..."),
    (0.75, "Tuning strings..."),
    (0.90, "Loading advertisements..."),
    (1.00, "Ready!")
]

struct SplashView: View {

    let onFinished: () -> Void

    @State private var progress: Float = 0
    @State private var stageLabel: String = loadingStages.first!.1

    var body: some View {
        ZStack {
            // Deep dark base
            Color(red: 0.04, green: 0.04, blue: 0.10)
                .ignoresSafeArea()

            // Radial glow behind logo
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

                // App name
                Text("TANPURA KINGS")
                    .font(.system(size: 26, weight: .bold))
                    .tracking(4)
                    .foregroundColor(.white)

                Spacer().frame(height: 4)

                Text("by Kingsman Software Solutions")
                    .font(.system(size: 12))
                    .foregroundColor(Color(white: 0.67))

                Spacer()

                // Progress bar section
                VStack(alignment: .leading, spacing: 8) {
                    // Stage label
                    Text(stageLabel)
                        .font(.system(size: 12))
                        .foregroundColor(Color(red: 1.0, green: 0.65, blue: 0))
                        .animation(.easeInOut(duration: 0.3), value: stageLabel)

                    // Progress track
                    GeometryReader { geo in
                        ZStack(alignment: .leading) {
                            // Track
                            RoundedRectangle(cornerRadius: 3)
                                .fill(Color.white.opacity(0.1))
                                .frame(height: 6)

                            // Fill
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

                    // Percentage
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
            await runSplashSequence()
        }
    }

    private func runSplashSequence() async {
        // Step through each loading stage
        for (target, label) in loadingStages.dropLast() {
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
                InterstitialAdManager.shared.showIfReady(onDismissed: onFinished, ignoreCooldown: true)
            } else {
                onFinished()
            }
        }
    }
}
