import SwiftUI

/// Dropdown that lets the user choose between Lower, Mid, and Higher octave.
/// The selection is stored in UserDefaults via @AppStorage so it survives
/// app restarts automatically — no manual save/load needed.
struct OctavePickerView: View {

    @Binding var selectedOctave: Int   // −1 = Lower, 0 = Mid, +1 = Higher

    private struct OctaveOption: Identifiable {
        let id: Int
        let label: String
        let icon: String
    }

    private let options: [OctaveOption] = [
        OctaveOption(id: -1, label: "Lower Octave",  icon: "arrow.down"),
        OctaveOption(id:  0, label: "Mid Octave",    icon: "equal"),
        OctaveOption(id:  1, label: "Higher Octave", icon: "arrow.up"),
    ]

    private var currentLabel: String {
        options.first(where: { $0.id == selectedOctave })?.label ?? "Mid Octave"
    }

    private let accent = Color(red: 1.0, green: 165/255, blue: 0)

    var body: some View {
        HStack {
            Text("Octave")
                .foregroundColor(.white)
                .font(.system(size: 15, weight: .semibold))

            Spacer()

            Menu {
                ForEach(options) { option in
                    Button {
                        selectedOctave = option.id
                    } label: {
                        Label(option.label, systemImage:
                              selectedOctave == option.id
                                  ? "checkmark"
                                  : option.icon)
                    }
                }
            } label: {
                HStack(spacing: 6) {
                    Text(currentLabel)
                        .font(.system(size: 14, weight: .medium))
                        .foregroundColor(.white)
                    Image(systemName: "chevron.up.chevron.down")
                        .font(.system(size: 11, weight: .semibold))
                        .foregroundColor(accent)
                }
                .padding(.horizontal, 14)
                .padding(.vertical, 8)
                .background(Color.black.opacity(0.45))
                .clipShape(Capsule())
                .overlay(
                    Capsule()
                        .stroke(accent.opacity(0.65), lineWidth: 1)
                )
            }
        }
        .padding(10)
        .background(Color.black.opacity(0.55))
        .clipShape(RoundedRectangle(cornerRadius: 8))
    }
}

#Preview {
    ZStack {
        Color.purple.ignoresSafeArea()
        OctavePickerView(selectedOctave: .constant(0))
            .padding()
    }
}
