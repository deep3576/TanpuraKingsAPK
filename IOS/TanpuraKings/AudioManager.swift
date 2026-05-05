import Foundation
import AVFoundation

final class AudioManager: NSObject {
    static let shared = AudioManager()

    private let engine = AVAudioEngine()
    private let effectsBus = AVAudioMixerNode()
    private let eqUnit = AVAudioUnitEQ(numberOfBands: 3)
    private let reverbUnit = AVAudioUnitReverb()
    private let delayUnit = AVAudioUnitDelay()
    private let echoUnit = AVAudioUnitDelay()

    // Metronome runs on its own player on a separate branch into the main
    // mixer, so it bypasses the reverb/delay/echo chain.
    private let metronomePlayer = AVAudioPlayerNode()
    private var metronomeFile: AVAudioFile?
    private var metronomeTimer: DispatchSourceTimer?
    private var metronomeRunning = false
    private var metronomeBPM: Float = 80
    private var metronomeVolume: Float = 0.7

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
        // Bumped on every engine/route restart. Stale completion handlers
        // captured an older value and bail out, so we never end up with two
        // re-schedule loops feeding the same player after a route change.
        var generation: Int = 0
        // Volume the note SHOULD be at once any in-flight fade resolves.
        // updateMasterVolume / updateNoteVolume update this, and the active
        // fade re-targets toward it on every step.
        var targetVolume: Float = 1.0
        // Bumped on each new fade so a stale fade ramp from a previous
        // play/stop bails out on the next step.
        var fadeGeneration: Int = 0

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

    // 0.0 = mono center, 1.0 = full stereo. With multiple active notes we
    // pan them evenly across [-stereoWidth, +stereoWidth] for a wider drone.
    private var stereoWidth: Float = 0.5

    // Fade duration for note start/stop. Avoids the "click/pop" you get
    // when AVAudioPlayerNode.play()/stop() is called instantly, and gives
    // a smooth crossfade when the user taps a different Sa.
    private let noteFadeMs: Double = 350

    private let queue = DispatchQueue(label: "com.kingsman.tanpurakings.audio")

    private let noteKeys = ["c","csharp","d","dsharp","e","f","fsharp","g","gsharp","a","asharp","b"]

    private var didRegisterObservers = false

    // Set to true on interruption.began, cleared on .ended or when setActive
    // finally succeeds in the watchdog.  Lets the watchdog distinguish
    // "session contested by another BT device — keep retrying" from
    // "engine died for another reason".
    private var isInterrupted = false

    // Periodic safety net. Re-kicks the engine and any active player that
    // should be looping but has gone silent (e.g. car Bluetooth dropping the
    // render thread, or a missed scheduleFile completion handler).
    private var watchdog: DispatchSourceTimer?

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
                guard let self = self else { return }
                self.isInterrupted = true
                self.engine.pause()
            }
        case .ended:
            // We always restart regardless of the .shouldResume hint — iOS
            // omits it for many sources (BT handoff, Siri, nav prompts).
            // restartEngineAndResumeNotes clears isInterrupted before it
            // tries setActive, and re-sets it if setActive is still failing
            // (session not yet released) so the watchdog keeps retrying.
            restartEngineAndResumeNotes()
        @unknown default:
            break
        }
    }

    @objc private func handleMediaServicesReset(_ note: Notification) {
        // mediaservicesd died — every engine node is invalid; rebuild.
        queue.async { [weak self] in
            guard let self = self else { return }
            self.watchdog?.cancel()
            self.watchdog = nil
            self.isInitialized = false
            self.isInterrupted = false
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
            // Clear the interrupted flag optimistically; re-set it below if
            // the session is still contested so the watchdog keeps retrying.
            self.isInterrupted = false
            do {
                try AVAudioSession.sharedInstance().setActive(true)

                // After a route/config change every AVAudioPlayerNode has lost
                // its scheduled file. Stop each player, restart the engine, then
                // re-schedule + re-play. Bumping the generation invalidates any
                // in-flight completion handler so we never run two re-schedule
                // loops on the same player.
                for (_, note) in self.activeNotes where !note.isStopping {
                    note.generation += 1
                    note.player.stop()
                }

                if !self.engine.isRunning {
                    self.engine.prepare()
                    try self.engine.start()
                }

                for (_, note) in self.activeNotes where !note.isStopping {
                    self.scheduleLooping(for: note)
                    note.player.play()
                }
            } catch {
                // Session still contested (common when a Bluetooth ownership
                // change and the interruption-end fire at the same time, or
                // when the remote device doesn't release the A2DP link cleanly).
                // Mark as interrupted so the watchdog retries every 2 s.
                print("Restart deferred — session busy: \(error)")
                self.isInterrupted = true
            }
        }
    }

    func initializeIfNeeded() {
        guard !isInitialized else { return }

        do {
            let session = AVAudioSession.sharedInstance()
            // Important: NO .mixWithOthers. With mixWithOthers, the session is
            // treated as "secondary" and iOS will not engage a Bluetooth A2DP
            // route until some other app starts primary playback. That caused
            // the bug where Tanpura was silent on already-connected Bluetooth
            // until YouTube was opened.
            try session.setCategory(.playback, mode: .default, options: [])
            try session.setActive(true)
        } catch {
            print("AudioSession error: \(error)")
        }

        // Cathedral gives more space and warmth than largeHall for a tanpura drone.
        reverbUnit.loadFactoryPreset(.cathedral)
        reverbUnit.wetDryMix = 0

        delayUnit.delayTime = TimeInterval(delayTimeMs / 1000.0)
        // 55 % feedback = ~4–5 audible repeats before fading. 0 % (the old value)
        // meant a single, near-inaudible tap — that was why the effect felt broken.
        delayUnit.feedback = 55
        delayUnit.wetDryMix = 0

        echoUnit.delayTime = TimeInterval(echoDelayMs / 1000.0)
        echoUnit.feedback = 68   // more tail than the old 45 %
        echoUnit.wetDryMix = 0

        // 3-band EQ: low shelf @ 100 Hz, mid parametric @ 1 kHz, high shelf @ 8 kHz.
        // Default gain = 0 dB (flat). User adjusts per band via the UI.
        eqUnit.bands[0].filterType = .lowShelf
        eqUnit.bands[0].frequency = 100
        eqUnit.bands[0].gain = 0
        eqUnit.bands[0].bypass = false
        eqUnit.bands[1].filterType = .parametric
        eqUnit.bands[1].frequency = 1000
        eqUnit.bands[1].bandwidth = 1.0
        eqUnit.bands[1].gain = 0
        eqUnit.bands[1].bypass = false
        eqUnit.bands[2].filterType = .highShelf
        eqUnit.bands[2].frequency = 8000
        eqUnit.bands[2].gain = 0
        eqUnit.bands[2].bypass = false

        engine.attach(effectsBus)
        engine.attach(eqUnit)
        engine.attach(reverbUnit)
        engine.attach(delayUnit)
        engine.attach(echoUnit)
        engine.attach(metronomePlayer)

        let mainMixer = engine.mainMixerNode

        // Drone path: per-note players → effectsBus → EQ → reverb → delay → echo → main
        engine.connect(effectsBus, to: eqUnit, format: processingFormat)
        engine.connect(eqUnit, to: reverbUnit, format: processingFormat)
        engine.connect(reverbUnit, to: delayUnit, format: processingFormat)
        engine.connect(delayUnit, to: echoUnit, format: processingFormat)
        engine.connect(echoUnit, to: mainMixer, format: processingFormat)
        // Metronome bypasses the effects so the click stays dry.
        engine.connect(metronomePlayer, to: mainMixer, format: processingFormat)

        openAudioFiles()
        openMetronomeFile()

        do {
            engine.prepare()
            try engine.start()
            isInitialized = true
            registerObservers()
            startWatchdog()
            print("AudioManager ready. Opened files: \(audioFiles.keys.sorted())")
        } catch {
            print("Engine start error: \(error)")
        }
    }

    private func startWatchdog() {
        watchdog?.cancel()
        let timer = DispatchSource.makeTimerSource(queue: queue)
        // 2 s interval — tighter than the old 3 s so audio resumes faster
        // after a Bluetooth ownership change where .interruption.ended never
        // fires (the most common multi-device BT failure mode).
        timer.schedule(deadline: .now() + 2, repeating: 2.0)
        timer.setEventHandler { [weak self] in
            self?.watchdogTick()
        }
        timer.resume()
        watchdog = timer
    }

    // Called every 2 s on the audio queue. Handles two failure modes:
    //
    // 1. isInterrupted == true — another BT device has the A2DP link and
    //    iOS never sent .interruption.ended.  Keep calling setActive(true)
    //    every tick; when it finally succeeds the link has been released and
    //    we can restart.
    //
    // 2. isInterrupted == false but a player silently stopped — engine died
    //    from a route change we missed, or a loop completion handler was
    //    never called (seen on some car head units).
    private func watchdogTick() {
        guard isInitialized else { return }
        guard !activeNotes.isEmpty else { return }

        if isInterrupted {
            // Try to reclaim the session. If the remote device still holds
            // the BT link setActive throws and we return to try again next tick.
            do {
                try AVAudioSession.sharedInstance().setActive(true)
                isInterrupted = false
                // Session reclaimed — fall through to restart the engine/players.
            } catch {
                return // Still contested; watchdog will retry in 2 s.
            }
        }

        if !engine.isRunning {
            do {
                try AVAudioSession.sharedInstance().setActive(true)
                engine.prepare()
                try engine.start()
            } catch {
                print("Watchdog engine restart failed: \(error)")
                isInterrupted = true // keep retrying next tick
                return
            }
        }

        for (_, note) in activeNotes where !note.isStopping {
            if !note.player.isPlaying {
                note.generation += 1
                scheduleLooping(for: note)
                note.player.play()
            }
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

    private func openMetronomeFile() {
        guard let url = Bundle.main.url(forResource: "click", withExtension: "mp3", subdirectory: "Audio")
            ?? Bundle.main.url(forResource: "click", withExtension: "mp3") else {
            print("Missing click.mp3")
            return
        }
        metronomeFile = try? AVAudioFile(forReading: url)
    }

    private func fileKey(from noteName: String) -> String {
        return noteName.lowercased().replacingOccurrences(of: "#", with: "sharp")
    }

    private func scheduleLooping(for note: ActiveNote) {
        let file = note.file
        let player = note.player
        let gen = note.generation
        file.framePosition = 0
        player.scheduleFile(file, at: nil) { [weak self, weak note] in
            guard let self = self, let note = note, !note.isStopping else { return }
            // If this completion handler belongs to a player generation that
            // has since been invalidated (route change, watchdog re-kick),
            // bail out — another scheduleLooping is already running.
            guard note.generation == gen else { return }
            self.queue.async {
                guard !note.isStopping, note.generation == gen else { return }
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

            let target = max(0, min(1, masterVolume * noteVolume))

            let note = ActiveNote(player: player, pitch: pitch, file: file, volume: noteVolume)
            note.targetVolume = target
            self.activeNotes[noteName] = note

            self.scheduleLooping(for: note)
            // Start silent and fade up — eliminates the click and gives a
            // crossfade-feel when this note is replacing another.
            player.volume = 0
            player.play()
            self.fadeVolume(of: note, to: target, durationMs: self.noteFadeMs)
            self.reapplyStereoSpread()
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

    // MARK: - Fades

    private func fadeVolume(of note: ActiveNote, to target: Float, durationMs: Double) {
        note.targetVolume = target
        note.fadeGeneration += 1
        let myGen = note.fadeGeneration
        let steps = 20
        let stepDelay = durationMs / Double(steps) / 1000.0
        let from = note.player.volume
        for i in 1...steps {
            queue.asyncAfter(deadline: .now() + stepDelay * Double(i)) { [weak self, weak note] in
                guard let _ = self, let note = note else { return }
                guard note.fadeGeneration == myGen, !note.isStopping else { return }
                let alpha = Float(i) / Float(steps)
                // Re-read targetVolume each step so updateMaster/NoteVolume
                // calls during the fade still take effect smoothly.
                let endVal = note.targetVolume
                note.player.volume = from + (endVal - from) * alpha
            }
        }
    }

    private func fadeAndTeardown(_ note: ActiveNote, durationMs: Double? = nil) {
        let dur = durationMs ?? noteFadeMs
        note.fadeGeneration += 1
        let from = note.player.volume
        let steps = 20
        let stepDelay = dur / Double(steps) / 1000.0
        for i in 1...steps {
            queue.asyncAfter(deadline: .now() + stepDelay * Double(i)) { [weak note] in
                guard let note = note else { return }
                let alpha = Float(i) / Float(steps)
                note.player.volume = max(0, from * (1 - alpha))
            }
        }
        queue.asyncAfter(deadline: .now() + dur / 1000.0) { [weak self] in
            guard let self = self else { return }
            self.teardown(note)
        }
    }

    // MARK: - Stereo width

    private func reapplyStereoSpread() {
        let sortedKeys = activeNotes.keys.sorted()
        let live = sortedKeys.compactMap { activeNotes[$0] }.filter { !$0.isStopping }
        let count = live.count
        guard count > 0 else { return }
        if count == 1 {
            live[0].player.pan = 0
            return
        }
        let step = (2.0 * stereoWidth) / Float(count - 1)
        for (i, note) in live.enumerated() {
            note.player.pan = -stereoWidth + step * Float(i)
        }
    }

    func updateStereoWidth(_ width: Float) {
        queue.async { [weak self] in
            guard let self = self else { return }
            self.stereoWidth = max(0, min(1, width))
            self.reapplyStereoSpread()
        }
    }

    // MARK: - 3-band EQ (each gain in dB, -12...+12)

    func updateEQ(low: Float, mid: Float, high: Float) {
        queue.async { [weak self] in
            guard let self = self else { return }
            self.eqUnit.bands[0].gain = max(-12, min(12, low))
            self.eqUnit.bands[1].gain = max(-12, min(12, mid))
            self.eqUnit.bands[2].gain = max(-12, min(12, high))
        }
    }

    // MARK: - Metronome

    func setMetronomeBPM(_ bpm: Float) {
        queue.async { [weak self] in
            guard let self = self else { return }
            self.metronomeBPM = max(40, min(240, bpm))
            // Timer reads metronomeBPM fresh on every tick, so no reschedule needed.
        }
    }

    func setMetronomeVolume(_ v: Float) {
        queue.async { [weak self] in
            guard let self = self else { return }
            self.metronomeVolume = max(0, min(1, v))
            self.metronomePlayer.volume = self.metronomeVolume
        }
    }

    func startMetronome() {
        queue.async { [weak self] in
            guard let self = self, self.isInitialized, self.metronomeFile != nil else { return }
            guard !self.metronomeRunning else { return }
            self.metronomeRunning = true
            self.metronomePlayer.volume = self.metronomeVolume
            if !self.metronomePlayer.isPlaying {
                self.metronomePlayer.play()
            }
            self.scheduleNextMetronomeTick()
        }
    }

    // Single-fire self-rescheduling timer. Each tick reads the current BPM
    // so tempo changes take effect at the very next beat — no dead-time caused
    // by re-setting a repeating timer's deadline on every slider drag.
    private func scheduleNextMetronomeTick() {
        guard metronomeRunning else { return }
        tickMetronome()
        let interval = 60.0 / Double(metronomeBPM)
        let timer = DispatchSource.makeTimerSource(queue: queue)
        timer.schedule(deadline: .now() + interval)
        timer.setEventHandler { [weak self] in
            guard let self = self else { return }
            self.metronomeTimer = nil
            self.scheduleNextMetronomeTick()
        }
        timer.resume()
        metronomeTimer = timer
    }

    func stopMetronome() {
        queue.async { [weak self] in
            guard let self = self else { return }
            self.metronomeTimer?.cancel()
            self.metronomeTimer = nil
            self.metronomePlayer.stop()
            self.metronomeRunning = false
        }
    }

    private func tickMetronome() {
        guard let file = metronomeFile else { return }
        if !metronomePlayer.isPlaying {
            metronomePlayer.play()
        }
        file.framePosition = 0
        metronomePlayer.scheduleFile(file, at: nil)
    }

    func stopNote(_ noteName: String) {
        queue.async { [weak self] in
            guard let self = self else { return }
            guard let note = self.activeNotes.removeValue(forKey: noteName) else { return }
            // Mark stopping + bump generation so any in-flight scheduleFile
            // completion handler bails out instead of re-queueing the loop.
            note.isStopping = true
            note.generation += 1
            self.fadeAndTeardown(note)
            self.reapplyStereoSpread()
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
            note.targetVolume = vol
            // If a fade is in progress it'll converge on the new target.
            // Otherwise apply immediately.
            note.player.volume = vol
        }
    }

    func updateMasterVolume(_ masterVolume: Float) {
        queue.async { [weak self] in
            guard let self = self else { return }
            for (_, note) in self.activeNotes {
                let vol = max(0, min(1, masterVolume * note.volume))
                note.targetVolume = vol
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
            // Feedback scales with mix: low mix = subtle single echo, high mix = long decay.
            self.echoUnit.feedback = 50 + echoVal * 30   // 50–80 %

            self.delayMix = delayVal
            self.delayTimeMs = delayTimeVal
            self.delayUnit.delayTime = TimeInterval(max(0.05, min(2.0, delayTimeVal / 1000.0)))
            self.delayUnit.wetDryMix = max(0, min(100, delayVal * 100))
            self.delayUnit.feedback = 45 + delayVal * 30  // 45–75 %
        }
    }

    func release() {
        queue.async { [weak self] in
            guard let self = self else { return }
            self.watchdog?.cancel()
            self.watchdog = nil
            self.metronomeTimer?.cancel()
            self.metronomeTimer = nil
            self.metronomePlayer.stop()
            self.metronomeRunning = false
            for (_, note) in self.activeNotes {
                self.teardown(note)
            }
            self.activeNotes.removeAll()
            self.engine.stop()
            self.audioFiles.removeAll()
            self.metronomeFile = nil
            self.isInitialized = false
            print("AudioManager released")
        }
    }
}
