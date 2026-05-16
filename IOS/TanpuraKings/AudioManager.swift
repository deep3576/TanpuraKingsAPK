import Foundation
import AVFoundation
import AudioToolbox
import MediaPlayer

final class AudioManager: NSObject {
    static let shared = AudioManager()

    private let engine = AVAudioEngine()
    private let effectsBus = AVAudioMixerNode()
    private let eqUnit = AVAudioUnitEQ(numberOfBands: 3)
    private let reverbUnit = AVAudioUnitReverb()
    private let echoUnit = AVAudioUnitDelay()
    private let warmthUnit     = AVAudioUnitDistortion()
    private let compressorUnit: AVAudioUnitEffect = {
        var desc = AudioComponentDescription()
        desc.componentType         = kAudioUnitType_Effect
        desc.componentSubType      = kAudioUnitSubType_DynamicsProcessor
        desc.componentManufacturer = kAudioUnitManufacturer_Apple
        desc.componentFlags        = 0
        desc.componentFlagsMask    = 0
        return AVAudioUnitEffect(audioComponentDescription: desc)
    }()

    // Metronome runs on its own player on a separate branch into the main
    // mixer, so it bypasses the reverb/delay/echo chain.
    private let metronomePlayer = AVAudioPlayerNode()
    // Immutable PCM buffer loaded once at startup. Using a buffer instead of
    // a shared AVAudioFile eliminates the data race where file.framePosition=0
    // was called while the engine render thread was still reading from the same
    // file object, silently dropping or corrupting clicks.
    // Guaranteed non-nil after initializeIfNeeded() — falls back to a
    // programmatic sine burst if click.mp3 can't be loaded.
    private var metronomeBuffer: AVAudioPCMBuffer?
    // Self-rescheduling asyncAfter chain that runs on metronomeTimerQueue —
    // a dedicated .userInteractive serial queue that is NEVER blocked by
    // tanpura file loading on the main audio queue.  Each link reads
    // metronomeBPM fresh, so tempo changes take effect at the very next beat
    // without any timer restart.
    private let metronomeTimerQueue = DispatchQueue(
        label: "com.kingsman.tanpurakings.metronome",
        qos: .userInteractive
    )
    // Bumped on every start/stop so stale chain links become no-ops.
    // Not bumped on BPM changes — the chain picks up the new value naturally.
    private var metronomeGeneration: Int = 0
    private var metronomeRunning = false
    private var metronomeBPM: Float = 80
    private var metronomeVolume: Float = 0.7

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
        var subPlayer: AVAudioPlayerNode? = nil
        var subPitch:  AVAudioUnitTimePitch? = nil

        init(player: AVAudioPlayerNode, pitch: AVAudioUnitTimePitch, buffer: AVAudioPCMBuffer, volume: Float) {
            self.player = player
            self.pitch = pitch
            self.buffer = buffer
            self.volume = volume
        }
    }

    private var activeNotes: [String: ActiveNote] = [:]
    // Snapshot saved when pause/stop is triggered from the lock screen so the
    // play button can restore the same set of notes without opening the app.
    // Keyed by note name, value is the per-note volume (0–1).
    private var pausedSnapshot: [String: Float] = [:]
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
    private var subOctaveMix:      Float = 0   // 0 = off, 1 = full sub-octave blend
    private var warmth:            Float = 0   // 0 = dry, 1 = fully warm
    private var compressionAmount: Float = 0   // 0 = bypass, 1 = full compression

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

                // Stop all active note players before restarting the engine.
                for (_, note) in self.activeNotes where !note.isStopping {
                    note.player.stop()
                }

                // Always do a full engine stop+start on every route change.
                // Even when engine.isRunning, a BT route swap causes it to
                // silently output to the disconnected device until the engine
                // is explicitly reset. Stopping first is safe — all nodes
                // remain attached and reconnect automatically on start().
                if self.engine.isRunning { self.engine.stop() }
                self.engine.prepare()
                try self.engine.start()

                // Re-schedule and restart each drone note.
                for (_, note) in self.activeNotes where !note.isStopping {
                    self.scheduleLoopingBuffer(for: note)
                    note.player.play()
                    note.subPlayer?.play()
                }

                // Also restart the metronome if it was running — engine.stop()
                // halts all attached player nodes.  Bump the generation to
                // kill any queued beat work items, then start a fresh chain.
                if self.metronomeRunning {
                    self.metronomeGeneration += 1
                    self.tickMetronome()
                    self.scheduleNextBeat()
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

        echoUnit.delayTime = TimeInterval(echoDelayMs / 1000.0)
        echoUnit.feedback = 97   // 2× previous 85 (capped near 100)
        echoUnit.wetDryMix = 0

        // .multiDistortedFunk is the closest Apple preset to analog tube saturation.
        // At low wetDryMix it adds harmonic richness without harshness.
        warmthUnit.loadFactoryPreset(.multiDistortedFunk)
        warmthUnit.preGain    = -4   // mild attenuation — lets saturation character come through
        warmthUnit.wetDryMix  = 0    // start dry; updated by updateWarmth()

        // Compressor: bypass at startup.
        // Ratio is fixed at 8:1 (param 2) — aggressive enough to be clearly
        // audible as the slider increases. Threshold and makeup gain are set
        // dynamically by updateCompressor().
        AudioUnitSetParameter(compressorUnit.audioUnit, 0,
                              kAudioUnitScope_Global, 0, 0, 0)    // threshold 0 dB (bypass)
        AudioUnitSetParameter(compressorUnit.audioUnit, 1,
                              kAudioUnitScope_Global, 0, 5, 0)    // headroom 5 dB
        AudioUnitSetParameter(compressorUnit.audioUnit, 2,
                              kAudioUnitScope_Global, 0, 8, 0)    // ratio 8:1 — clearly audible
        AudioUnitSetParameter(compressorUnit.audioUnit, 4,
                              kAudioUnitScope_Global, 0, 0.005, 0) // attack 5 ms
        AudioUnitSetParameter(compressorUnit.audioUnit, 5,
                              kAudioUnitScope_Global, 0, 0.15, 0)  // release 150 ms
        AudioUnitSetParameter(compressorUnit.audioUnit, 6,
                              kAudioUnitScope_Global, 0, 0, 0)    // master gain 0 dB

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
        engine.attach(echoUnit)
        engine.attach(metronomePlayer)
        engine.attach(warmthUnit)
        engine.attach(compressorUnit)

        let mainMixer = engine.mainMixerNode

        // Drone path: effectsBus → EQ → Warmth → Compressor → Reverb → Echo → main
        engine.connect(effectsBus,     to: eqUnit,         format: processingFormat)
        engine.connect(eqUnit,         to: warmthUnit,     format: processingFormat)
        engine.connect(warmthUnit,     to: compressorUnit, format: processingFormat)
        engine.connect(compressorUnit, to: reverbUnit,     format: processingFormat)
        engine.connect(reverbUnit,     to: echoUnit,       format: processingFormat)
        engine.connect(echoUnit,       to: mainMixer,      format: processingFormat)
        // Metronome bypasses the effects so the click stays dry.
        engine.connect(metronomePlayer, to: mainMixer, format: processingFormat)

        openAudioFiles()
        loadMetronomeBuffer()

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
        // Run whenever there are active notes OR the metronome is on —
        // both need to be kept alive across BT route changes.
        guard !activeNotes.isEmpty || metronomeRunning else { return }

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

        // Restart any drone note that silently stopped.
        for (_, note) in activeNotes where !note.isStopping {
            if !note.player.isPlaying {
                scheduleLoopingBuffer(for: note)
                note.player.play()
            }
            if let sp = note.subPlayer, !sp.isPlaying {
                note.subPlayer?.scheduleBuffer(note.buffer, at: nil, options: .loops, completionHandler: nil)
                sp.play()
            }
        }

        // If the metronome should be running but the player has gone silent,
        // restart the chain (engine.stop() during a BT route change stops all
        // attached player nodes without firing any callback).
        if metronomeRunning && !metronomePlayer.isPlaying {
            metronomeGeneration += 1
            tickMetronome()
            scheduleNextBeat()
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

    // MARK: - Click buffer helpers

    /// Builds a short sine-burst click entirely in software — no audio file
    /// required.  Used as a guaranteed fallback when click.mp3 can't be loaded.
    /// The result is already in processingFormat, so it schedules directly
    /// without any conversion step.
    private func makeClickBuffer() -> AVAudioPCMBuffer? {
        let sr         = processingFormat.sampleRate
        let durationMs = 55.0                          // ms — natural percussive length
        let frameCount = AVAudioFrameCount(sr * durationMs / 1000.0)
        guard let buf  = AVAudioPCMBuffer(pcmFormat: processingFormat,
                                          frameCapacity: frameCount) else { return nil }
        buf.frameLength = frameCount
        let freq: Double  = 1_200   // Hz — bright, cuts through drone
        let decay: Double = 55.0    // exp decay rate; higher = shorter tail
        let channels = Int(processingFormat.channelCount)
        for ch in 0..<channels {
            guard let ptr = buf.floatChannelData?[ch] else { continue }
            for f in 0..<Int(frameCount) {
                let t = Double(f) / sr
                ptr[f] = Float(exp(-decay * t) * sin(2.0 * .pi * freq * t))
            }
        }
        return buf
    }

    private func loadMetronomeBuffer() {
        // --- Attempt 1: load click.mp3 from the bundle ---
        if let url = Bundle.main.url(forResource: "click", withExtension: "mp3",
                                     subdirectory: "Audio")
                  ?? Bundle.main.url(forResource: "click", withExtension: "mp3") {
            do {
                let file      = try AVAudioFile(forReading: url)
                let srcFmt    = file.processingFormat
                let srcFrames = AVAudioFrameCount(file.length)

                guard let srcBuf = AVAudioPCMBuffer(pcmFormat: srcFmt,
                                                    frameCapacity: srcFrames) else {
                    throw NSError(domain: "AM", code: 1,
                                  userInfo: [NSLocalizedDescriptionKey: "srcBuf alloc failed"])
                }
                try file.read(into: srcBuf)

                if srcFmt == processingFormat {
                    metronomeBuffer = srcBuf
                    print("AudioManager: click.mp3 loaded (formats match — no conversion)")
                    return
                }

                // Mono or different sample rate → convert to processingFormat.
                guard let conv = AVAudioConverter(from: srcFmt, to: processingFormat) else {
                    throw NSError(domain: "AM", code: 2,
                                  userInfo: [NSLocalizedDescriptionKey: "AVAudioConverter init failed"])
                }
                let ratio     = processingFormat.sampleRate / srcFmt.sampleRate
                let dstFrames = AVAudioFrameCount(Double(srcFrames) * ratio) + 64
                guard let dstBuf = AVAudioPCMBuffer(pcmFormat: processingFormat,
                                                    frameCapacity: dstFrames) else {
                    throw NSError(domain: "AM", code: 3,
                                  userInfo: [NSLocalizedDescriptionKey: "dstBuf alloc failed"])
                }
                var fed = false
                let status = conv.convert(to: dstBuf, error: nil) { _, outStatus in
                    guard !fed else { outStatus.pointee = .endOfStream; return nil }
                    fed = true
                    outStatus.pointee = .haveData
                    return srcBuf
                }
                if status != .error, dstBuf.frameLength > 0 {
                    metronomeBuffer = dstBuf
                    print("AudioManager: click.mp3 loaded (\(srcFmt.channelCount)ch→\(processingFormat.channelCount)ch converted)")
                    return
                }
                print("AudioManager: converter status=\(status.rawValue) frameLength=\(dstBuf.frameLength) — falling back to synthetic click")
            } catch {
                print("AudioManager: click.mp3 load error: \(error) — falling back to synthetic click")
            }
        } else {
            print("AudioManager: click.mp3 not found in bundle — using synthetic click")
        }

        // --- Attempt 2: programmatic sine-burst ---
        metronomeBuffer = makeClickBuffer()
        print("AudioManager: Synthetic click buffer \(metronomeBuffer == nil ? "FAILED" : "ready")")
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
        note.subPlayer?.scheduleBuffer(note.buffer, at: nil,
                                       options: .loops, completionHandler: nil)
    }

    func playNote(_ noteName: String, masterVolume: Float, noteVolume: Float = 1.0) {
        queue.async { [weak self] in
            guard let self = self else { return }
            guard self.isInitialized else { return }
            guard self.activeNotes.count < MAX_ACTIVE_NOTES else { return }
            guard self.activeNotes[noteName] == nil else { return }
            // Clear any stale lock-screen snapshot — the user is taking manual
            // control, so the paused widget should not linger after they tap a note.
            self.pausedSnapshot = [:]

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

            // Sub-octave companion — always created, volume = 0 when blend is off.
            let subPlayer = AVAudioPlayerNode()
            let subPitch   = AVAudioUnitTimePitch()
            subPitch.pitch = -1200 + self.fineTuneCents   // −1 octave, tracking fine tune
            self.engine.attach(subPlayer)
            self.engine.attach(subPitch)
            self.engine.connect(subPlayer, to: subPitch,      format: bufferFormat)
            self.engine.connect(subPitch,  to: self.effectsBus, format: bufferFormat)
            note.subPlayer = subPlayer
            note.subPitch  = subPitch
            let subVol = (target * self.subOctaveMix).clamped(to: 0...1)
            subPlayer.volume = subVol
            subPlayer.scheduleBuffer(note.buffer, at: nil, options: .loops, completionHandler: nil)
            subPlayer.play()

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
        note.subPlayer?.stop()
        if let sp = note.subPlayer, let sPitch = note.subPitch {
            engine.disconnectNodeOutput(sPitch)
            engine.disconnectNodeOutput(sp)
            engine.detach(sp)
            engine.detach(sPitch)
        }
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

    func updateOctaveBlend(_ mix: Float) {
        queue.async { [weak self] in
            guard let self = self else { return }
            self.subOctaveMix = mix.clamped(to: 0...1)
            for (_, note) in self.activeNotes where !note.isStopping {
                let baseVol = (self.currentMasterVolume * note.volume).clamped(to: 0...1)
                note.subPlayer?.volume = (baseVol * self.subOctaveMix).clamped(to: 0...1)
            }
        }
    }

    func updateWarmth(_ amount: Float) {
        queue.async { [weak self] in
            guard let self = self else { return }
            self.warmth = amount.clamped(to: 0...1)
            // 60 % max wet — clearly audible warmth without crossing into
            // hard distortion territory on a tanpura drone.
            self.warmthUnit.wetDryMix = amount * 60
        }
    }

    func updateCompressor(_ amount: Float) {
        queue.async { [weak self] in
            guard let self = self else { return }
            self.compressionAmount = amount.clamped(to: 0...1)
            // Threshold sweeps 0 → −30 dB: at full slider almost everything
            // is compressed (tanpura drone sits around −12 to −6 dBFS).
            let threshold  = -amount * 30        // 0 → −30 dB
            // +14 dB makeup gain at full slider — the compression squashes the
            // peaks, the makeup gain restores loudness, making the effect
            // unmistakably audible (punchier, more present sound).
            let masterGain =  amount * 14
            AudioUnitSetParameter(self.compressorUnit.audioUnit,
                                  0, kAudioUnitScope_Global, 0, threshold, 0)
            AudioUnitSetParameter(self.compressorUnit.audioUnit,
                                  6, kAudioUnitScope_Global, 0, Float(masterGain), 0)
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
            // No timer restart needed. The self-rescheduling chain reads
            // metronomeBPM fresh on each link, so the new tempo is applied
            // automatically at the very next beat boundary.
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
            guard let self = self, self.isInitialized else { return }
            guard !self.metronomeRunning else { return }
            guard self.metronomeBuffer != nil else {
                print("AudioManager: startMetronome — buffer nil, cannot start")
                return
            }
            self.metronomeRunning = true
            self.metronomePlayer.volume = self.metronomeVolume
            self.metronomeGeneration += 1
            // Fire the first click immediately, then start the recurring chain.
            self.tickMetronome()
            self.scheduleNextBeat()
        }
    }

    /// Schedules the next beat on `metronomeTimerQueue` (high-priority,
    /// separate from the audio queue) and reads `metronomeBPM` fresh so that
    /// slider changes take effect at the very next beat with no restart.
    ///
    /// Each link in the chain captures the current generation; if
    /// `metronomeGeneration` changes (start/stop) the captured gen no longer
    /// matches and the link silently becomes a no-op, ending the old chain.
    private func scheduleNextBeat() {
        guard metronomeRunning else { return }
        let gen        = metronomeGeneration
        // Read BPM fresh every beat — this is the key that makes slider changes
        // take effect automatically without any timer restart.
        let intervalNs = max(1, Int(60_000_000_000 / Double(metronomeBPM)))

        metronomeTimerQueue.asyncAfter(deadline: .now() + .nanoseconds(intervalNs)) {
            [weak self] in
            guard let self = self, self.metronomeGeneration == gen else { return }
            self.tickMetronome()
            self.scheduleNextBeat()   // recurse with fresh BPM
        }
    }

    func stopMetronome() {
        queue.async { [weak self] in
            guard let self = self else { return }
            // Bump generation first — any queued beat work item that fires
            // after this will see a stale gen and become a silent no-op.
            self.metronomeGeneration += 1
            self.metronomeRunning = false
            self.metronomePlayer.stop()
        }
    }

    /// Schedules one click buffer and ensures the player is running.
    /// Uses only thread-safe AVAudioPlayerNode APIs — safe to call from any
    /// queue, including metronomeTimerQueue.
    private func tickMetronome() {
        guard let buf = metronomeBuffer else { return }
        // Critical order: scheduleBuffer BEFORE play().
        // Calling play() on an idle player with no buffer queued causes it to
        // start rendering silence; when the buffer arrives a few µs later the
        // first frame is already gone, producing a silent or clipped beat.
        metronomePlayer.scheduleBuffer(buf, at: nil, options: [], completionHandler: nil)
        if !metronomePlayer.isPlaying { metronomePlayer.play() }
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
            // Persist which notes were playing so the lock-screen Play button
            // can restore them without the user needing to open the app.
            if !self.activeNotes.isEmpty {
                self.pausedSnapshot = self.activeNotes.mapValues { $0.volume }
            }
            for (_, note) in self.activeNotes {
                self.teardown(note)
            }
            self.activeNotes.removeAll()
            self.updateNowPlayingInfo()
        }
    }

    /// Restore the notes that were playing when pause/stop was last called.
    /// Called by the lock-screen Play and Toggle buttons.
    private func resumeFromSnapshot() {
        let snap = self.pausedSnapshot
        guard !snap.isEmpty else { return }
        self.pausedSnapshot = [:]
        let master = self.currentMasterVolume
        for (name, noteVol) in snap {
            self.playNote(name, masterVolume: master, noteVolume: noteVol)
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
            note.subPlayer?.volume = (vol * self.subOctaveMix).clamped(to: 0...1)
        }
    }

    // Track most-recent master volume for sub-octave blend calculations.
    private var currentMasterVolume: Float = 1.0

    func updateMasterVolume(_ masterVolume: Float) {
        queue.async { [weak self] in
            guard let self = self else { return }
            self.currentMasterVolume = masterVolume
            for (_, note) in self.activeNotes {
                let vol = max(0, min(1, masterVolume * note.volume))
                note.targetVolume = vol
                note.player.volume = vol
                let subVol = (vol * self.subOctaveMix).clamped(to: 0...1)
                note.subPlayer?.volume = subVol
            }
        }
    }

    func updateEffects(
        reverbMix reverbVal: Float,
        fineTune: Float,
        echoMix echoVal: Float,
        echoDelay echoDelayVal: Float
    ) {
        queue.async { [weak self] in
            guard let self = self else { return }

            if fineTune != self.fineTuneCents {
                self.fineTuneCents = fineTune
                for (_, note) in self.activeNotes {
                    note.pitch.pitch = fineTune
                    note.subPitch?.pitch = -1200 + fineTune
                }
            }

            self.reverbMix = reverbVal
            // Square-root curve: makes even a small slider nudge instantly
            // audible rather than needing to reach 50+ before you feel it.
            // Reverb: 2× wetDryMix — reaches full wet at half-slider position
            let reverbT = reverbVal > 0 ? sqrt(reverbVal / 100.0) : 0
            self.reverbUnit.wetDryMix = Float(min(100.0, reverbT * 200.0))

            let echoChanged = echoVal != self.echoMix || echoDelayVal != self.echoDelayMs
            self.echoMix = echoVal
            self.echoDelayMs = echoDelayVal
            self.echoUnit.delayTime = TimeInterval(max(0.05, min(2.0, echoDelayVal / 1000.0)))
            // Echo wetDryMix: 2× — full wet at slider = 0.5
            self.echoUnit.wetDryMix = min(100, echoVal * 200)
            // Feedback 90–98 %: 2× previous 78–97 % (capped at 98 to avoid runaway)
            self.echoUnit.feedback = 90 + echoVal * 8    // 90–98 %

            _ = echoChanged  // suppress unused warning; kept for potential future use
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

        // Stop / Pause → snapshot active notes then stop them
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

        // Play → restore the last snapshot (notes playing before pause/stop)
        cc.playCommand.isEnabled = true
        cc.playCommand.addTarget { [weak self] _ in
            guard let self = self else { return .commandFailed }
            self.queue.async { self.resumeFromSnapshot() }
            return .success
        }

        // Toggle (headphone single-tap, steering-wheel button) → pause/resume
        cc.togglePlayPauseCommand.isEnabled = true
        cc.togglePlayPauseCommand.addTarget { [weak self] _ in
            guard let self = self else { return .commandFailed }
            self.queue.async {
                if self.activeNotes.isEmpty {
                    self.resumeFromSnapshot()
                } else {
                    self.stopAllNotes()
                }
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
        // Capture all audio-queue values here before crossing to the main queue.
        // Accessing self.pausedSnapshot inside DispatchQueue.main.async would be
        // a data race — the audio queue can mutate it while the main queue reads it.
        let names          = activeNotes.keys.sorted()
        let playing        = !names.isEmpty
        let snapshotNames  = pausedSnapshot.keys.sorted()   // captured on audio queue ✓
        let hasSnapshot    = !snapshotNames.isEmpty

        DispatchQueue.main.async {
            // Notify CarPlaySceneDelegate (and any other observer) that the
            // active-notes set has changed so it can refresh its browse list.
            NotificationCenter.default.post(
                name: Notification.Name("tanpuraActiveNotesChanged"),
                object: nil
            )

            // Remove the widget only when nothing is playing AND there is
            // nothing to resume.
            guard playing || hasSnapshot else {
                MPNowPlayingInfoCenter.default().nowPlayingInfo = nil
                return
            }
            var info: [String: Any] = [:]
            info[MPMediaItemPropertyTitle]                    = "Tanpura Kings"
            // Show last-played notes even when paused so the user knows what
            // will resume. Use the captured copies — no self access needed.
            let displayNames = playing ? names : snapshotNames
            info[MPMediaItemPropertyArtist]                   = displayNames.joined(separator: " • ")
            info[MPMediaItemPropertyAlbumTitle]               = "Tanpura Drone"
            // playbackRate 0 = paused (shows Play button); 1 = playing (shows Pause)
            info[MPNowPlayingInfoPropertyPlaybackRate]        = playing ? Float(1.0) : Float(0.0)
            info[MPNowPlayingInfoPropertyElapsedPlaybackTime] = Double(0)
            // Use a 24-hour duration so the lock screen shows no progress bar
            info[MPMediaItemPropertyPlaybackDuration]         = Double(86_400)
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
            // Bump generation before clearing running flag so any queued beat
            // work item that fires during teardown sees a stale gen and bails.
            self.metronomeGeneration += 1
            self.metronomeRunning = false
            self.metronomePlayer.stop()
            for (_, note) in self.activeNotes {
                self.teardown(note)
            }
            self.activeNotes.removeAll()
            self.engine.stop()
            self.audioFileURLs.removeAll()
            self.metronomeBuffer = nil
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

private extension Float {
    func clamped(to range: ClosedRange<Float>) -> Float {
        min(max(self, range.lowerBound), range.upperBound)
    }
}
