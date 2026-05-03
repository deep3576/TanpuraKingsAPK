import Foundation
import AVFoundation
import Combine

/// Real-time chromatic pitch detector backed by the device microphone.
///
/// Lifecycle:
///   - `start()` requests mic permission (idempotent if already granted),
///     switches the shared `AVAudioSession` to `.playAndRecord` with
///     `.measurement` mode (disables iOS voice-processing / AGC, which
///     would otherwise distort the fundamental we're trying to detect),
///     installs a tap on the input node, and begins streaming.
///   - `stop()` removes the tap, stops the input engine, and restores
///     `.playback` so the drone tab works correctly when the user returns.
///
/// SwiftUI talks to this via `@Published` state. The drone tab is expected
/// to call `AudioManager.shared.stopAllNotes()` *before* `start()` so the
/// mic doesn't pick up our own tanpura.
final class TunerManager: ObservableObject {
    static let shared = TunerManager()

    // MARK: - Published state for the UI

    @Published var frequency: Float = 0
    @Published var noteName: String = "—"
    @Published var cents: Float = 0
    @Published var isListening: Bool = false
    @Published var inputLevel: Float = 0
    @Published var detected: Bool = false

    // MARK: - Internals

    private let inputEngine = AVAudioEngine()
    private var tapInstalled = false
    private var sampleRate: Double = 44_100
    private let referenceA: Double = 440.0

    private init() {}

    // MARK: - Control

    func start() {
        guard !isListening else { return }
        Task { @MainActor [weak self] in
            guard let self = self else { return }
            let granted = await AVAudioApplication.requestRecordPermission()
            if granted {
                self.beginListening()
            } else {
                self.noteName = "Mic denied"
                self.detected = false
            }
        }
    }

    func stop() {
        guard isListening else { return }
        if tapInstalled {
            inputEngine.inputNode.removeTap(onBus: 0)
            tapInstalled = false
        }
        inputEngine.stop()
        isListening = false
        detected = false
        inputLevel = 0
        frequency = 0
        cents = 0

        // Restore the drone-friendly session category. The drone tab's
        // AudioManager observes routeChangeNotification (categoryChange)
        // and will re-start its engine if needed.
        do {
            let session = AVAudioSession.sharedInstance()
            try session.setCategory(.playback, mode: .default, options: [])
            try session.setActive(true)
        } catch {
            print("Tuner: restore session error: \(error)")
        }
    }

    private func beginListening() {
        do {
            let session = AVAudioSession.sharedInstance()
            try session.setCategory(
                .playAndRecord,
                mode: .measurement,
                options: [.defaultToSpeaker, .allowBluetooth]
            )
            try session.setActive(true)
        } catch {
            print("Tuner: session error: \(error)")
            return
        }

        let input = inputEngine.inputNode
        let format = input.outputFormat(forBus: 0)
        sampleRate = format.sampleRate

        let bufferSize: AVAudioFrameCount = 2048
        if tapInstalled { input.removeTap(onBus: 0) }
        input.installTap(onBus: 0, bufferSize: bufferSize, format: format) { [weak self] buffer, _ in
            self?.process(buffer: buffer)
        }
        tapInstalled = true

        do {
            inputEngine.prepare()
            try inputEngine.start()
            isListening = true
        } catch {
            print("Tuner: engine start error: \(error)")
        }
    }

    // MARK: - DSP

    private func process(buffer: AVAudioPCMBuffer) {
        guard let channelData = buffer.floatChannelData?[0] else { return }
        let frameCount = Int(buffer.frameLength)
        guard frameCount > 512 else { return }

        // RMS for level meter + silence gate.
        var sumSq: Float = 0
        for i in 0..<frameCount {
            let s = channelData[i]
            sumSq += s * s
        }
        let rms = sqrt(sumSq / Float(frameCount))
        let level = min(1.0, Double(rms) * 5.0)

        // Below the noise floor — don't bother detecting.
        if rms < 0.005 {
            DispatchQueue.main.async { [weak self] in
                self?.inputLevel = Float(level)
                self?.detected = false
            }
            return
        }

        // Copy frames into a local Swift Array — keeps the hot inner loop
        // free of pointer math.
        let samples = Array(UnsafeBufferPointer(start: channelData, count: frameCount))
        let sr = sampleRate

        // Search in the range 60 Hz...1000 Hz which covers most singing
        // voices and tanpura Sa (≈110–300 Hz) plus their first harmonic.
        let minLag = max(2, Int(sr / 1000.0))
        let maxLag = min(samples.count - 1, Int(sr / 60.0))
        guard maxLag > minLag + 4 else { return }

        // Pre-compute zero-lag autocorrelation once.
        let zeroLag = sumSq

        // Compute autocorrelation r(τ) for τ in [minLag..maxLag].
        var corr = [Float](repeating: 0, count: maxLag - minLag + 1)
        for k in 0..<corr.count {
            let lag = k + minLag
            var s: Float = 0
            let n = samples.count - lag
            var i = 0
            while i < n {
                s += samples[i] * samples[i + lag]
                i += 1
            }
            corr[k] = s
        }

        // Find the first significant local max after r(τ) drops below
        // 30 % of r(0). This dodges the octave-error trap where a
        // harmonic peak is taller than the fundamental peak.
        let threshold = zeroLag * 0.3
        var passedDip = false
        var bestIdx = -1
        var bestCorr: Float = 0
        var k = 1
        while k < corr.count - 1 {
            let c = corr[k]
            if !passedDip {
                if c < threshold { passedDip = true }
            } else {
                if c > corr[k - 1] && c > corr[k + 1] {
                    if c > bestCorr {
                        bestCorr = c
                        bestIdx = k
                    }
                    // The first peak above 50 % of zero-lag is almost
                    // certainly the fundamental — lock in and stop.
                    if c > zeroLag * 0.5 { break }
                }
            }
            k += 1
        }

        guard bestIdx > 0 else {
            DispatchQueue.main.async { [weak self] in
                self?.inputLevel = Float(level)
                self?.detected = false
            }
            return
        }

        // Parabolic interpolation for sub-sample lag accuracy.
        let yLeft = corr[max(0, bestIdx - 1)]
        let yPeak = corr[bestIdx]
        let yRight = corr[min(corr.count - 1, bestIdx + 1)]
        let denom = yLeft - 2 * yPeak + yRight
        let shift: Float = denom == 0 ? 0 : 0.5 * (yLeft - yRight) / denom
        let refinedLag = Float(bestIdx + minLag) + shift
        guard refinedLag > 0 else { return }
        let freq = Float(sr) / refinedLag
        let clarity = zeroLag == 0 ? 0 : bestCorr / zeroLag

        // Sanity bounds + clarity gate.
        if !freq.isFinite || freq < 50 || freq > 1500 || clarity < 0.3 {
            DispatchQueue.main.async { [weak self] in
                self?.inputLevel = Float(level)
                self?.detected = false
            }
            return
        }

        let (note, centsValue) = noteAndCents(forFrequency: Double(freq), refA: referenceA)

        DispatchQueue.main.async { [weak self] in
            guard let self = self else { return }
            // Light exponential smoothing — keeps the needle calm without
            // adding so much lag that the tuner feels sluggish.
            let smoothFactor: Float = 0.6
            let smoothedFreq = self.frequency == 0
                ? freq
                : self.frequency * smoothFactor + freq * (1 - smoothFactor)
            let smoothedCents = self.cents * smoothFactor + Float(centsValue) * (1 - smoothFactor)
            self.frequency = smoothedFreq
            self.cents = smoothedCents
            self.noteName = note
            self.inputLevel = Float(level)
            self.detected = true
        }
    }

    /// Returns (e.g.) ("A4", -3.2): nearest equal-tempered note name plus
    /// the deviation in cents (-50…+50). Uses 440 Hz reference A4 = MIDI 69.
    private func noteAndCents(forFrequency freq: Double, refA: Double) -> (String, Double) {
        let names = ["C","C#","D","D#","E","F","F#","G","G#","A","A#","B"]
        let semis = 12.0 * log2(freq / refA) + 69.0
        let nearest = round(semis)
        let cents = (semis - nearest) * 100.0
        let midi = Int(nearest)
        let octave = midi / 12 - 1
        let idx = ((midi % 12) + 12) % 12
        return ("\(names[idx])\(octave)", cents)
    }
}
