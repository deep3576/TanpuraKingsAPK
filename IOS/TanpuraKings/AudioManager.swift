import Foundation
import AVFoundation

final class AudioManager {
    static let shared = AudioManager()

    private let engine = AVAudioEngine()
    private let reverbUnit = AVAudioUnitReverb()
    private let delayUnit = AVAudioUnitDelay()
    private let echoUnit = AVAudioUnitDelay()

    private var isInitialized = false

    private struct ActiveNote {
        let player: AVAudioPlayerNode
        let pitch: AVAudioUnitTimePitch
        let buffer: AVAudioPCMBuffer
        var volume: Float
    }

    private var activeNotes: [String: ActiveNote] = [:]
    private var buffers: [String: AVAudioPCMBuffer] = [:]

    private var fineTuneCents: Float = 0
    private var reverbMix: Float = 0
    private var echoMix: Float = 0
    private var echoDelayMs: Float = 300
    private var delayMix: Float = 0
    private var delayTimeMs: Float = 500

    private let queue = DispatchQueue(label: "com.kingsman.tanpurakings.audio")

    private let noteKeys = ["c","csharp","d","dsharp","e","f","fsharp","g","gsharp","a","asharp","b"]

    private init() {}

    func initializeIfNeeded() {
        guard !isInitialized else { return }

        do {
            let session = AVAudioSession.sharedInstance()
            try session.setCategory(.playback, mode: .default, options: [.mixWithOthers])
            try session.setActive(true)
        } catch {
            print("AudioSession error: \(error)")
        }

        reverbUnit.loadFactoryPreset(.largeHall)
        reverbUnit.wetDryMix = 0

        delayUnit.delayTime = TimeInterval(delayTimeMs / 1000.0)
        delayUnit.feedback = 0
        delayUnit.wetDryMix = 0

        echoUnit.delayTime = TimeInterval(echoDelayMs / 1000.0)
        echoUnit.feedback = 45
        echoUnit.wetDryMix = 0

        engine.attach(reverbUnit)
        engine.attach(delayUnit)
        engine.attach(echoUnit)

        let mainMixer = engine.mainMixerNode
        let format = mainMixer.outputFormat(forBus: 0)

        engine.connect(reverbUnit, to: delayUnit, format: format)
        engine.connect(delayUnit, to: echoUnit, format: format)
        engine.connect(echoUnit, to: mainMixer, format: format)

        preloadBuffers()

        do {
            try engine.start()
            isInitialized = true
            print("AudioManager ready. Loaded: \(buffers.keys.sorted())")
        } catch {
            print("Engine start error: \(error)")
        }
    }

    private func preloadBuffers() {
        for key in noteKeys {
            guard let url = Bundle.main.url(forResource: key, withExtension: "mp3", subdirectory: "Audio")
                ?? Bundle.main.url(forResource: key, withExtension: "mp3") else {
                print("Missing audio resource: \(key).mp3")
                continue
            }
            do {
                let file = try AVAudioFile(forReading: url)
                let processingFormat = file.processingFormat
                let frameCount = AVAudioFrameCount(file.length)
                guard let buffer = AVAudioPCMBuffer(pcmFormat: processingFormat, frameCapacity: frameCount) else {
                    continue
                }
                try file.read(into: buffer)
                buffers[key] = buffer
            } catch {
                print("Load error for \(key): \(error)")
            }
        }
    }

    private func fileKey(from noteName: String) -> String {
        return noteName.lowercased().replacingOccurrences(of: "#", with: "sharp")
    }

    func playNote(_ noteName: String, masterVolume: Float, noteVolume: Float = 1.0) {
        queue.async { [weak self] in
            guard let self = self else { return }
            guard self.isInitialized else { return }
            guard self.activeNotes.count < MAX_ACTIVE_NOTES else { return }
            guard self.activeNotes[noteName] == nil else { return }

            let key = self.fileKey(from: noteName)
            guard let buffer = self.buffers[key] else {
                print("Buffer not ready for \(key)")
                return
            }

            let player = AVAudioPlayerNode()
            let pitch = AVAudioUnitTimePitch()
            pitch.pitch = self.fineTuneCents

            self.engine.attach(player)
            self.engine.attach(pitch)

            let format = buffer.format
            self.engine.connect(player, to: pitch, format: format)
            self.engine.connect(pitch, to: self.reverbUnit, format: format)

            let vol = max(0, min(1, masterVolume * noteVolume))
            player.volume = vol

            player.scheduleBuffer(buffer, at: nil, options: .loops, completionHandler: nil)
            player.play()

            self.activeNotes[noteName] = ActiveNote(player: player, pitch: pitch, buffer: buffer, volume: noteVolume)
        }
    }

    func stopNote(_ noteName: String) {
        queue.async { [weak self] in
            guard let self = self else { return }
            guard let note = self.activeNotes.removeValue(forKey: noteName) else { return }
            note.player.stop()
            self.engine.detach(note.player)
            self.engine.detach(note.pitch)
        }
    }

    func stopAllNotes() {
        queue.async { [weak self] in
            guard let self = self else { return }
            for (_, note) in self.activeNotes {
                note.player.stop()
                self.engine.detach(note.player)
                self.engine.detach(note.pitch)
            }
            self.activeNotes.removeAll()
        }
    }

    func updateNoteVolume(_ noteName: String, noteVolume: Float, masterVolume: Float) {
        queue.async { [weak self] in
            guard let self = self else { return }
            guard var note = self.activeNotes[noteName] else { return }
            note.volume = noteVolume
            let vol = max(0, min(1, masterVolume * noteVolume))
            note.player.volume = vol
            self.activeNotes[noteName] = note
        }
    }

    func updateMasterVolume(_ masterVolume: Float) {
        queue.async { [weak self] in
            guard let self = self else { return }
            for (_, note) in self.activeNotes {
                let vol = max(0, min(1, masterVolume * note.volume))
                note.player.volume = vol
            }
        }
    }

    func updateEffects(
        reverbMix reverbVal: Float,
        fineTune: Float,
        echoMix echoVal: Float,
        echoDelay echoDelayVal: Float,
        delayMix delayVal: Float,
        delayTime delayTimeVal: Float
    ) {
        queue.async { [weak self] in
            guard let self = self else { return }

            if fineTune != self.fineTuneCents {
                self.fineTuneCents = fineTune
                for (_, note) in self.activeNotes {
                    note.pitch.pitch = fineTune
                }
            }

            self.reverbMix = reverbVal
            self.reverbUnit.wetDryMix = max(0, min(100, reverbVal))

            self.echoMix = echoVal
            self.echoDelayMs = echoDelayVal
            self.echoUnit.delayTime = TimeInterval(max(0.05, min(2.0, echoDelayVal / 1000.0)))
            self.echoUnit.wetDryMix = max(0, min(100, echoVal * 100))

            self.delayMix = delayVal
            self.delayTimeMs = delayTimeVal
            self.delayUnit.delayTime = TimeInterval(max(0.05, min(2.0, delayTimeVal / 1000.0)))
            self.delayUnit.wetDryMix = max(0, min(100, delayVal * 100))
        }
    }

    func release() {
        queue.async { [weak self] in
            guard let self = self else { return }
            for (_, note) in self.activeNotes {
                note.player.stop()
                self.engine.detach(note.player)
                self.engine.detach(note.pitch)
            }
            self.activeNotes.removeAll()
            self.engine.stop()
            self.buffers.removeAll()
            self.isInitialized = false
            print("AudioManager released")
        }
    }
}
