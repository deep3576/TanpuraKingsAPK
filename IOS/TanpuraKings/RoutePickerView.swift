import SwiftUI
import AVKit

struct RoutePickerView: UIViewRepresentable {
    var tintColor: UIColor
    var activeTintColor: UIColor

    func makeUIView(context: Context) -> AVRoutePickerView {
        let view = AVRoutePickerView()
        view.tintColor = tintColor
        view.activeTintColor = activeTintColor
        view.prioritizesVideoDevices = false
        view.backgroundColor = .clear
        return view
    }

    func updateUIView(_ uiView: AVRoutePickerView, context: Context) {
        uiView.tintColor = tintColor
        uiView.activeTintColor = activeTintColor
    }
}

struct AudioOutputButton: View {
    var body: some View {
        HStack(spacing: 8) {
            Image(systemName: "speaker.wave.2.fill")
                .foregroundColor(.white)
            Text("Audio Output")
                .foregroundColor(.white)
                .font(.system(size: 14))
            RoutePickerView(
                tintColor: .white,
                activeTintColor: UIColor(red: 1.0, green: 165/255, blue: 0, alpha: 1)
            )
            .frame(width: 30, height: 30)
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 6)
        .background(Color.black.opacity(0.5))
        .clipShape(Capsule())
    }
}
