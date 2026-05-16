import SwiftUI

/// Top-level tab container. Two tabs:
///   1. Drone – the tanpura keyboard + effects + metronome.
///   2. Tuner – analog-clock chromatic tuner.
///
/// Switching to the Tuner tab stops every active drone note and the
/// metronome, otherwise the mic would pick up our own audio. Switching
/// back to Drone stops the tuner so the audio session can flip from
/// `.playAndRecord` back to `.playback`.
struct ContentView: View {
    enum AppTab: Hashable { case drone, tuner }

    @State private var selectedTab: AppTab = .drone

    // Drone-tab state (kept here so it survives tab switches).
    @State private var activeNotes: Set<String> = []
    @State private var activeNoteVolumes: [String: Float] = [:]
    @State private var masterVolume: Float = 1.0
    @State private var reverb: Float = 0
    @State private var fineTune: Float = 0
    @State private var echoMix: Float = 0
    @State private var echoDelay: Float = 300
    @State private var subOctaveMix:      Float = 0
    @State private var warmth:            Float = 0
    @State private var compressionAmount: Float = 0

    @State private var eqLow: Float = 0
    @State private var eqMid: Float = 0
    @State private var eqHigh: Float = 0
    @State private var stereoWidth: Float = 0.5

    @State private var metronomeOn: Bool = false
    @State private var metronomeBPM: Float = 80
    @State private var metronomeVolume: Float = 0.7

    var body: some View {
        TabView(selection: $selectedTab) {
            DroneView(
                activeNotes: $activeNotes,
                activeNoteVolumes: $activeNoteVolumes,
                masterVolume: $masterVolume,
                reverb: $reverb,
                fineTune: $fineTune,
                echoMix: $echoMix,
                echoDelay: $echoDelay,
                subOctaveMix: $subOctaveMix,
                warmth: $warmth,
                compressionAmount: $compressionAmount,
                eqLow: $eqLow,
                eqMid: $eqMid,
                eqHigh: $eqHigh,
                stereoWidth: $stereoWidth,
                metronomeOn: $metronomeOn,
                metronomeBPM: $metronomeBPM,
                metronomeVolume: $metronomeVolume
            )
            .tabItem {
                Label("Drone", systemImage: "music.note")
            }
            .tag(AppTab.drone)

            TunerView()
                .tabItem {
                    Label("Tuner", systemImage: "tuningfork")
                }
                .tag(AppTab.tuner)
        }
        .onAppear {
            AudioManager.shared.initializeIfNeeded()
        }
        .onChange(of: selectedTab) { _, newValue in
            switch newValue {
            case .tuner:
                // Mic input would otherwise pick up our own drone +
                // metronome and confuse the pitch detector.
                metronomeOn = false
                AudioManager.shared.stopMetronome()
                AudioManager.shared.stopAllNotes()
                activeNotes.removeAll()
                activeNoteVolumes.removeAll()
                TunerManager.shared.start()
            case .drone:
                TunerManager.shared.stop()
            }
        }
    }
}

/// Drone keyboard + effects panel. Adapts to a two-column layout on iPad
/// (regular horizontal size class) and stays as a single scrolling column
/// on iPhone / iPad split-view narrow mode (compact).
struct DroneView: View {
    @Binding var activeNotes: Set<String>
    @Binding var activeNoteVolumes: [String: Float]
    @Binding var masterVolume: Float
    @Binding var reverb: Float
    @Binding var fineTune: Float
    @Binding var echoMix: Float
    @Binding var echoDelay: Float
    @Binding var subOctaveMix:      Float
    @Binding var warmth:            Float
    @Binding var compressionAmount: Float
    @Binding var eqLow: Float
    @Binding var eqMid: Float
    @Binding var eqHigh: Float
    @Binding var stereoWidth: Float
    @Binding var metronomeOn: Bool
    @Binding var metronomeBPM: Float
    @Binding var metronomeVolume: Float

    @Environment(\.horizontalSizeClass) private var hSizeClass

    private var isIPad: Bool { hSizeClass == .regular }

    /// Tracks when the first note began playing so the timer can count up.
    /// Set to nil when all notes stop (resets the clock).
    @State private var playbackStart: Date? = nil

    // MARK: - Body
    // Split across two computed properties so Swift's type-checker does not
    // time out on a single expression with 15+ chained .onChange modifiers.

    var body: some View {
        // Second half: EQ, stereo width, metronome.
        effectsLayerView
            .onChange(of: eqLow)           { _, _   in pushEQ() }
            .onChange(of: eqMid)           { _, _   in pushEQ() }
            .onChange(of: eqHigh)          { _, _   in pushEQ() }
            .onChange(of: stereoWidth)     { _, v   in AudioManager.shared.updateStereoWidth(v) }
            .onChange(of: metronomeOn)     { _, on  in
                if on { AudioManager.shared.startMetronome() }
                else  { AudioManager.shared.stopMetronome()  }
            }
            .onChange(of: metronomeBPM)    { _, bpm in AudioManager.shared.setMetronomeBPM(bpm) }
            .onChange(of: metronomeVolume) { _, v   in AudioManager.shared.setMetronomeVolume(v) }
    }

    // First half: layout + timer + core audio effects.
    @ViewBuilder
    private var effectsLayerView: some View {
        ZStack {
            appGradient.ignoresSafeArea()
            if isIPad {
                iPadLayout
            } else {
                phoneLayout
            }
        }
        .onChange(of: activeNotes) { _, notes in
            if notes.isEmpty {
                playbackStart = nil          // all stopped → reset
            } else if playbackStart == nil {
                playbackStart = Date()       // first note → start clock
            }
        }
        .onChange(of: masterVolume)      { _, v in AudioManager.shared.updateMasterVolume(v) }
        .onChange(of: reverb)            { _, _ in pushEffects() }
        .onChange(of: fineTune)          { _, _ in pushEffects() }
        .onChange(of: echoMix)           { _, _ in pushEffects() }
        .onChange(of: echoDelay)         { _, _ in pushEffects() }
        .onChange(of: subOctaveMix)      { _, v in AudioManager.shared.updateOctaveBlend(v) }
        .onChange(of: warmth)            { _, v in AudioManager.shared.updateWarmth(v) }
        .onChange(of: compressionAmount) { _, v in AudioManager.shared.updateCompressor(v) }
    }

    // MARK: - Shared gradient

    private var appGradient: LinearGradient {
        LinearGradient(
            colors: [
                Color.blue.opacity(0.6),
                Color(red: 0x80/255, green: 0, blue: 0x80/255).opacity(0.8)
            ],
            startPoint: .top,
            endPoint: .bottom
        )
    }

    // MARK: - Phone layout (single scrolling column)

    private var phoneLayout: some View {
        ScrollView {
            VStack(spacing: 16) {
                Spacer().frame(height: 20)
                logoHeader(iconSize: 96, fontSize: 24)
                PlaybackTimerView(startDate: playbackStart)
                AudioOutputButton()
                PianoView(
                    activeNotes: $activeNotes,
                    activeNoteVolumes: $activeNoteVolumes,
                    masterVolume: masterVolume
                )
                if !activeNoteVolumes.isEmpty {
                    ActiveNotesVolumeView(
                        activeNoteVolumes: $activeNoteVolumes,
                        masterVolume: masterVolume
                    )
                }
                MetronomePanel(
                    isOn: $metronomeOn,
                    bpm: $metronomeBPM,
                    volume: $metronomeVolume
                )
                EffectsPanel(
                    reverb: $reverb,
                    fineTune: $fineTune,
                    echoMix: $echoMix,
                    echoDelay: $echoDelay,
                    subOctaveMix: $subOctaveMix,
                    warmth: $warmth,
                    compressionAmount: $compressionAmount,
                    eqLow: $eqLow,
                    eqMid: $eqMid,
                    eqHigh: $eqHigh,
                    stereoWidth: $stereoWidth
                )
                MasterVolumeView(masterVolume: $masterVolume)
                copyrightText
            }
            .padding(16)
        }
    }

    // MARK: - iPad layout (two columns side-by-side)
    //
    // Left column  (~45 %): branding + keyboard + per-note volumes
    // Right column (~55 %): metronome + effects + master volume

    private var iPadLayout: some View {
        HStack(alignment: .top, spacing: 0) {

            // Left — keyboard panel
            ScrollView {
                VStack(spacing: 16) {
                    Spacer().frame(height: 20)
                    logoHeader(iconSize: 120, fontSize: 30)
                    PlaybackTimerView(startDate: playbackStart)
                    AudioOutputButton()
                    PianoView(
                        activeNotes: $activeNotes,
                        activeNoteVolumes: $activeNoteVolumes,
                        masterVolume: masterVolume,
                        keyHeight: 260
                    )
                    if !activeNoteVolumes.isEmpty {
                        ActiveNotesVolumeView(
                            activeNoteVolumes: $activeNoteVolumes,
                            masterVolume: masterVolume
                        )
                    }
                    Spacer(minLength: 0)
                    copyrightText
                        .padding(.bottom, 8)
                }
                .padding(16)
            }
            .frame(maxWidth: .infinity)

            // Thin divider
            Rectangle()
                .fill(Color.white.opacity(0.2))
                .frame(width: 1)
                .padding(.vertical, 32)

            // Right — controls panel
            ScrollView {
                VStack(spacing: 16) {
                    Spacer().frame(height: 20)
                    MetronomePanel(
                        isOn: $metronomeOn,
                        bpm: $metronomeBPM,
                        volume: $metronomeVolume
                    )
                    EffectsPanel(
                        reverb: $reverb,
                        fineTune: $fineTune,
                        echoMix: $echoMix,
                        echoDelay: $echoDelay,
                        subOctaveMix: $subOctaveMix,
                        warmth: $warmth,
                        compressionAmount: $compressionAmount,
                        eqLow: $eqLow,
                        eqMid: $eqMid,
                        eqHigh: $eqHigh,
                        stereoWidth: $stereoWidth
                    )
                    MasterVolumeView(masterVolume: $masterVolume)
                }
                .padding(16)
            }
            .frame(maxWidth: .infinity)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    // MARK: - Shared sub-views

    @ViewBuilder
    private func logoHeader(iconSize: CGFloat, fontSize: CGFloat) -> some View {
        Image("Logo")
            .resizable()
            .aspectRatio(contentMode: .fit)
            .frame(width: iconSize, height: iconSize)
        Text("Tanpura Kings")
            .font(.system(size: fontSize, weight: .semibold))
            .foregroundColor(.white)
    }

    private var copyrightText: some View {
        Text("© kingsman software solutions")
            .font(.system(size: 14))
            .foregroundColor(.white)
            .frame(maxWidth: .infinity, alignment: .center)
    }

    // MARK: - Effect helpers

    private func pushEffects() {
        AudioManager.shared.updateEffects(
            reverbMix: reverb,
            fineTune: fineTune,
            echoMix: echoMix,
            echoDelay: echoDelay
        )
    }

    private func pushEQ() {
        AudioManager.shared.updateEQ(low: eqLow, mid: eqMid, high: eqHigh)
    }
}

// MARK: - Playback timer

/// Pill-shaped badge that counts up from the moment the first note starts
/// playing. Resets to 00:00 (greyed out) when all notes are stopped.
/// Uses TimelineView so the count advances every second without any manual
/// timer management — SwiftUI drives it automatically.
private struct PlaybackTimerView: View {
    let startDate: Date?

    var body: some View {
        if let start = startDate {
            TimelineView(.periodic(from: start, by: 1.0)) { ctx in
                let elapsed = max(0, Int(ctx.date.timeIntervalSince(start)))
                badge(time: Self.format(elapsed), active: true)
            }
        } else {
            badge(time: "00:00", active: false)
        }
    }

    @ViewBuilder
    private func badge(time: String, active: Bool) -> some View {
        HStack(spacing: 8) {
            // Pulsing green dot when playing, dim grey when stopped
            Circle()
                .fill(active
                      ? Color(red: 0.13, green: 0.80, blue: 0.35)
                      : Color.white.opacity(0.25))
                .frame(width: 9, height: 9)

            Text(active ? "Playing" : "Stopped")
                .font(.system(size: 12, weight: .semibold))
                .foregroundColor(active ? .white : .white.opacity(0.35))

            Text(time)
                .font(.system(size: 20, weight: .bold).monospacedDigit())
                .foregroundColor(active ? .white : .white.opacity(0.35))
        }
        .padding(.horizontal, 18)
        .padding(.vertical, 9)
        .background(Color.black.opacity(0.42))
        .clipShape(Capsule())
    }

    private static func format(_ total: Int) -> String {
        let h = total / 3600
        let m = (total % 3600) / 60
        let s = total % 60
        return h > 0
            ? String(format: "%d:%02d:%02d", h, m, s)
            : String(format: "%02d:%02d", m, s)
    }
}

#Preview {
    ContentView()
}
