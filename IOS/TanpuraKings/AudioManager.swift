import Foundation
import AVFoundation

final class AudioManager: NSObject {
    static let shared = AudioManager()

    private let engine = AVAudioEngine()
    private let effectsBus = AVAudioMixerNode()
    private let reverbUnit = AVAudioUnitReverb()
    private let delayUnit = AVAudioUnitDelay()
    private let echoUnit = AVAudioUnitDelay()

    private var isInitialized = false

    private let processingFormat: AVAudioFormat = {
        guard let fmt = AVAudioFormat(standardFormatWithSampleRate: 44_100, channels: 2) else {
            fatalError("Failed to create processing format")
        }
        return fmt
    }()

    private final class ActiveNote {
        let player: AVAudioPlayerNode
        let pitch: AVAudioUnitTimePitch
        let file: AVAudioFile
        var volume: Float
        var isStopping: Bool = false

        init(player: AVAudioPlayerNode, pitch: AVAudioUnitTimePitch, file: AVAudioFile, volume: Float) {
            self.player = player
            self.pitch = pitch
            self.file = file
            self.volume = volume
        }
    }

    private var activeNotes: [String: ActiveNote] = [:]
    private var audioFiles: [String: AVAudioFile] = [:]

    private var fineTuneCents: Float = 0
    private var reverbMix: Float = 0
    private var echoMix: Float = 0
    private var echoDelayMs: Float = 300
    private var delayMix: Float = 0
    private var delayTimeMs: Float = 500

    private let queue = DispatchQueue(label: "com.kingsman.tanpurakings.audio")

    private let noteKeys = ["c","csharp","d","dsharp","e","f","fsharp","g","gsharp","a","asharp","b"]

    private var didRegisterObservers = false

    private override init() {
        super.init()
    }

    deinit {
        NotificationCenter.default.removeObserver(self)
    }

    private func registerObservers() {
        guard !didRegisterObservers else { return }
        didRegisterObservers = true
        let nc = NotificationCenter.default
        nc.addObserver(
            self,
            selector: #selector(handleRouteChange(_:)),
            name: AVAudioSession.routeChangeNotification,
            object: nil
        )
        nc.addObserver(
            self,
            selector: #selector(handleInterruption(_:)),
            name: AVAudioSession.interruptionNotification,
            object: nil
        )
        nc.addObserver(
            self,
            selector: #selector(handleMediaServicesReset(_:)),
            name: AVAudioSession.mediaServicesWereResetNotification,
            object: nil
        )
        nc.addObserver(
            self,
            selector: #selector(handleEngineConfigChange(_:)),
            name: .AVAudioEngineConfigurationChange,
            object: engine
        )
    }

    @objc private func handleRouteChange(_ note: Notification) {
        guard
            let reasonVal = note.userInfo?[AVAudioSessionRouteChangeReasonKey] as? UInt,
            let reason = AVAudioSession.RouteChangeReason(rawValue: reasonVal)
        else { return }

        switch reason {
        case .newDeviceAvailable, .oldDeviceUnavailable, .categoryChange, .override, .routeConfigurationChange:
            restartEngineAndResumeNotes()
        case .wakeFromSleep:
            restartEngineAndResumeNotes()
        default:
            break
        }
    }

    @objc private func handleInterruption(_ note: Notification) {
        guard
            let typeVal = note.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt,
            let type = AVAudioSession.InterruptionType(rawValue: typeVal)
        else { return }

        switch type {
        case .began:
            queue.async { [weak self] in
                self?.engine.pause()
            }
        case .ended:
            let opts = (note.userInfo?[AVAudioSessionInterruptionOptionKey] as? UInt).map {
                AVAudioSession.InterruptionOptions(rawValue: $0)
            }
            if opts?.contains(.shouldResume) == true {
                restartEngineAndResumeNotes()
            }
        @unknown default:
            break
        }
    }

    @objc private func handleMediaServicesReset(_ note: Notification) {
        // mediaservicesd died — every engine node is invalid; rebuild.
        queue.async { [weak self] in
            guard let self = self else { return }
            self.isInitialized = false
            self.engine.stop()
        }
        DispatchQueue.main.async { [weak self] in
            self?.initializeIfNeeded()
        }
    }

    @objc private func handleEngineConfigChange(_ note: Notification) {
        restartEngineAndResumeNotes()
    }

    private func restartEngineAndResumeNotes() {
        queue.async { [weak self] in
            guard let self = self, self.isInitialized else { return }
            do {
                try AVAudioSession.sharedInstance().setActive(true)

                // After a route/config change the engine is implicitly stopped and
                // every AVAudioPlayerNode has lost its scheduled file. We must stop
                // each player, restart the engine, then re-schedule + re-play.
                for (_, note) in self.activeNotes where !note.isStopping {
                    note.player.stop()
                }

                if !self.engine.isRunning {
                    try self.engine.start()
                }

                for (_, note) in self.activeNotes where !note.isStopping {
                    self.scheduleLooping(for: note)
                    note.player.play()
                }
            } catch {
                print("Restart error: \(error)")
            }
        }
    }

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

        engine.attach(effectsBus)
        engine.attach(reverbUnit)
        engine.attach(delayUnit)
        engine.attach(echoUnit)

        let mainMixer = engine.mainMixerNode

        engine.connect(effectsBus, to: reverbUnit, format: processingFormat)
        engine.connect(reverbUnit, to: delayUnit, format: processingFormat)
        engine.connect(delayUnit, to: echoUnit, format: processingFormat)
        engine.connect(echoUnit, to: mainMixer, format: processingFormat)

        openAudioFiles()

        do {
            try engine.start()
            isInitialized = true
            registerObservers()
            print("AudioManager ready. Opened files: \(audioFiles.keys.sorted())")
        } catch {
            print("Engine start error: \(error)")
        }
    }

    private func openAudioFiles() {
        for key in noteKeys {
            guard let url = Bundle.main.url(forResource: key, withExtension: "mp3", subdirectory: "Audio")
                ?? Bundle.main.url(forResource: key, withExtension: "mp3") else {
                print("Missing audio resource: \(key).mp3")
                continue
            }
            do {
                audioFiles[key] = try AVAudioFile(forReading: url)
            } catch {
                print("Open error for \(key): \(error)")
            }
        }
    }

    private func fileKey(from noteName: String) -> String {
        return noteName.lowercased().replacingOccurrences(of: "#", with: "sharp")
    }

    private func scheduleLooping(for note: ActiveNote) {
        let file = note.file
        let player = note.player
        file.framePosition = 0
        player.scheduleFile(file, at: nil) { [weak self, weak note] in
            guard let self = self, let note = note, !note.isStopping else { return }
            self.queue.async {
                guard !note.isStopping else { return }
                self.scheduleLooping(for: note)
            }
        }
    }

    func playNote(_ noteName: String, masterVolume: Float, noteVolume: Float = 1.0) {
        queue.async { [weak self] in
            guard let self = self else { return }
            guard self.isInitialized else { return }
            guard self.activeNotes.count < MAX_ACTIVE_NOTES else { return }
            guard self.activeNotes[noteName] == nil else { return }

            let key = self.fileKey(from: noteName)
            guard let file = self.audioFiles[key] else {
                print("File not opened for \(key)")
                return
            }

            let player = AVAudioPlayerNode()
            let pitch = AVAudioUnitTimePitch()
            pitch.pitch = self.fineTuneCents

            self.engine.attach(player)
            self.engine.attach(pitch)

            let fileFormat = file.processingFormat
            self.engine.connect(player, to: pitch, format: fileFormat)
            self.engine.connect(pitch, to: self.effectsBus, format: fileFormat)

            let vol = max(0, min(1, masterVolume * noteVolume))
            player.volume = vol

            let note = ActiveNote(player: player, pitch: pitch, file: file, volume: noteVolume)
            self.activeNotes[noteName] = note

            self.scheduleLooping(for: note)
            player.play()
        }
    }

    private func teardown(_ note: ActiveNote) {
        note.isStopping = true
        note.player.stop()
        engine.disconnectNodeOutput(note.pitch)
        engine.disconnectNodeOutput(note.player)
        engine.detach(note.player)
        engine.detach(note.pitch)
    }

    func stopNote(_ noteName: String) {
        queue.async { [weak self] in
            guard let self = self else { return }
            guard let note = self.activeNotes.removeValue(forKey: noteName) else { return }
            self.teardown(note)
        }
    }

    func stopAllNotes() {
        queue.async { [weak self] in
            guard let self = self else { return }
            for (_, note) in self.activeNotes {
                self.teardown(note)
            }
            self.activeNotes.removeAll()
        }
    }

    func updateNoteVolume(_ noteName: String, noteVolume: Float, masterVolume: Float) {
        queue.async { [weak self] in
            guard let self = self else { return }
            guard let note = self.activeNotes[noteName] else { return }
            note.volume = noteVolume
            let vol = max(0, min(1, masterVolume * noteVolume))
            note.player.volume = vol
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
                self.teardown(note)
            }
            self.activeNotes.removeAll()
            self.engine.stop()
            self.audioFiles.removeAll()
            self.isInitialized = false
            print("AudioManager released")
        }
    }
}
