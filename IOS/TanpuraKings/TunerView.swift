import SwiftUI

/// Analog-clock-style chromatic tuner.
///
/// The dial sweeps from -50 cents (left) to +50 cents (right). The needle
/// points straight up (12-o-clock) when the user is exactly in tune with
/// the nearest equal-tempered note.
///
/// Big "C4 / -3¢" readout below the dial. A simple input level meter
/// helps the user confirm the mic is picking them up.
struct TunerView: View {
    @ObservedObject private var tuner = TunerManager.shared
    @Environment(\.horizontalSizeClass) private var hSizeClass

    private var isIPad: Bool { hSizeClass == .regular }

    var body: some View {
        VStack(spacing: isIPad ? 24 : 16) {
            Text("Tuner")
                .foregroundColor(.white)
                .font(.system(size: 22, weight: .bold))

            Text("Sing or play a sustained note. Tanpura is paused while you tune.")
                .foregroundColor(Color(white: 0.85))
                .font(.system(size: 13))
                .multilineTextAlignment(.center)
                .padding(.horizontal, 16)

            TunerDial(
                cents: tuner.cents,
                inTune: tuner.detected && abs(tuner.cents) < 5,
                detected: tuner.detected
            )
            .frame(
                width:  isIPad ? 380 : 280,
                height: isIPad ? 380 : 280
            )
            .padding(.top, 8)

            VStack(spacing: 4) {
                Text(tuner.detected ? tuner.noteName : "—")
                    .foregroundColor(.white)
                    .font(.system(size: isIPad ? 64 : 44, weight: .heavy, design: .rounded))
                    .monospacedDigit()
                Text(tuner.detected
                     ? String(format: "%+.0f cents", tuner.cents)
                     : "listening…")
                    .foregroundColor(centsColor)
                    .font(.system(size: isIPad ? 24 : 18, weight: .semibold, design: .rounded))
                Text(tuner.detected
                     ? String(format: "%.1f Hz", tuner.frequency)
                     : " ")
                    .foregroundColor(Color(white: 0.7))
                    .font(.system(size: 13))
                    .monospacedDigit()
            }

            // Input level bar.
            GeometryReader { geo in
                ZStack(alignment: .leading) {
                    RoundedRectangle(cornerRadius: 4)
                        .fill(Color.white.opacity(0.15))
                    RoundedRectangle(cornerRadius: 4)
                        .fill(Color(red: 1.0, green: 165/255, blue: 0))
                        .frame(width: geo.size.width * CGFloat(tuner.inputLevel))
                }
            }
            .frame(height: 6)
            .padding(.horizontal, 24)

            Spacer(minLength: 0)
        }
        .padding(16)
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
    }

    private var centsColor: Color {
        guard tuner.detected else { return Color(white: 0.7) }
        let c = abs(tuner.cents)
        if c < 5  { return Color.green }
        if c < 15 { return Color.yellow }
        return Color.red
    }
}

/// Pure SwiftUI Canvas drawing of the analog dial. The needle angle maps
/// linearly from -50¢ (-60°) to +50¢ (+60°), so the user gets ~1.2° of
/// needle deflection per cent of pitch error — easy to read at a glance.
private struct TunerDial: View {
    let cents: Float
    let inTune: Bool
    let detected: Bool

    /// Maximum needle deflection from 12-o-clock, in radians.
    private let maxAngle: Double = .pi / 3 // ±60°

    var body: some View {
        Canvas { context, size in
            let cx = size.width / 2
            let cy = size.height / 2 + size.height * 0.12 // pivot below center
            let radius = min(size.width, size.height) / 2 - 8

            // Backplate.
            let plate = Path(ellipseIn: CGRect(
                x: cx - radius, y: cy - radius,
                width: radius * 2, height: radius * 2
            ))
            context.fill(plate, with: .color(Color.black.opacity(0.55)))
            context.stroke(plate, with: .color(Color.white.opacity(0.25)), lineWidth: 2)

            // Color bands. -50..-15 red, -15..-5 yellow, -5..+5 green,
            // +5..+15 yellow, +15..+50 red.
            drawArcBand(in: context, cx: cx, cy: cy, radius: radius - 6,
                        fromCents: -50, toCents: -15, color: .red)
            drawArcBand(in: context, cx: cx, cy: cy, radius: radius - 6,
                        fromCents: -15, toCents: -5, color: .yellow)
            drawArcBand(in: context, cx: cx, cy: cy, radius: radius - 6,
                        fromCents: -5,  toCents:  5, color: .green)
            drawArcBand(in: context, cx: cx, cy: cy, radius: radius - 6,
                        fromCents: 5,   toCents: 15, color: .yellow)
            drawArcBand(in: context, cx: cx, cy: cy, radius: radius - 6,
                        fromCents: 15,  toCents: 50, color: .red)

            // Tick marks every 10¢, longer every 25¢, plus center.
            for c in stride(from: -50, through: 50, by: 5) {
                let isMajor = (c % 25 == 0)
                let isMid = (c % 10 == 0)
                let inner = radius - (isMajor ? 26 : (isMid ? 20 : 14))
                let outer = radius - 6
                let angle = angleForCents(Float(c))
                let p1 = CGPoint(
                    x: cx + cos(angle - .pi/2) * inner,
                    y: cy + sin(angle - .pi/2) * inner
                )
                let p2 = CGPoint(
                    x: cx + cos(angle - .pi/2) * outer,
                    y: cy + sin(angle - .pi/2) * outer
                )
                var tick = Path()
                tick.move(to: p1)
                tick.addLine(to: p2)
                context.stroke(
                    tick,
                    with: .color(.white.opacity(isMajor ? 0.95 : (isMid ? 0.7 : 0.45))),
                    lineWidth: isMajor ? 2.2 : (isMid ? 1.6 : 1.0)
                )
            }

            // Numeric labels at -50, -25, 0, +25, +50.
            for c in [-50, -25, 0, 25, 50] {
                let labelRadius = radius - 42
                let angle = angleForCents(Float(c))
                let pos = CGPoint(
                    x: cx + cos(angle - .pi/2) * labelRadius,
                    y: cy + sin(angle - .pi/2) * labelRadius
                )
                let text = Text(c == 0 ? "0" : "\(c > 0 ? "+" : "")\(c)")
                    .font(.system(size: 11, weight: .semibold, design: .rounded))
                    .foregroundColor(.white)
                context.draw(text, at: pos, anchor: .center)
            }

            // Needle.
            let clampedCents = max(-50, min(50, cents))
            let needleAngle = angleForCents(clampedCents)
            let needleColor: Color = detected
                ? (inTune ? .green : .white)
                : Color(white: 0.55)
            let tip = CGPoint(
                x: cx + cos(needleAngle - .pi/2) * (radius - 14),
                y: cy + sin(needleAngle - .pi/2) * (radius - 14)
            )
            let tail = CGPoint(
                x: cx - cos(needleAngle - .pi/2) * 18,
                y: cy - sin(needleAngle - .pi/2) * 18
            )
            var needle = Path()
            needle.move(to: tail)
            needle.addLine(to: tip)
            context.stroke(needle, with: .color(needleColor), style: StrokeStyle(
                lineWidth: 3.5, lineCap: .round
            ))

            // Center pivot.
            let pivotRadius: CGFloat = 9
            let pivot = Path(ellipseIn: CGRect(
                x: cx - pivotRadius, y: cy - pivotRadius,
                width: pivotRadius * 2, height: pivotRadius * 2
            ))
            context.fill(pivot, with: .color(Color(red: 1.0, green: 165/255, blue: 0)))
            context.stroke(pivot, with: .color(.white.opacity(0.85)), lineWidth: 1.5)
        }
        .animation(.easeOut(duration: 0.12), value: cents)
        .animation(.easeOut(duration: 0.2),  value: detected)
    }

    private func angleForCents(_ c: Float) -> Double {
        let frac = Double(max(-50, min(50, c))) / 50.0
        return frac * maxAngle
    }

    private func drawArcBand(
        in context: GraphicsContext,
        cx: CGFloat, cy: CGFloat, radius: CGFloat,
        fromCents: Float, toCents: Float,
        color: Color
    ) {
        let start = angleForCents(fromCents) - .pi/2
        let end   = angleForCents(toCents)   - .pi/2
        var p = Path()
        p.addArc(
            center: CGPoint(x: cx, y: cy),
            radius: radius,
            startAngle: .radians(start),
            endAngle: .radians(end),
            clockwise: false
        )
        context.stroke(
            p,
            with: .color(color.opacity(0.55)),
            style: StrokeStyle(lineWidth: 10, lineCap: .butt)
        )
    }
}

#Preview {
    TunerView()
}
