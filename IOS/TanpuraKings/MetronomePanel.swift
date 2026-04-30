import SwiftUI

struct MetronomePanel: View {
    @Binding var isOn: Bool
    @Binding var bpm: Float
    @Binding var volume: Float

    // Tap-tempo state. We keep the last few tap timestamps and average the
    // intervals between them to derive BPM. Resets if the user pauses for
    // more than 2 s between taps.
    @State private var tapTimes: [Date] = []

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text("Metronome")
                    .foregroundColor(.white)
                    .font(.system(size: 18, weight: .bold))
                Spacer()
                Toggle("", isOn: $isOn)
                    .labelsHidden()
                    .tint(Color(red: 1.0, green: 165/255, blue: 0))
            }

            HStack {
                Text("BPM")
                    .foregroundColor(.white)
                Spacer()
                Text("\(Int(bpm))")
                    .foregroundColor(Color(white: 0.85))
                    .font(.system(size: 13))
            }
            Slider(value: $bpm, in: 40...240)
                .tint(Color(red: 1.0, green: 165/255, blue: 0))

            HStack {
                Button(action: handleTap) {
                    Text("Tap Tempo")
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundColor(.white)
                        .padding(.horizontal, 16)
                        .padding(.vertical, 8)
                        .background(Color(red: 1.0, green: 165/255, blue: 0))
                        .clipShape(Capsule())
                }
                Spacer()
                Text(tapTimes.count >= 2 ? "tapping…" : "tap 4× for tempo")
                    .foregroundColor(Color(white: 0.7))
                    .font(.system(size: 12))
            }

            HStack {
                Text("Volume")
                    .foregroundColor(.white)
                Spacer()
                Text(String(format: "%.0f%%", volume * 100))
                    .foregroundColor(Color(white: 0.85))
                    .font(.system(size: 13))
            }
            Slider(value: $volume, in: 0...1)
                .tint(Color(red: 1.0, green: 165/255, blue: 0))
        }
        .padding(8)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color.black.opacity(0.67))
        .clipShape(RoundedRectangle(cornerRadius: 8))
    }

    private func handleTap() {
        let now = Date()
        // Reset if the previous tap is too old.
        if let last = tapTimes.last, now.timeIntervalSince(last) > 2.0 {
            tapTimes.removeAll()
        }
        tapTimes.append(now)
        if tapTimes.count > 5 {
            tapTimes.removeFirst(tapTimes.count - 5)
        }
        if tapTimes.count >= 2 {
            // Average the inter-tap intervals.
            var sum: TimeInterval = 0
            for i in 1..<tapTimes.count {
                sum += tapTimes[i].timeIntervalSince(tapTimes[i - 1])
            }
            let avgInterval = sum / Double(tapTimes.count - 1)
            if avgInterval > 0 {
                let newBpm = Float(60.0 / avgInterval).clamped(to: 40...240)
                bpm = newBpm
            }
        }
    }
}

private extension Float {
    func clamped(to range: ClosedRange<Float>) -> Float {
        return min(max(self, range.lowerBound), range.upperBound)
    }
}
