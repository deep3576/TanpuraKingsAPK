import Foundation
import SwiftUI

struct EffectsPanel: View {
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

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text("Effects")
                .foregroundColor(.white)
                .font(.system(size: 18, weight: .bold))

            sectionHeader("Fine Tune")
            SliderWithLabel(
                label: "Pitch",
                value: $fineTune,
                range: -100...100,
                color: Color(red: 1.0, green: 215/255, blue: 0),
                formatter: { "\(Int($0)) cents" }
            )

            sectionHeader("Equalizer")
            SliderWithLabel(
                label: "Low",
                value: $eqLow,
                range: -12...12,
                color: Color(red: 0.4, green: 0.85, blue: 0.4),
                formatter: { String(format: "%+.1f dB", $0) }
            )
            SliderWithLabel(
                label: "Mid",
                value: $eqMid,
                range: -12...12,
                color: Color(red: 0.4, green: 0.85, blue: 0.4),
                formatter: { String(format: "%+.1f dB", $0) }
            )
            SliderWithLabel(
                label: "High",
                value: $eqHigh,
                range: -12...12,
                color: Color(red: 0.4, green: 0.85, blue: 0.4),
                formatter: { String(format: "%+.1f dB", $0) }
            )

            sectionHeader("Stereo Width")
            SliderWithLabel(
                label: "Width",
                value: $stereoWidth,
                range: 0...1,
                color: Color(red: 0.5, green: 0.7, blue: 1.0),
                formatter: { String(format: "%.0f%%", $0 * 100) }
            )

            sectionHeader("Reverb")
            SliderWithLabel(
                label: "Mix",
                value: $reverb,
                range: 0...100,
                color: .purple
            )

            sectionHeader("Echo")
            SliderWithLabel(
                label: "Mix",
                value: $echoMix,
                range: 0...1,
                color: .cyan
            )

            sectionHeader("Octave Blend")
            SliderWithLabel(
                label: "Sub Octave",
                value: $subOctaveMix,
                range: 0...1,
                color: Color(red: 0.6, green: 0.4, blue: 1.0),
                formatter: { String(format: "%.0f%%", $0 * 100) }
            )

            sectionHeader("Warmth")
            SliderWithLabel(
                label: "Saturation",
                value: $warmth,
                range: 0...1,
                color: Color(red: 1.0, green: 0.55, blue: 0.1),
                formatter: { String(format: "%.0f%%", $0 * 100) }
            )

            sectionHeader("Compressor")
            SliderWithLabel(
                label: "Amount",
                value: $compressionAmount,
                range: 0...1,
                color: Color(red: 0.9, green: 0.2, blue: 0.3),
                formatter: { String(format: "%.0f%%", $0 * 100) }
            )
        }
        .padding(8)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color.black.opacity(0.67))
        .clipShape(RoundedRectangle(cornerRadius: 8))
    }

    @ViewBuilder
    private func sectionHeader(_ title: String) -> some View {
        Text(title)
            .foregroundColor(Color(red: 1.0, green: 165/255, blue: 0))
            .font(.system(size: 13, weight: .semibold))
            .padding(.top, 10)
            .padding(.bottom, 2)
    }
}

struct SliderWithLabel: View {
    let label: String
    @Binding var value: Float
    let range: ClosedRange<Float>
    let color: Color
    var formatter: (Float) -> String = { String(format: "%.2f", $0) }

    var body: some View {
        VStack(spacing: 2) {
            HStack {
                Text(label).foregroundColor(.white)
                Spacer()
                Text(formatter(value))
                    .foregroundColor(Color(white: 0.8))
                    .font(.system(size: 13))
            }
            TappableSlider(value: $value, range: range, color: color)
        }
        .padding(.vertical, 2)
    }
}

/// Slider that responds to taps anywhere on the track, not just the thumb.
struct TappableSlider: View {
    @Binding var value: Float
    let range: ClosedRange<Float>
    var color: Color = .white

    private let trackH: CGFloat = 4
    private let thumbD: CGFloat = 22

    private func frac(_ w: CGFloat) -> CGFloat {
        let usable = w - thumbD
        guard usable > 0 else { return 0 }
        return CGFloat((value - range.lowerBound) / (range.upperBound - range.lowerBound))
    }

    private func update(_ x: CGFloat, width w: CGFloat) {
        let usable = w - thumbD
        guard usable > 0 else { return }
        let raw = Float((x - thumbD / 2) / usable)
        value = range.lowerBound + min(max(raw, 0), 1) * (range.upperBound - range.lowerBound)
    }

    var body: some View {
        GeometryReader { geo in
            let w = geo.size.width
            let f = frac(w)
            let usable = w - thumbD
            ZStack(alignment: .leading) {
                RoundedRectangle(cornerRadius: 2)
                    .fill(Color.white.opacity(0.25))
                    .frame(height: trackH)
                    .padding(.horizontal, thumbD / 2)
                RoundedRectangle(cornerRadius: 2)
                    .fill(color)
                    .frame(width: thumbD / 2 + usable * f, height: trackH)
                    .padding(.leading, thumbD / 2)
                Circle()
                    .fill(color)
                    .frame(width: thumbD, height: thumbD)
                    .shadow(color: .black.opacity(0.3), radius: 2)
                    .offset(x: usable * f)
            }
            .contentShape(Rectangle())
            .gesture(DragGesture(minimumDistance: 0).onChanged { drag in
                update(drag.location.x, width: w)
            })
        }
        .frame(height: thumbD)
    }
}
