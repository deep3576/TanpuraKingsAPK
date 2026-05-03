import SwiftUI

struct TunerView: View {
    @ObservedObject private var tuner = TunerManager.shared
    @Environment(\.horizontalSizeClass) private var hSizeClass
    private var isIPad: Bool { hSizeClass == .regular }

    var body: some View {
        VStack(spacing: isIPad ? 20 : 12) {
            Text("Tuner")
                .foregroundColor(.white)
                .font(.system(size: 22, weight: .bold))

            Text("Sing or play a sustained note. Tanpura is paused while you tune.")
                .foregroundColor(Color(white: 0.85))
                .font(.system(size: 13))
                .multilineTextAlignment(.center)
                .padding(.horizontal, 16)

            ChromaticWheelDial(
                noteIndex: tuner.detected ? noteIndex(tuner.noteName) : -1,
                cents: tuner.cents,
                detected: tuner.detected,
                inTune: tuner.detected && abs(tuner.cents) < 5
            )
            .frame(
                width:  isIPad ? 340 : 260,
                height: isIPad ? 340 : 260
            )

            VStack(spacing: 4) {
                Text(tuner.detected ? tuner.noteName : "—")
                    .foregroundColor(.white)
                    .font(.system(size: isIPad ? 56 : 40, weight: .heavy, design: .rounded))
                    .monospacedDigit()
                Text(tuner.detected
                     ? String(format: "%+.0f¢", tuner.cents)
                     : "listening…")
                    .foregroundColor(centsColor)
                    .font(.system(size: isIPad ? 22 : 16, weight: .semibold, design: .rounded))
                Text(tuner.detected
                     ? String(format: "%.1f Hz", tuner.frequency)
                     : " ")
                    .foregroundColor(Color(white: 0.7))
                    .font(.system(size: 13))
                    .monospacedDigit()
            }

            PitchHistoryGraph(history: tuner.centsHistory)
                .frame(height: isIPad ? 100 : 72)
                .padding(.horizontal, 16)

            // Input level bar
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
        if c < 5  { return .green }
        if c < 15 { return .yellow }
        return .red
    }

    private func noteIndex(_ name: String) -> Int {
        let names = ["C","C#","D","D#","E","F","F#","G","G#","A","A#","B"]
        let base = String(name.prefix(while: { !$0.isNumber && $0 != "-" }))
        return names.firstIndex(of: base) ?? -1
    }
}

// MARK: - 360° Chromatic Wheel

/// Full-circle chromatic wheel with 12 note segments (C at 12 o'clock,
/// clockwise). An animated needle points to the currently detected note
/// + cents offset. Shortest-path angle wrapping prevents 330° back-spins
/// when crossing the C/B boundary.
private struct ChromaticWheelDial: View {
    let noteIndex: Int   // 0–11 (C–B), or -1 when nothing is detected
    let cents: Float
    let detected: Bool
    let inTune: Bool

    @State private var displayAngle: Double = 0   // degrees, unwrapped

    private let noteNames = ["C","C#","D","D#","E","F","F#","G","G#","A","A#","B"]

    var body: some View {
        GeometryReader { geo in
            let outerR = min(geo.size.width, geo.size.height) / 2 - 4
            let needleLen = outerR * 0.54
            let tailLen:  CGFloat = 14

            ZStack {
                // Static wheel drawn with Canvas
                Canvas { ctx, sz in
                    drawWheel(ctx: ctx, size: sz, outerR: outerR)
                }

                // Needle — uses SwiftUI rotationEffect so the animation is
                // driven by the rendering system (no custom Animatable needed).
                let nc: Color = detected ? (inTune ? .green : .white) : Color(white: 0.30)
                NeedleShape(tipLength: needleLen, tailLength: tailLen)
                    .stroke(nc, style: StrokeStyle(lineWidth: 3.2, lineCap: .round))
                    .rotationEffect(.degrees(displayAngle))

                // Center pivot
                Circle()
                    .fill(Color(red: 1.0, green: 165/255, blue: 0))
                    .frame(width: 14, height: 14)
                    .overlay(Circle().stroke(Color.white.opacity(0.85), lineWidth: 1.5))
            }
        }
        .onChange(of: noteIndex) { _, _ in animateToTarget() }
        .onChange(of: cents)     { _, _ in animateToTarget() }
    }

    private func animateToTarget() {
        guard noteIndex >= 0, detected else { return }
        // Each note occupies 30°; ±50¢ deflects ±15° (half a segment).
        let base    = Double(noteIndex) * 30.0
        let cOff    = Double(cents) / 50.0 * 15.0
        let rawTarget = base + cOff

        // Shortest-path delta: never spin more than 180° in one jump.
        var delta = rawTarget - displayAngle.truncatingRemainder(dividingBy: 360)
        if delta >  180 { delta -= 360 }
        if delta < -180 { delta += 360 }

        withAnimation(.easeOut(duration: 0.15)) {
            displayAngle += delta
        }
    }

    private func drawWheel(ctx: GraphicsContext, size: CGSize, outerR: CGFloat) {
        let cx = size.width  / 2
        let cy = size.height / 2
        let arcR: CGFloat = outerR - 10
        let arcW: CGFloat = 14

        // Background disc
        let bg = Path(ellipseIn: CGRect(x: cx-outerR, y: cy-outerR,
                                        width: outerR*2, height: outerR*2))
        ctx.fill(bg, with: .color(.black.opacity(0.55)))
        ctx.stroke(bg, with: .color(.white.opacity(0.2)), lineWidth: 2)

        for i in 0..<12 {
            let isActive = (i == noteIndex)
            let midDeg   = Double(i) * 30.0
            let startDeg = midDeg - 15.0
            let endDeg   = midDeg + 15.0

            // Convert to canvas angles (0° = 3-o'clock in Core Graphics)
            let startRad = (startDeg - 90.0) * .pi / 180.0
            let endRad   = (endDeg   - 90.0) * .pi / 180.0
            let midRad   = (midDeg   - 90.0) * .pi / 180.0

            // Coloured segment arc
            var arc = Path()
            arc.addArc(center: CGPoint(x: cx, y: cy), radius: arcR,
                       startAngle: .radians(startRad), endAngle: .radians(endRad),
                       clockwise: false)
            let segColor: Color = isActive
                ? (inTune ? .green : Color(red: 1.0, green: 0.6, blue: 0.0))
                : .white.opacity(0.10)
            ctx.stroke(arc, with: .color(segColor),
                       style: StrokeStyle(lineWidth: arcW, lineCap: .butt))

            // Divider tick between segments
            let divRad   = (endDeg - 90.0) * .pi / 180.0
            let tInner   = arcR - arcW * 0.5 - 2
            let tOuter   = arcR + arcW * 0.5 + 2
            var div = Path()
            div.move(to:    CGPoint(x: cx + cos(divRad)*tInner, y: cy + sin(divRad)*tInner))
            div.addLine(to: CGPoint(x: cx + cos(divRad)*tOuter, y: cy + sin(divRad)*tOuter))
            ctx.stroke(div, with: .color(.black.opacity(0.5)),
                       style: StrokeStyle(lineWidth: 2, lineCap: .butt))

            // Small tick mark at each note position (inner area)
            let tickOuter = arcR - arcW * 0.5 - 4
            let tickInner = tickOuter - (isActive ? 14 : 9)
            var tick = Path()
            tick.move(to:    CGPoint(x: cx + cos(midRad)*tickInner, y: cy + sin(midRad)*tickInner))
            tick.addLine(to: CGPoint(x: cx + cos(midRad)*tickOuter, y: cy + sin(midRad)*tickOuter))
            ctx.stroke(tick, with: .color(.white.opacity(isActive ? 1.0 : 0.50)),
                       style: StrokeStyle(lineWidth: isActive ? 2.5 : 1.5, lineCap: .round))

            // Note label inside the wheel
            let labelR = outerR * 0.64
            let pos    = CGPoint(x: cx + cos(midRad)*labelR, y: cy + sin(midRad)*labelR)
            // Natural notes (no #) shown bigger
            let isNatural = !noteNames[i].contains("#")
            let label = Text(noteNames[i])
                .font(.system(size: isNatural ? 14 : 11,
                              weight: isActive ? .bold : .regular,
                              design: .rounded))
                .foregroundColor(isActive ? .white : .white.opacity(0.50))
            ctx.draw(label, at: pos, anchor: .center)
        }

        // Faint inner circle to define the hub area
        let hubR = outerR * 0.32
        let hub  = Path(ellipseIn: CGRect(x: cx-hubR, y: cy-hubR, width: hubR*2, height: hubR*2))
        ctx.stroke(hub, with: .color(.white.opacity(0.12)), lineWidth: 1)
    }
}

// Draws a vertical needle centred at the view's midpoint: tip `tipLength`
// above centre, tail `tailLength` below. Rotate with .rotationEffect.
private struct NeedleShape: Shape {
    let tipLength:  CGFloat
    let tailLength: CGFloat

    func path(in rect: CGRect) -> Path {
        let cx = rect.midX
        let cy = rect.midY
        var p  = Path()
        p.move(to:    CGPoint(x: cx, y: cy + tailLength))   // tail (down)
        p.addLine(to: CGPoint(x: cx, y: cy - tipLength))    // tip  (up = 12 o'clock)
        return p
    }
}

// MARK: - Pitch History Graph

/// Scrolling line chart of cents deviation over the last ~150 frames (~6 s).
/// Green band = ±5¢, yellow band = ±15¢. Float.nan breaks the line at
/// silence gaps so the graph never connects voiced segments through silence.
private struct PitchHistoryGraph: View {
    let history: [Float]

    var body: some View {
        Canvas { ctx, size in
            let w = size.width
            let h = size.height
            let midY = h / 2
            let range: CGFloat = 50         // ±50¢ maps to ±h/2

            // Background
            let bg = Path(CGRect(x: 0, y: 0, width: w, height: h))
            ctx.fill(bg, with: .color(.black.opacity(0.35)))

            // ±15¢ yellow band
            let y15top = midY - h / 2 * (15 / range)
            let y15bot = midY + h / 2 * (15 / range)
            let band15 = Path(CGRect(x: 0, y: y15top, width: w, height: y15bot - y15top))
            ctx.fill(band15, with: .color(.yellow.opacity(0.12)))

            // ±5¢ green band
            let y5top  = midY - h / 2 * (5 / range)
            let y5bot  = midY + h / 2 * (5 / range)
            let band5  = Path(CGRect(x: 0, y: y5top, width: w, height: y5bot - y5top))
            ctx.fill(band5, with: .color(.green.opacity(0.18)))

            // Center line (0¢)
            var center = Path()
            center.move(to: CGPoint(x: 0, y: midY))
            center.addLine(to: CGPoint(x: w, y: midY))
            ctx.stroke(center, with: .color(.white.opacity(0.25)), lineWidth: 1)

            // Pitch line — break on NaN
            guard !history.isEmpty else { return }
            let count   = history.count
            let xStep   = w / CGFloat(max(count - 1, 1))
            var linePath = Path()
            var penDown  = false

            for (i, val) in history.enumerated() {
                let x = CGFloat(i) * xStep
                if val.isNaN {
                    penDown = false
                    continue
                }
                let clampedCents = max(-range, min(range, CGFloat(val)))
                let y = midY - (clampedCents / range) * (h / 2)
                if penDown {
                    linePath.addLine(to: CGPoint(x: x, y: y))
                } else {
                    linePath.move(to: CGPoint(x: x, y: y))
                    penDown = true
                }
            }
            ctx.stroke(linePath, with: .color(.white.opacity(0.90)),
                       style: StrokeStyle(lineWidth: 2, lineCap: .round, lineJoin: .round))

            // Right-edge border
            var border = Path()
            border.addRect(CGRect(x: 0, y: 0, width: w, height: h))
            ctx.stroke(border, with: .color(.white.opacity(0.18)), lineWidth: 1)
        }
        .clipShape(RoundedRectangle(cornerRadius: 6))
    }
}

#Preview {
    TunerView()
}
