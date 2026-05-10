import Foundation
import AVFoundation
import MediaPlayer

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
    // Each beat schedules the next via a DispatchWorkItem so the current BPM
    // is always read fresh — no timer restart needed when the slider changes.
    private var metronomeWorkItem: DispatchWorkItem?
    private var metronomeRunning = false
    private var metronomeBPM: Float = 80
    private var metronomeVolume: Float = 0.7
    // Bumped every time the timer is (re)started or stopped.
    // Any event handler that sees a stale generation bails out immediately,
    // which prevents a queued-but-about-to-fire old event from creating a
    // second beat chain after the timer has been replaced.
    private var metronomeGeneration: Int = 0

    private var isInitialized = false
    private var remoteCommandsConfigured = false

    private let processingFormat: AVAudioFormat = {
        guard let fmt = AVAudioFormat(standardFormatWithSampleRate: 44_100, channels: 2) else {
            fatalError("Failed to create processing format")
        }
        return fmt
    }()

    private final class ActiveNote {
        let player: AVAudioPlayerNode
        let pitch: AVAudioUnitTimePitch
        // PCM buffer scheduled with .loops — the engine replays it forever
        // at the hardware level with no completion-handler callbacks needed.
        let buffer: AVAudioPCMBuffer
        var volume: Float
        var isStopping: Bool = false
        // Volume the note SHOULD be at once any in-flight fade resolves.
        // updateMasterVolume / updateNoteVolume update this, and the active
        // fade re-targets toward it on every step.
        var targetVolume: Float = 1.0
        // Bumped on each new fade so a stale fade ramp from a previous
        // play/stop bails out on the next step.
        var fadeGeneration: Int = 0

        init(player: AVAudioPlayerNode, pitch: AVAudioUnitTimePitch, buffer: AVAudioPCMBuffer, volume: Float) {
            self.player = player
            self.pitch = pitch
            self.buffer = buffer
            self.volume = volume
        }
    }

    private var activeNotes: [String: ActiveNote] = [:]
    // Only the file URL is stored at startup — the PCM buffer is loaded lazily
    // inside playNote() the moment a note is tapped.  Keeping all 12 files
    // decoded simultaneously blows the 3 GB process limit (each tanpura
    // recording is ~100–200 MB as uncompressed float32 PCM; 12 × that = crash).
    // With lazy loading at most MAX_ACTIVE_NOTES (3) buffers live at once.
    private var audioFileURLs: [String: URL] = [:]

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
                // its scheduled buffer. Stop each player, restart the engine,
                // then re-schedule + re-play. With scheduleBuffer(.loops) there
                // are no completion handlers to invalidate, so no generation bump.
                for (_, note) in self.activeNotes where !note.isStopping {
                    note.player.stop()
                }

                if !self.engine.isRunning {
                    self.engine.prepare()
                    try self.engine.start()
                }

                for (_, note) in self.activeNotes where !note.isStopping {
                    self.scheduleLoopingBuffer(for: note)
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
        delayUnit.feedback = 90   // 2× previous 75 (capped near 100)
        delayUnit.wetDryMix = 0

        echoUnit.delayTime = TimeInterval(echoDelayMs / 1000.0)
        echoUnit.feedback = 97   // 2× previous 85 (capped near 100)
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
            setupRemoteCommands()
            print("AudioManager ready. Cached URLs: \(audioFileURLs.keys.sorted())")
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
                // Re-schedule the looping buffer and restart the player.
                // No generation bump needed — scheduleBuffer(.loops) has no
                // completion handler to invalidate.
                scheduleLoopingBuffer(for: note)
                note.player.play()
            }
        }
    }

    private func openAudioFiles() {
        // Only resolve and cache the file URL — no decoding yet.
        // The PCM buffer is loaded on demand in playNote() so we never hold
        // more than MAX_ACTIVE_NOTES decoded buffers in RAM simultaneously.
        for key in noteKeys {
            guard let url = Bundle.main.url(forResource: key, withExtension: "mp3", subdirectory: "Audio")
                ?? Bundle.main.url(forResource: key, withExtension: "mp3") else {
                print("Missing audio resource: \(key).mp3")
                continue
            }
            audioFileURLs[key] = url
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

    private func scheduleLoopingBuffer(for note: ActiveNote) {
        // .loops tells AVAudioEngine to replay the buffer indefinitely at the
        // hardware render level — zero completion-handler callbacks required.
        // This survives app backgrounding without any gaps or dropouts.
        note.player.scheduleBuffer(note.buffer, at: nil,
                                   options: .loops,
                                   completionHandler: nil)
    }

    func playNote(_ noteName: String, masterVolume: Float, noteVolume: Float = 1.0) {
        queue.async { [weak self] in
            guard let self = self else { return }
            guard self.isInitialized else { return }
            guard self.activeNotes.count < MAX_ACTIVE_NOTES else { return }
            guard self.activeNotes[noteName] == nil else { return }

            let key = self.fileKey(from: noteName)
            guard let url = self.audioFileURLs[key] else {
                print("No URL cached for \(key)")
                return
            }
            // Load the PCM buffer now, on demand.  This is the only point where
            // RAM for this note is allocated; it is freed automatically when
            // ActiveNote (and thus the buffer) is deallocated on stop.
            let buffer: AVAudioPCMBuffer
            do {
                let file = try AVAudioFile(forReading: url)
                let capacity = AVAudioFrameCount(file.length)
                guard let buf = AVAudioPCMBuffer(pcmFormat: file.processingFormat,
                                                 frameCapacity: capacity) else {
                    print("Could not allocate PCM buffer for \(key)")
                    return
                }
                try file.read(into: buf)
                buffer = buf
            } catch {
                print("Buffer load error for \(key): \(error)")
                return
            }

            let player = AVAudioPlayerNode()
            let pitch = AVAudioUnitTimePitch()
            pitch.pitch = self.fineTuneCents

            self.engine.attach(player)
            self.engine.attach(pitch)

            let bufferFormat = buffer.format
            self.engine.connect(player, to: pitch, format: bufferFormat)
            self.engine.connect(pitch, to: self.effectsBus, format: bufferFormat)

            let target = max(0, min(1, masterVolume * noteVolume))

            let note = ActiveNote(player: player, pitch: pitch, buffer: buffer, volume: noteVolume)
            note.targetVolume = target
            self.activeNotes[noteName] = note

            self.updateNowPlayingInfo()
            self.scheduleLoopingBuffer(for: note)
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
            let clamped = max(40, min(240, bpm))
            guard clamped != self.metronomeBPM else { return }
            self.metronomeBPM = clamped
            // No timer restart needed — scheduleNextBeat() reads metronomeBPM
            // fresh on every beat, so the new tempo takes effect automatically
            // at the next beat boundary without interrupting the current count.
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
            if !self.metronomePlayer.isPlaying { self.metronomePlayer.play() }
            // Bump generation to invalidate any lingering work item from a
            // previous run, then fire the first click immediately.
            self.metronomeGeneration += 1
            self.tickMetronome()
            self.scheduleNextBeat()
        }
    }

    // Schedules one DispatchWorkItem for the next beat using the CURRENT
    // metronomeBPM value.  The work item fires the click, then calls this
    // function again — forming a self-rescheduling chain.
    //
    // Because each link in the chain reads metronomeBPM fresh, a slider drag
    // is picked up at the very next beat with zero timer restarts or debounce.
    private func scheduleNextBeat() {
        metronomeWorkItem?.cancel()
        metronomeWorkItem = nil

        guard metronomeRunning else { return }

        let gen        = metronomeGeneration
        let intervalMs = max(1, Int(60_000.0 / Double(metronomeBPM)))

        let item = DispatchWorkItem { [weak self] in
            guard let self = self, self.metronomeGeneration == gen else { return }
            self.tickMetronome()
            self.scheduleNextBeat()
        }
        metronomeWorkItem = item
        queue.asyncAfter(deadline: .now() + .milliseconds(intervalMs), execute: item)
    }

    func stopMetronome() {
        queue.async { [weak self] in
            guard let self = self else { return }
            // Bump generation so any queued work item is a no-op when it fires.
            self.metronomeGeneration += 1
            self.metronomeWorkItem?.cancel()
            self.metronomeWorkItem = nil
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
            self.updateNowPlayingInfo()
            // Mark stopping so the watchdog and fade steps skip this note.
            note.isStopping = true
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
            self.updateNowPlayingInfo()
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
            // Square-root curve: makes even a small slider nudge instantly
            // audible rather than needing to reach 50+ before you feel it.
            // Reverb: 2× wetDryMix — reaches full wet at half-slider position
            let reverbT = reverbVal > 0 ? sqrt(reverbVal / 100.0) : 0
            self.reverbUnit.wetDryMix = Float(min(100.0, reverbT * 200.0))

            self.echoMix = echoVal
            self.echoDelayMs = echoDelayVal
            self.echoUnit.delayTime = TimeInterval(max(0.05, min(2.0, echoDelayVal / 1000.0)))
            // Echo wetDryMix: 2× — full wet at slider = 0.5
            self.echoUnit.wetDryMix = min(100, echoVal * 200)
            // Feedback 90–98 %: 2× previous 78–97 % (capped at 98 to avoid runaway)
            self.echoUnit.feedback = 90 + echoVal * 8    // 90–98 %

            self.delayMix = delayVal
            self.delayTimeMs = delayTimeVal
            self.delayUnit.delayTime = TimeInterval(max(0.05, min(2.0, delayTimeVal / 1000.0)))
            // Delay wetDryMix: 2× — full wet at slider = 0.5
            self.delayUnit.wetDryMix = min(100, delayVal * 200)
            // Feedback 86–97 %: 2× previous 72–95 % (capped at 97)
            self.delayUnit.feedback = 86 + delayVal * 11  // 86–97 %
        }
    }

    // MARK: - Now Playing + Remote Commands (lock screen / CarPlay / headphones)

    /// Registers play/pause/stop handlers with MPRemoteCommandCenter exactly once.
    /// Idempotent — safe to call from initializeIfNeeded().
    private func setupRemoteCommands() {
        guard !remoteCommandsConfigured else { return }
        remoteCommandsConfigured = true

        let cc = MPRemoteCommandCenter.shared()

        // Disable commands that don't apply to a drone instrument app
        [cc.nextTrackCommand, cc.previousTrackCommand,
         cc.skipForwardCommand, cc.skipBackwardCommand,
         cc.changePlaybackRateCommand, cc.changePlaybackPositionCommand,
         cc.changeShuffleModeCommand, cc.changeRepeatModeCommand,
         cc.likeCommand, cc.dislikeCommand, cc.bookmarkCommand].forEach {
            $0.isEnabled = false
        }

        // Stop / Pause → stop all active drone notes
        cc.stopCommand.isEnabled = true
        cc.stopCommand.addTarget { [weak self] _ in
            self?.stopAllNotes()
            return .success
        }

        cc.pauseCommand.isEnabled = true
        cc.pauseCommand.addTarget { [weak self] _ in
            self?.stopAllNotes()
            return .success
        }

        // Play is intentionally disabled: the user must open the app to choose
        // which note(s) to play. A future version could restore the last set.
        cc.playCommand.isEnabled = false

        // Toggle (headphone single-tap, steering-wheel button) → stop if playing
        cc.togglePlayPauseCommand.isEnabled = true
        cc.togglePlayPauseCommand.addTarget { [weak self] _ in
            guard let self = self else { return .commandFailed }
            self.queue.async {
                if !self.activeNotes.isEmpty { self.stopAllNotes() }
            }
            return .success
        }
    }

    // Thread-safe snapshot of the currently active note keys.
    // Read by CarPlaySceneDelegate on the main thread to build its browse list.
    var activeNoteNames: Set<String> {
        queue.sync { Set(activeNotes.keys) }
    }

    /// Updates MPNowPlayingInfoCenter with the current active note names.
    /// Call this from the audio queue whenever activeNotes changes.
    private func updateNowPlayingInfo() {
        let names    = activeNotes.keys.sorted()
        let playing  = !names.isEmpty

        DispatchQueue.main.async {
            // Notify CarPlaySceneDelegate (and any other observer) that the
            // active-notes set has changed so it can refresh its browse list.
            NotificationCenter.default.post(
                name: Notification.Name("tanpuraActiveNotesChanged"),
                object: nil
            )

            guard playing else {
                MPNowPlayingInfoCenter.default().nowPlayingInfo = nil
                return
            }
            var info: [String: Any] = [:]
            info[MPMediaItemPropertyTitle]                   = "Tanpura Kings"
            info[MPMediaItemPropertyArtist]                  = names.joined(separator: " • ")
            info[MPMediaItemPropertyAlbumTitle]              = "Tanpura Drone"
            info[MPNowPlayingInfoPropertyPlaybackRate]       = Float(1.0)
            info[MPNowPlayingInfoPropertyElapsedPlaybackTime] = Double(0)
            // Use a 24-hour duration so the lock screen shows no progress bar
            info[MPMediaItemPropertyPlaybackDuration]        = Double(86_400)
            if let image = UIImage(named: "Logo") {
                info[MPMediaItemPropertyArtwork] = MPMediaItemArtwork(
                    boundsSize: CGSize(width: 512, height: 512)) { _ in image }
            }
            MPNowPlayingInfoCenter.default().nowPlayingInfo = info
        }
    }

    func release() {
        queue.async { [weak self] in
            guard let self = self else { return }
            self.watchdog?.cancel()
            self.watchdog = nil
            self.metronomeGeneration += 1
            self.metronomeWorkItem?.cancel()
            self.metronomeWorkItem = nil
            self.metronomePlayer.stop()
            self.metronomeRunning = false
            for (_, note) in self.activeNotes {
                self.teardown(note)
            }
            self.activeNotes.removeAll()
            self.engine.stop()
            self.audioFileURLs.removeAll()
            self.metronomeFile = nil
            self.isInitialized = false
            // Clear now-playing and remote command targets
            DispatchQueue.main.async {
                MPNowPlayingInfoCenter.default().nowPlayingInfo = nil
                let cc = MPRemoteCommandCenter.shared()
                cc.stopCommand.removeTarget(nil)
                cc.pauseCommand.removeTarget(nil)
                cc.togglePlayPauseCommand.removeTarget(nil)
            }
            self.remoteCommandsConfigured = false
            print("AudioManager released")
        }
    }
}
