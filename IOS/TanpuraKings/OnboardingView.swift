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
    @State private var dragOffset: CGFloat = 0
    @Environment(\.horizontalSizeClass) private var hSizeClass

    private var isLast:    Bool    { currentPage == onboardingPages.count - 1 }
    private var isFirst:   Bool    { currentPage == 0 }
    private var isIPad:    Bool    { hSizeClass == .regular }
    private var maxWidth:  CGFloat { isIPad ? 620 : .infinity }
    private var emojiFSize: CGFloat { isIPad ? 100 : 80 }
    private var titleFSize: CGFloat { isIPad ? 34 : 26 }
    private var bodyFSize:  CGFloat { isIPad ? 19 : 16 }

    var body: some View {
        GeometryReader { geo in
            ZStack {
                // Background gradient
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

                    // ── Skip button ───────────────────────────────────────
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
                    .frame(maxWidth: maxWidth)

                    // ── Page content ──────────────────────────────────────
                    // Uses manual offset + drag so it works identically on
                    // iPhone and iPad (TabView.page is unreliable on iPad).
                    ZStack {
                        ForEach(onboardingPages.indices, id: \.self) { idx in
                            if abs(idx - currentPage) <= 1 {   // only render neighbours
                                pageCard(onboardingPages[idx])
                                    .frame(maxWidth: maxWidth)
                                    .frame(height: geo.size.height * 0.65)
                                    .offset(x: CGFloat(idx - currentPage) * geo.size.width + dragOffset)
                            }
                        }
                    }
                    .frame(maxWidth: .infinity)
                    .frame(height: geo.size.height * 0.65)
                    .clipped()
                    .contentShape(Rectangle())
                    .gesture(
                        DragGesture()
                            .onChanged { value in
                                // Resist at first/last page
                                let raw = value.translation.width
                                if (isFirst && raw > 0) || (isLast && raw < 0) {
                                    dragOffset = raw / 4
                                } else {
                                    dragOffset = raw
                                }
                            }
                            .onEnded { value in
                                let threshold = geo.size.width * 0.25
                                withAnimation(.interactiveSpring(response: 0.35, dampingFraction: 0.85)) {
                                    if value.translation.width < -threshold, !isLast {
                                        currentPage += 1
                                    } else if value.translation.width > threshold, !isFirst {
                                        currentPage -= 1
                                    }
                                    dragOffset = 0
                                }
                            }
                    )

                    Spacer(minLength: 0)

                    // ── Dot indicators ────────────────────────────────────
                    HStack(spacing: 8) {
                        ForEach(onboardingPages.indices, id: \.self) { idx in
                            Circle()
                                .fill(idx == currentPage
                                      ? Color.white
                                      : Color.white.opacity(0.3))
                                .frame(width:  idx == currentPage ? 10 : 7,
                                       height: idx == currentPage ? 10 : 7)
                                .animation(.easeInOut(duration: 0.2), value: currentPage)
                        }
                    }
                    .padding(.bottom, 20)

                    // ── Next / Get Started button ─────────────────────────
                    Button(action: advance) {
                        Text(isLast ? "Get Started! 🎵" : "Next  →")
                            .font(.system(size: 18, weight: .semibold))
                            .foregroundColor(.black)
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 14)
                            .background(Color(red: 1.0, green: 165/255, blue: 0))
                            .clipShape(Capsule())
                    }
                    .frame(maxWidth: maxWidth)
                    .padding(.horizontal, 32)
                    .padding(.bottom, 44)
                }
                .frame(width: geo.size.width, height: geo.size.height)
            }
        }
        .ignoresSafeArea()
    }

    // MARK: - Page card

    @ViewBuilder
    private func pageCard(_ page: OnboardingPage) -> some View {
        VStack(spacing: 20) {
            Spacer()
            Text(page.emoji)
                .font(.system(size: emojiFSize))
            Text(page.title)
                .font(.system(size: titleFSize, weight: .bold))
                .foregroundColor(.white)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 32)
            Text(page.description)
                .font(.system(size: bodyFSize))
                .foregroundColor(.white.opacity(0.88))
                .multilineTextAlignment(.center)
                .lineSpacing(5)
                .padding(.horizontal, 32)
            Spacer()
        }
    }

    // MARK: - Navigation

    private func advance() {
        if isLast {
            onFinished()
        } else {
            withAnimation(.interactiveSpring(response: 0.35, dampingFraction: 0.85)) {
                currentPage += 1
            }
        }
    }
}
