import SwiftUI

struct ActiveNotesVolumeView: View {
    @Binding var activeNoteVolumes: [String: Float]
    let masterVolume: Float

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Active Notes Volume")
                .foregroundColor(.white)
                .font(.system(size: 18))

            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(Array(activeNoteVolumes.keys.sorted()), id: \.self) { note in
                        VStack {
                            Text(note).foregroundColor(.white)
                            TappableSlider(
                                value: Binding(
                                    get: { activeNoteVolumes[note] ?? 1.0 },
                                    set: { v in
                                        activeNoteVolumes[note] = v
                                        AudioManager.shared.updateNoteVolume(note, noteVolume: v, masterVolume: masterVolume)
                                    }
                                ),
                                range: 0...1,
                                color: Color(red: 1.0, green: 165/255, blue: 0)
                            )
                            .frame(width: 150)
                        }
                        .padding(8)
                    }
                }
            }
        }
        .padding(8)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color.black.opacity(0.67))
        .clipShape(RoundedRectangle(cornerRadius: 8))
    }
}
