import Foundation
import AVFoundation
import Combine

/// Real-time chromatic pitch detector backed by the device microphone.
final class TunerManager: ObservableObject {
    static let shared = TunerManager()

    // MARK: - Published state

    @Published var frequency: Float = 0
    @Published var noteName: String = "—"
    @Published var cents: Float = 0
    @Published var isListening: Bool = false
    @Published var inputLevel: Float = 0
    @Published var detected: Bool = false
    /// Rolling cents-deviation history (max 150 frames ≈ 6 s).
    /// Float.nan marks silence gaps so the graph can break the line.
    @Published var centsHistory: [Float] = []

    // MARK: - Internals

    private let inputEngine = AVAudioEngine()
    private var tapInstalled = false
    private var sampleRate: Double = 44_100
    private let referenceA: Double = 440.0

    // Note stabilization: require 3 consecutive frames of the same note
    // before displaying it, so the label doesn't flicker at note boundaries.
    private var pendingNote: String = ""
    private var pendingNoteCount: Int = 0

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
        centsHistory.removeAll()
        pendingNote = ""
        pendingNoteCount = 0

        do {
            let session = AVAudioSession.sharedInstance()
            try session.setCategory(.playback, mode: .default, options: [])
            try session.setActive(true)
        } catch {
            debugLog("Tuner: restore session error: \(error)")
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
            debugLog("Tuner: session error: \(error)")
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
            debugLog("Tuner: engine start error: \(error)")
        }
    }

    // MARK: - DSP

    private func process(buffer: AVAudioPCMBuffer) {
        guard let channelData = buffer.floatChannelData?[0] else { return }
        let frameCount = Int(buffer.frameLength)
        guard frameCount > 512 else { return }

        var sumSq: Float = 0
        for i in 0..<frameCount {
            let s = channelData[i]
            sumSq += s * s
        }
        let rms = sqrt(sumSq / Float(frameCount))
        let level = min(1.0, Double(rms) * 5.0)

        if rms < 0.005 {
            DispatchQueue.main.async { [weak self] in
                guard let self = self else { return }
                self.inputLevel = Float(level)
                self.detected = false
                self.centsHistory.append(.nan)
                if self.centsHistory.count > 150 { self.centsHistory.removeFirst() }
            }
            return
        }

        let samples = Array(UnsafeBufferPointer(start: channelData, count: frameCount))
        let sr = sampleRate

        let minLag = max(2, Int(sr / 1000.0))
        let maxLag = min(samples.count - 1, Int(sr / 60.0))
        guard maxLag > minLag + 4 else { return }

        let zeroLag = sumSq
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
                    if c > zeroLag * 0.5 { break }
                }
            }
            k += 1
        }

        guard bestIdx > 0 else {
            DispatchQueue.main.async { [weak self] in
                guard let self = self else { return }
                self.inputLevel = Float(level)
                self.detected = false
                self.centsHistory.append(.nan)
                if self.centsHistory.count > 150 { self.centsHistory.removeFirst() }
            }
            return
        }

        let yLeft  = corr[max(0, bestIdx - 1)]
        let yPeak  = corr[bestIdx]
        let yRight = corr[min(corr.count - 1, bestIdx + 1)]
        let denom  = yLeft - 2 * yPeak + yRight
        let shift: Float = denom == 0 ? 0 : 0.5 * (yLeft - yRight) / denom
        let refinedLag = Float(bestIdx + minLag) + shift
        guard refinedLag > 0 else { return }
        let freq = Float(sr) / refinedLag
        let clarity = zeroLag == 0 ? 0 : bestCorr / zeroLag

        if !freq.isFinite || freq < 50 || freq > 1500 || clarity < 0.3 {
            DispatchQueue.main.async { [weak self] in
                guard let self = self else { return }
                self.inputLevel = Float(level)
                self.detected = false
                self.centsHistory.append(.nan)
                if self.centsHistory.count > 150 { self.centsHistory.removeFirst() }
            }
            return
        }

        let (note, centsValue) = noteAndCents(forFrequency: Double(freq), refA: referenceA)

        DispatchQueue.main.async { [weak self] in
            guard let self = self else { return }
            // Heavy smoothing (0.85) keeps the display stable without hiding
            // real pitch drift — the history graph shows the raw trajectory.
            let smoothFactor: Float = 0.85
            let smoothedFreq = self.frequency == 0
                ? freq
                : self.frequency * smoothFactor + freq * (1 - smoothFactor)
            let smoothedCents = self.cents * smoothFactor + Float(centsValue) * (1 - smoothFactor)
            self.frequency = smoothedFreq
            self.cents = smoothedCents

            // Only flip the displayed note after 3 consecutive frames agree —
            // prevents rapid flickering at note boundaries.
            if note == self.pendingNote {
                self.pendingNoteCount += 1
            } else {
                self.pendingNote = note
                self.pendingNoteCount = 1
            }
            if self.pendingNoteCount >= 3 {
                self.noteName = note
            }

            self.inputLevel = Float(level)
            self.detected = true

            self.centsHistory.append(smoothedCents)
            if self.centsHistory.count > 150 { self.centsHistory.removeFirst() }
        }
    }

    private func noteAndCents(forFrequency freq: Double, refA: Double) -> (String, Double) {
        let names = ["C","C#","D","D#","E","F","F#","G","G#","A","A#","B"]
        let semis  = 12.0 * log2(freq / refA) + 69.0
        let nearest = round(semis)
        let cents   = (semis - nearest) * 100.0
        let midi    = Int(nearest)
        let octave  = midi / 12 - 1
        let idx     = ((midi % 12) + 12) % 12
        return ("\(names[idx])\(octave)", cents)
    }
}
