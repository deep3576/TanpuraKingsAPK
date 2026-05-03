import SwiftUI

struct PianoView: View {
    @Binding var activeNotes: Set<String>
    @Binding var activeNoteVolumes: [String: Float]
    let masterVolume: Float
    var keyHeight: CGFloat = 200

    private let blackKeyPositions: [String: CGFloat] = [
        "C#": 0.75, "D#": 1.75, "F#": 3.75, "G#": 4.75, "A#": 5.75
    ]

    var body: some View {
        GeometryReader { geo in
            let whiteKeys = octaveKeys.filter { !$0.isSharp }
            let whiteKeyWidth = geo.size.width / CGFloat(whiteKeys.count)

            ZStack(alignment: .topLeading) {
                HStack(spacing: 0) {
                    ForEach(whiteKeys, id: \.name) { key in
                        whiteKeyView(key: key, width: whiteKeyWidth, height: geo.size.height)
                    }
                }

                ForEach(octaveKeys.filter { $0.isSharp }, id: \.name) { key in
                    let pos = blackKeyPositions[key.name] ?? 0
                    let bkWidth = whiteKeyWidth * 0.6
                    let offsetX = whiteKeyWidth * pos - bkWidth / 2
                    blackKeyView(key: key, width: bkWidth, height: keyHeight * 0.6)
                        .offset(x: offsetX)
                }
            }
            .frame(width: geo.size.width, height: geo.size.height)
            .background(Color(white: 0.83))
        }
        .frame(height: keyHeight)
    }

    @ViewBuilder
    private func whiteKeyView(key: PianoKey, width: CGFloat, height: CGFloat) -> some View {
        let isActive = activeNotes.contains(key.name)
        ZStack {
            Rectangle()
                .fill(isActive ? Color.yellow : Color.white)
            Text(key.name)
                .foregroundColor(.black)
        }
        .frame(width: width, height: height)
        .overlay(Rectangle().stroke(Color.black, lineWidth: 1))
        .contentShape(Rectangle())
        .onTapGesture {
            toggle(key: key.name)
        }
    }

    @ViewBuilder
    private func blackKeyView(key: PianoKey, width: CGFloat, height: CGFloat) -> some View {
        let isActive = activeNotes.contains(key.name)
        ZStack {
            Rectangle()
                .fill(isActive ? Color.yellow : Color.black)
            Text(key.name)
                .foregroundColor(.white)
                .font(.system(size: 10))
        }
        .frame(width: width, height: height)
        .overlay(Rectangle().stroke(Color.black, lineWidth: 1))
        .contentShape(Rectangle())
        .onTapGesture {
            toggle(key: key.name)
        }
    }

    private func toggle(key: String) {
        if activeNotes.contains(key) {
            AudioManager.shared.stopNote(key)
            activeNotes.remove(key)
            activeNoteVolumes.removeValue(forKey: key)
        } else {
            if activeNotes.count >= MAX_ACTIVE_NOTES { return }
            activeNotes.insert(key)
            activeNoteVolumes[key] = 1.0
            AudioManager.shared.playNote(key, masterVolume: masterVolume, noteVolume: 1.0)
        }
    }
}
