import SwiftUI

struct MasterVolumeView: View {
    @Binding var masterVolume: Float

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Master Volume")
                .foregroundColor(.white)
                .font(.system(size: 18))
            Slider(value: $masterVolume, in: 0...1)
                .tint(.blue)
        }
        .padding(8)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color.black.opacity(0.67))
        .clipShape(RoundedRectangle(cornerRadius: 8))
    }
}
