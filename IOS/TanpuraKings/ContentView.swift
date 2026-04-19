import SwiftUI

struct ContentView: View {
    @State private var activeNotes: Set<String> = []
    @State private var activeNoteVolumes: [String: Float] = [:]
    @State private var masterVolume: Float = 1.0
    @State private var reverb: Float = 0
    @State private var fineTune: Float = 0
    @State private var echoMix: Float = 0
    @State private var echoDelay: Float = 300
    @State private var delayMix: Float = 0
    @State private var delayTime: Float = 500

    var body: some View {
        ScrollView {
            VStack(spacing: 16) {
                Spacer().frame(height: 20)

                Image("Logo")
                    .resizable()
                    .aspectRatio(contentMode: .fit)
                    .frame(width: 96, height: 96)

                Text("Tanpura Kings")
                    .font(.system(size: 24, weight: .semibold))
                    .foregroundColor(.white)

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

                EffectsPanel(
                    reverb: $reverb,
                    fineTune: $fineTune,
                    echoMix: $echoMix,
                    echoDelay: $echoDelay,
                    delayMix: $delayMix,
                    delayTime: $delayTime
                )

                MasterVolumeView(masterVolume: $masterVolume)

                Text("© kingsman software solutions")
                    .font(.system(size: 14))
                    .foregroundColor(.white)
                    .frame(maxWidth: .infinity, alignment: .center)
            }
            .padding(16)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(
            LinearGradient(
                colors: [
                    Color.blue.opacity(0.6),
                    Color(red: 0x80/255, green: 0, blue: 0x80/255).opacity(0.8)
                ],
                startPoint: .top,
                endPoint: .bottom
            )
            .ignoresSafeArea()
        )
        .onAppear {
            AudioManager.shared.initializeIfNeeded()
        }
        .onChange(of: masterVolume) { _, newValue in
            AudioManager.shared.updateMasterVolume(newValue)
        }
        .onChange(of: reverb) { _, _ in pushEffects() }
        .onChange(of: fineTune) { _, _ in pushEffects() }
        .onChange(of: echoMix) { _, _ in pushEffects() }
        .onChange(of: echoDelay) { _, _ in pushEffects() }
        .onChange(of: delayMix) { _, _ in pushEffects() }
        .onChange(of: delayTime) { _, _ in pushEffects() }
    }

    private func pushEffects() {
        AudioManager.shared.updateEffects(
            reverbMix: reverb,
            fineTune: fineTune,
            echoMix: echoMix,
            echoDelay: echoDelay,
            delayMix: delayMix,
            delayTime: delayTime
        )
    }
}

#Preview {
    ContentView()
}
