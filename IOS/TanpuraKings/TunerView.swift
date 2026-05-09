import SwiftUI

// MARK: - Main Tuner View

struct TunerView: View {
    @ObservedObject private var tuner = TunerManager.shared
    @Environment(\.horizontalSizeClass) private var hSizeClass
    private var isIPad: Bool { hSizeClass == .regular }

    private var noteIdx: Int {
        let names = ["C","C#","D","D#","E","F","F#","G","G#","A","A#","B"]
        let base  = String(tuner.noteName.prefix(while: { !$0.isNumber && $0 != "-" }))
        return names.firstIndex(of: base) ?? -1
    }

    var body: some View {
        ZStack {
            Color(white: 0.07).ignoresSafeArea()

            VStack(spacing: 0) {
                // ── Header ──
                HStack {
                    Text("CHROMATIC TUNER")
                        .font(.system(size: 10, weight: .semibold, design: .rounded))
                        .tracking(3)
                        .foregroundColor(Color(white: 0.42))
                    Spacer()
                    Text("A₄ = 440 Hz")
                        .font(.system(size: 11, weight: .medium, design: .rounded))
                        .foregroundColor(TunerPalette.accent)
                }
                .padding(.horizontal, 20)
                .padding(.top, isIPad ? 20 : 14)
                .padding(.bottom, isIPad ? 10 : 6)

                // ── InsTuner-style note status card ──
                TuningStatusCard(
                    noteName:  tuner.noteName,
                    cents:     tuner.cents,
                    frequency: tuner.frequency,
                    detected:  tuner.detected
                )
                .frame(height: isIPad ? 160 : 130)
                .padding(.horizontal, 20)

                // ── 360° Chromatic Wheel ──
                let dialSize: CGFloat = isIPad
                    ? 360
                    : min(UIScreen.main.bounds.width - 40, 310)

                ChromaticWheelDial(
                    noteIndex: tuner.detected ? noteIdx : -1,
                    cents:     tuner.cents,
                    detected:  tuner.detected,
                    inTune:    tuner.detected && abs(tuner.cents) < 5
                )
                .frame(width: dialSize, height: dialSize)
                .padding(.top, isIPad ? 10 : 6)

                // ── Input level bar ──
                InputLevelBar(level: tuner.inputLevel)
                    .frame(height: 5)
                    .padding(.horizontal, 20)
                    .padding(.top, isIPad ? 10 : 6)

                // ── Pitch history graph ──
                PitchHistoryGraph(history: tuner.centsHistory)
                    .frame(height: isIPad ? 90 : 66)
                    .padding(.horizontal, 16)
                    .padding(.top, isIPad ? 8 : 4)

                // ── Status dot ──
                HStack(spacing: 5) {
                    Circle()
                        .fill(tuner.detected ? TunerPalette.green : Color(white: 0.28))
                        .frame(width: 6, height: 6)
                    Text(tuner.detected ? "Signal detected" : "Listening for signal…")
                        .font(.system(size: 11, weight: .medium, design: .rounded))
                        .foregroundColor(Color(white: 0.40))
                }
                .padding(.top, isIPad ? 8 : 5)
                .animation(.easeInOut(duration: 0.35), value: tuner.detected)

                Spacer(minLength: 0)
            }
        }
    }
}

// MARK: - Colour palette

private enum TunerPalette {
    static let green  = Color(red: 0.13, green: 0.80, blue: 0.35)
    static let yellow = Color(red: 1.00, green: 0.80, blue: 0.00)
    static let red    = Color(red: 0.95, green: 0.20, blue: 0.20)
    static let accent = Color(red: 1.00, green: 0.60, blue: 0.00)

    static func forCents(_ c: Float, detected: Bool) -> Color {
        guard detected else { return Color(white: 0.30) }
        let a = abs(c)
        if a < 5  { return green }
        if a < 15 { return yellow }
        return red
    }
}

// MARK: - Tuning Status Card  (InsTuner reference)
//
// Layout matches InsTuner:
//  • Full-green background when in tune ("Got it!")
//  • Dark background + red bar on LEFT edge when flat  ("Tune Up ↑")
//  • Dark background + red bar on RIGHT edge when sharp ("Tune Down ↓")
//  • ♭ label on left, # label on right
//  • Very large note name centred with accidental superscript + octave

private struct TuningStatusCard: View {
    let noteName:  String
    let cents:     Float
    let frequency: Float
    let detected:  Bool

    // Decompose e.g. "F#3" → natural "F", accidental "♯", octave "3"
    private var naturalNote: String {
        let base = String(noteName.prefix(while: { !$0.isNumber && $0 != "-" }))
        return base.replacingOccurrences(of: "#", with: "")
                   .replacingOccurrences(of: "b", with: "")
    }
    private var accidental: String? {
        let base = String(noteName.prefix(while: { !$0.isNumber && $0 != "-" }))
        if base.contains("#") { return "♯" }
        if base.contains("b") { return "♭" }
        return nil
    }
    private var octaveStr: String {
        let base = String(noteName.prefix(while: { !$0.isNumber && $0 != "-" }))
        return String(noteName.dropFirst(base.count))
    }

    // Tuning state
    private var isInTune:  Bool { detected && abs(cents) < 5 }
    private var isFlat:    Bool { detected && cents < -5 }
    private var isSharp:   Bool { detected && cents >  5 }

    // How much of the bar to fill (0…1), capped at 50¢ = full bar
    private var barFraction: CGFloat {
        CGFloat(min(abs(cents), 50) / 50)
    }

    private var statusText: String {
        guard detected else { return "Listening…" }
        if isInTune { return "Got it! ✓" }
        if isFlat   { return "Tune Up  ↑" }
        return "Tune Down  ↓"
    }

    var body: some View {
        GeometryReader { geo in
            let w = geo.size.width
            let h = geo.size.height

            ZStack {
                // ── Background ──
                RoundedRectangle(cornerRadius: 14)
                    .fill(isInTune
                          ? TunerPalette.green
                          : Color(white: 0.11))

                // ── Tuning direction bar ──
                if detected && !isInTune {
                    let barW = w * barFraction * 0.42   // max ~42% of card width

                    if isFlat {
                        // Flat → red bar on LEFT
                        HStack(spacing: 0) {
                            RoundedRectangle(cornerRadius: 14)
                                .fill(TunerPalette.red.opacity(0.85))
                                .frame(width: barW)
                                .clipShape(
                                    .rect(topLeadingRadius: 14, bottomLeadingRadius: 14,
                                          topTrailingRadius: 0, bottomTrailingRadius: 0)
                                )
                            Spacer(minLength: 0)
                        }
                    } else {
                        // Sharp → red bar on RIGHT
                        HStack(spacing: 0) {
                            Spacer(minLength: 0)
                            RoundedRectangle(cornerRadius: 14)
                                .fill(TunerPalette.red.opacity(0.85))
                                .frame(width: barW)
                                .clipShape(
                                    .rect(topLeadingRadius: 0, bottomLeadingRadius: 0,
                                          topTrailingRadius: 14, bottomTrailingRadius: 14)
                                )
                        }
                    }
                }

                // ── ♭  and  # side labels ──
                HStack {
                    Text("♭")
                        .font(.system(size: 20, weight: .bold, design: .rounded))
                        .foregroundColor(.white.opacity(isFlat ? 1.0 : 0.25))
                    Spacer()
                    Text("#")
                        .font(.system(size: 18, weight: .bold, design: .rounded))
                        .foregroundColor(.white.opacity(isSharp ? 1.0 : 0.25))
                }
                .padding(.horizontal, 14)

                // ── Note name + octave + status ──
                VStack(spacing: 2) {
                    // Big note name row
                    HStack(alignment: .top, spacing: 2) {
                        Text(detected ? naturalNote : "—")
                            .font(.system(size: h * 0.58, weight: .black, design: .rounded))
                            .foregroundColor(.white)
                            .minimumScaleFactor(0.4)
                            .lineLimit(1)

                        if detected {
                            VStack(alignment: .leading, spacing: 0) {
                                if let acc = accidental {
                                    Text(acc)
                                        .font(.system(size: h * 0.22, weight: .bold, design: .rounded))
                                        .foregroundColor(.white)
                                }
                                if !octaveStr.isEmpty {
                                    Text(octaveStr)
                                        .font(.system(size: h * 0.20, weight: .semibold, design: .rounded))
                                        .foregroundColor(.white.opacity(0.80))
                                }
                            }
                            .offset(y: h * 0.08)
                        }
                    }

                    // Status label
                    Text(statusText)
                        .font(.system(size: 13, weight: .semibold, design: .rounded))
                        .foregroundColor(.white.opacity(0.88))
                        .animation(.easeInOut(duration: 0.20), value: statusText)
                }

                // ── Cents badge (bottom-right corner) ──
                if detected {
                    VStack {
                        Spacer()
                        HStack {
                            Spacer()
                            Text(String(format: "%+.0f¢", cents))
                                .font(.system(size: 12, weight: .semibold, design: .rounded))
                                .foregroundColor(.white.opacity(0.65))
                                .monospacedDigit()
                                .padding(.trailing, 12)
                                .padding(.bottom, 8)
                        }
                    }
                }
            }
            .animation(.easeInOut(duration: 0.25), value: isInTune)
            .animation(.easeInOut(duration: 0.12), value: cents)
        }
    }
}

// MARK: - 360° Chromatic Wheel Dial

private struct ChromaticWheelDial: View {
    let noteIndex: Int
    let cents:     Float
    let detected:  Bool
    let inTune:    Bool

    @State private var displayAngle: Double = 0

    private let noteNames = ["C","C#","D","D#","E","F","F#","G","G#","A","A#","B"]

    var body: some View {
        GeometryReader { geo in
            let side    = min(geo.size.width, geo.size.height)
            let outerR  = side / 2 - 4
            let needleR = outerR * 0.50
            let tailLen: CGFloat = 13

            ZStack {
                Canvas { ctx, sz in
                    drawWheel(ctx: ctx, size: sz, outerR: outerR)
                }

                // Animated needle
                let nc: Color = detected
                    ? (inTune ? TunerPalette.green : .white)
                    : Color(white: 0.22)

                WheelNeedle(tipLength: needleR, tailLength: tailLen)
                    .stroke(nc, style: StrokeStyle(lineWidth: 3.0, lineCap: .round))
                    .rotationEffect(.degrees(displayAngle))

                // Pivot
                Circle()
                    .fill(TunerPalette.accent)
                    .frame(width: 13, height: 13)
                    .overlay(Circle().stroke(Color.white.opacity(0.75), lineWidth: 1.5))
            }
        }
        .onChange(of: noteIndex) { _, _ in animateToTarget() }
        .onChange(of: cents)     { _, _ in animateToTarget() }
    }

    private func animateToTarget() {
        guard noteIndex >= 0, detected else { return }
        let base      = Double(noteIndex) * 30.0
        let centsOff  = Double(cents) / 50.0 * 15.0
        let rawTarget = base + centsOff
        var delta = rawTarget - displayAngle.truncatingRemainder(dividingBy: 360)
        if delta >  180 { delta -= 360 }
        if delta < -180 { delta += 360 }
        withAnimation(.interactiveSpring(response: 0.18, dampingFraction: 0.65)) {
            displayAngle += delta
        }
    }

    private func drawWheel(ctx: GraphicsContext, size: CGSize, outerR: CGFloat) {
        let cx = size.width / 2;  let cy = size.height / 2

        // Background disc
        let bg = Path(ellipseIn: CGRect(x: cx-outerR, y: cy-outerR,
                                        width: outerR*2, height: outerR*2))
        ctx.fill(bg, with: .color(.black.opacity(0.65)))
        ctx.stroke(bg, with: .color(.white.opacity(0.10)), lineWidth: 1.5)

        let segArcR: CGFloat = outerR - 10
        let segArcW: CGFloat = 26
        let segInner = segArcR - segArcW / 2 - 2

        let centsArcR: CGFloat = segInner - 10
        let centsArcW: CGFloat = 10

        // ── 12 note segments ──
        for i in 0..<12 {
            let isActive   = (i == noteIndex && detected)
            let midDeg     = Double(i) * 30.0
            let startDeg   = midDeg - 15.0
            let endDeg     = midDeg + 15.0
            let isNatural  = !noteNames[i].contains("#")

            func cRad(_ d: Double) -> Double { (d - 90.0) * .pi / 180.0 }
            let midRad   = cRad(midDeg)

            // Segment colour
            let segColor: Color = isActive
                ? TunerPalette.forCents(cents, detected: true)
                : (isNatural ? Color.white.opacity(0.13) : Color.white.opacity(0.07))

            var arc = Path()
            arc.addArc(center: CGPoint(x: cx, y: cy), radius: segArcR,
                       startAngle: .radians(cRad(startDeg)),
                       endAngle:   .radians(cRad(endDeg)),
                       clockwise: false)
            ctx.stroke(arc, with: .color(segColor),
                       style: StrokeStyle(lineWidth: segArcW, lineCap: .butt))

            // Divider
            let divRad = cRad(endDeg)
            let dO = segArcR + segArcW / 2 + 2;  let dI = segArcR - segArcW / 2 - 2
            var div = Path()
            div.move(to:    CGPoint(x: cx + CGFloat(cos(divRad)) * dI, y: cy + CGFloat(sin(divRad)) * dI))
            div.addLine(to: CGPoint(x: cx + CGFloat(cos(divRad)) * dO, y: cy + CGFloat(sin(divRad)) * dO))
            ctx.stroke(div, with: .color(.black.opacity(0.75)),
                       style: StrokeStyle(lineWidth: 3, lineCap: .butt))

            // Centre tick
            let tO = segArcR - segArcW / 2 - 4
            let tI = tO - (isActive ? 14 : 8)
            var tick = Path()
            tick.move(to:    CGPoint(x: cx + CGFloat(cos(midRad)) * tI, y: cy + CGFloat(sin(midRad)) * tI))
            tick.addLine(to: CGPoint(x: cx + CGFloat(cos(midRad)) * tO, y: cy + CGFloat(sin(midRad)) * tO))
            ctx.stroke(tick, with: .color(.white.opacity(isActive ? 1.0 : 0.32)),
                       style: StrokeStyle(lineWidth: isActive ? 2.5 : 1.2, lineCap: .round))

            // Note label
            let labelR = outerR * 0.58
            let lpos = CGPoint(x: cx + CGFloat(cos(midRad)) * labelR,
                               y: cy + CGFloat(sin(midRad)) * labelR)
            let lbl = Text(noteNames[i])
                .font(.system(size: isNatural ? 15 : 11,
                              weight: isActive ? .black : .regular,
                              design: .rounded))
                .foregroundColor(isActive
                    ? TunerPalette.forCents(cents, detected: true)
                    : .white.opacity(isNatural ? 0.58 : 0.35))
            ctx.draw(lbl, at: lpos, anchor: .center)
        }

        // ── Inner ±50¢ colour zone ring ──
        if detected && noteIndex >= 0 {
            let midDeg = Double(noteIndex) * 30.0
            func cRad(_ d: Double) -> Double { (d - 90.0) * .pi / 180.0 }

            struct CZone { var fromC: Double; var toC: Double; var col: Color }
            let czones: [CZone] = [
                CZone(fromC: -50, toC: -15, col: TunerPalette.red.opacity(0.80)),
                CZone(fromC: -15, toC:  -5, col: TunerPalette.yellow.opacity(0.80)),
                CZone(fromC:  -5, toC:   5, col: TunerPalette.green.opacity(0.95)),
                CZone(fromC:   5, toC:  15, col: TunerPalette.yellow.opacity(0.80)),
                CZone(fromC:  15, toC:  50, col: TunerPalette.red.opacity(0.80)),
            ]
            for z in czones {
                let sRad = cRad(midDeg + z.fromC / 50.0 * 15.0)
                let eRad = cRad(midDeg + z.toC   / 50.0 * 15.0)
                var ca = Path()
                ca.addArc(center: CGPoint(x: cx, y: cy), radius: centsArcR,
                          startAngle: .radians(sRad), endAngle: .radians(eRad),
                          clockwise: false)
                ctx.stroke(ca, with: .color(z.col),
                           style: StrokeStyle(lineWidth: centsArcW, lineCap: .butt))
            }
            // Ticks at 0, ±25, ±50¢
            for c in [-50.0, -25.0, 0.0, 25.0, 50.0] {
                let isZ  = c == 0
                let tRad = cRad(midDeg + c / 50.0 * 15.0)
                let tO   = centsArcR + centsArcW / 2 + 2
                let tI   = centsArcR - centsArcW / 2 - (isZ ? 6 : 3)
                var tk = Path()
                tk.move(to:    CGPoint(x: cx + CGFloat(cos(tRad)) * tI, y: cy + CGFloat(sin(tRad)) * tI))
                tk.addLine(to: CGPoint(x: cx + CGFloat(cos(tRad)) * tO, y: cy + CGFloat(sin(tRad)) * tO))
                ctx.stroke(tk, with: .color(.white.opacity(isZ ? 0.85 : 0.48)),
                           style: StrokeStyle(lineWidth: isZ ? 2.2 : 1.2, lineCap: .round))
            }
        }

        // Hub
        let hubR = outerR * 0.22
        let hub  = Path(ellipseIn: CGRect(x: cx-hubR, y: cy-hubR, width: hubR*2, height: hubR*2))
        ctx.fill(hub, with: .color(Color(white: 0.07)))
        ctx.stroke(hub, with: .color(.white.opacity(0.14)), lineWidth: 1)
    }
}

// MARK: - Wheel Needle

private struct WheelNeedle: Shape {
    let tipLength:  CGFloat
    let tailLength: CGFloat
    func path(in rect: CGRect) -> Path {
        let cx = rect.midX; let cy = rect.midY
        var p = Path()
        p.move(to:    CGPoint(x: cx, y: cy + tailLength))
        p.addLine(to: CGPoint(x: cx, y: cy - tipLength))
        return p
    }
}

// MARK: - Input Level Bar

private struct InputLevelBar: View {
    let level: Float
    var body: some View {
        GeometryReader { geo in
            ZStack(alignment: .leading) {
                RoundedRectangle(cornerRadius: 3).fill(Color.white.opacity(0.09))
                RoundedRectangle(cornerRadius: 3)
                    .fill(LinearGradient(
                        colors: [TunerPalette.green, TunerPalette.yellow, TunerPalette.red],
                        startPoint: .leading, endPoint: .trailing))
                    .frame(width: geo.size.width * CGFloat(level))
                    .animation(.linear(duration: 0.05), value: level)
            }
        }
    }
}

// MARK: - Pitch History Graph

private struct PitchHistoryGraph: View {
    let history: [Float]
    var body: some View {
        Canvas { ctx, size in
            let w = size.width; let h = size.height
            let midY = h / 2; let range: CGFloat = 50

            ctx.fill(Path(CGRect(x: 0, y: 0, width: w, height: h)),
                     with: .color(.black.opacity(0.28)))

            let y15t = midY - h/2*(15/range); let y15b = midY + h/2*(15/range)
            ctx.fill(Path(CGRect(x: 0, y: y15t, width: w, height: y15b-y15t)),
                     with: .color(TunerPalette.yellow.opacity(0.07)))

            let y5t = midY - h/2*(5/range); let y5b = midY + h/2*(5/range)
            ctx.fill(Path(CGRect(x: 0, y: y5t, width: w, height: y5b-y5t)),
                     with: .color(TunerPalette.green.opacity(0.12)))

            var cl = Path()
            cl.move(to: CGPoint(x: 0, y: midY)); cl.addLine(to: CGPoint(x: w, y: midY))
            ctx.stroke(cl, with: .color(.white.opacity(0.18)), lineWidth: 1)

            guard !history.isEmpty else { return }
            let xStep = w / CGFloat(max(history.count - 1, 1))
            var lp = Path(); var pen = false
            for (i, val) in history.enumerated() {
                if val.isNaN { pen = false; continue }
                let x  = CGFloat(i) * xStep
                let cy = midY - (max(-range, min(range, CGFloat(val))) / range) * (h/2)
                if pen { lp.addLine(to: CGPoint(x: x, y: cy)) }
                else   { lp.move(to:    CGPoint(x: x, y: cy)); pen = true }
            }
            ctx.stroke(lp, with: .color(.white.opacity(0.88)),
                       style: StrokeStyle(lineWidth: 2, lineCap: .round, lineJoin: .round))

            var border = Path()
            border.addRect(CGRect(x: 0, y: 0, width: w, height: h))
            ctx.stroke(border, with: .color(.white.opacity(0.10)), lineWidth: 1)
        }
        .clipShape(RoundedRectangle(cornerRadius: 8))
        .overlay(alignment: .top) {
            HStack {
                Text("+50¢"); Spacer()
                Text("PITCH HISTORY"); Spacer()
                Text("-50¢")
            }
            .font(.system(size: 8, weight: .medium, design: .rounded))
            .tracking(1)
            .foregroundColor(.white.opacity(0.28))
            .padding(.horizontal, 8).padding(.top, 3)
        }
    }
}

#Preview {
    TunerView().preferredColorScheme(.dark)
}
