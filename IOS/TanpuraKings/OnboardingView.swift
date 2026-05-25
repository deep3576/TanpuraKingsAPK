import SwiftUI

private struct OnboardingPage {
    let emoji: String
    let title: String
    let description: String
}

private let onboardingPages: [OnboardingPage] = [
    .init(emoji: "🎵", title: "Welcome to Tanpura Kings",
          description: "Your professional tanpura drone and chromatic tuner. Let's take a quick tour of everything you can do."),
    .init(emoji: "🎹", title: "Play the Drone",
          description: "Tap any key on the keyboard to start a continuous drone tone. Tap again to stop it. You can play multiple notes at once."),
    .init(emoji: "🎼", title: "Change Octave",
          description: "Use the octave selector to switch between Lower (−1), Mid (0), and Higher (+1) octaves to match your vocal or instrument range."),
    .init(emoji: "🔊", title: "Per-Note Volume",
          description: "When notes are playing, individual volume sliders appear so you can balance each drone note precisely."),
    .init(emoji: "🥁", title: "Metronome",
          description: "Enable the built-in metronome, set the BPM (40–240) with the slider or Tap Tempo button, and adjust its tick volume independently."),
    .init(emoji: "✨", title: "Effects & EQ",
          description: "Shape your sound with Reverb, Echo, EQ (Low / Mid / High), Stereo Width, Sub Octave blend, Warmth, and Compression."),
    .init(emoji: "🎚️", title: "Master Volume",
          description: "The master fader at the bottom controls the overall output level of all active drone notes together."),
    .init(emoji: "🎤", title: "Chromatic Tuner",
          description: "Tap the Tuner tab to switch to the chromatic tuner. It listens via your microphone and shows detected pitch on an analog dial."),
    .init(emoji: "🙏", title: "You're All Set!",
          description: "Enjoy Tanpura Kings. This tour won't appear again — explore every feature at your own pace."),
]

struct OnboardingView: View {
    let onFinished: () -> Void

    @State private var currentPage = 0
    @Environment(\.horizontalSizeClass) private var hSizeClass

    private var isLast: Bool { currentPage == onboardingPages.count - 1 }
    /// On iPad (regular) cap the card width so content doesn't stretch edge-to-edge.
    private var maxCardWidth: CGFloat { hSizeClass == .regular ? 600 : .infinity }

    var body: some View {
        ZStack {
            LinearGradient(
                colors: [
                    Color.blue.opacity(0.6),
                    Color(red: 0x80/255, green: 0, blue: 0x80/255).opacity(0.8)
                ],
                startPoint: .top,
                endPoint: .bottom
            )
            .ignoresSafeArea()

            VStack(spacing: 0) {
                // Skip button
                HStack {
                    Spacer()
                    if !isLast {
                        Button("Skip") { onFinished() }
                            .foregroundColor(.white.opacity(0.7))
                            .font(.system(size: 16))
                            .padding()
                    } else {
                        Color.clear.frame(height: 52)
                    }
                }
                .frame(maxWidth: maxCardWidth)

                // Swipeable pages — must be .infinity height so it fills the VStack.
                TabView(selection: $currentPage) {
                    ForEach(onboardingPages.indices, id: \.self) { idx in
                        pageCard(onboardingPages[idx])
                            .frame(maxWidth: maxCardWidth)
                            .tag(idx)
                    }
                }
                .tabViewStyle(.page(indexDisplayMode: .never))
                .frame(maxWidth: .infinity, maxHeight: .infinity) // ← fixes iPad collapse

                // Dot indicators
                HStack(spacing: 8) {
                    ForEach(onboardingPages.indices, id: \.self) { idx in
                        Circle()
                            .fill(idx == currentPage ? Color.white : Color.white.opacity(0.3))
                            .frame(
                                width:  idx == currentPage ? 10 : 7,
                                height: idx == currentPage ? 10 : 7
                            )
                            .animation(.easeInOut(duration: 0.2), value: currentPage)
                    }
                }
                .padding(.bottom, 20)

                // Next / Get Started button
                Button(action: advance) {
                    Text(isLast ? "Get Started! 🎵" : "Next  →")
                        .font(.system(size: 18, weight: .semibold))
                        .foregroundColor(.black)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 14)
                        .background(Color(red: 1.0, green: 165/255, blue: 0))
                        .clipShape(Capsule())
                }
                .frame(maxWidth: maxCardWidth)
                .padding(.horizontal, 32)
                .padding(.bottom, 44)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
    }

    @ViewBuilder
    private func pageCard(_ page: OnboardingPage) -> some View {
        VStack(spacing: 20) {
            Spacer()
            Text(page.emoji)
                .font(.system(size: hSizeClass == .regular ? 100 : 80))
            Text(page.title)
                .font(.system(size: hSizeClass == .regular ? 32 : 26, weight: .bold))
                .foregroundColor(.white)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 32)
            Text(page.description)
                .font(.system(size: hSizeClass == .regular ? 18 : 16))
                .foregroundColor(.white.opacity(0.88))
                .multilineTextAlignment(.center)
                .lineSpacing(5)
                .padding(.horizontal, 32)
            Spacer()
        }
    }

    private func advance() {
        if isLast {
            onFinished()
        } else {
            withAnimation { currentPage += 1 }
        }
    }
}
