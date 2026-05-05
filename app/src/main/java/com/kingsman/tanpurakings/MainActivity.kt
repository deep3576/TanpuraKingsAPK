package com.kingsman.tanpurakings

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.media.SoundPool
import android.media.audiofx.EnvironmentalReverb
import android.media.audiofx.Equalizer
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import androidx.compose.material3.Switch
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.Manifest
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder as AndroidMediaRecorder
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val MAX_ACTIVE_NOTES = 3
private const val CROSSFADE_MS = 600L   // overlap window between old and new player
private const val CROSSFADE_STEPS = 30  // volume steps during the fade
private const val NOTE_FADE_MS = 350L   // fade-in / fade-out for tap on/off (Sa change)
private const val NOTE_FADE_STEPS = 20

// ------------------------------
// AudioManager
// ------------------------------
object AudioManager {
    private var appContext: Context? = null

    // One MediaPlayer per active note — provides proper seamless looping via crossfade
    private val activePlayers = mutableMapOf<String, MediaPlayer>()
    private val loopJobs     = mutableMapOf<String, Job>()
    private val noteVolumes  = mutableMapOf<String, Float>()

    // SoundPool used only for transient echo/delay/reverb copies
    private lateinit var soundPool: SoundPool
    private val noteSoundIds  = ConcurrentHashMap<String, Int>()
    private val noteDurations = ConcurrentHashMap<String, Long>()   // ms, fetched async

    private val echoJobs       = mutableMapOf<String, Job>()
    // Extra looping MediaPlayer instances created for delay/echo repeats.
    // Keyed by note name; released alongside their parent note.
    private val effectPlayers  = mutableMapOf<String, MutableList<MediaPlayer>>()
    // Hardware reverb (global audio session). Null when the device doesn't support it.
    private var envReverb: EnvironmentalReverb? = null
    var isInitialized = false
    private var coroutineScope: CoroutineScope? = null

    // System-level audio plumbing for Bluetooth/route handling
    private var sysAudioManager: android.media.AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null
    private var deviceCallback: AudioDeviceCallback? = null

    // Last-known master volume so we can restart notes after a route change
    // (callers pass it per-note; we cache the most recent value here).
    private var currentMasterVolume: Float = 1f

    // Audio focus state. When another app grabs focus (transient — e.g. a
    // notification, navigation prompt, voice assistant, brief sound from a
    // background app) we tear down our active notes and snapshot them. When
    // focus comes back we replay the snapshot at their previous per-note
    // volumes — that's the "doesn't auto-resume after foreign sound stops"
    // bug fix.
    @Volatile private var pausedForFocus: Boolean = false
    private var pausedNotesSnapshot: Map<String, Float> = emptyMap()

    // Effect parameters
    private var fineTuneCents: Float = 0f
    private var reverbMix:     Float = 0f
    private var echoMix:       Float = 0f
    private var echoDelayMs:   Float = 300f
    private var delayMix:      Float = 0f
    private var delayTimeMs:   Float = 500f

    // 3-band EQ state (gain in dB, -12..+12). Per-MediaPlayer Equalizer objects
    // are attached on creation and tracked here for cleanup.
    private var eqLowDb:  Float = 0f
    private var eqMidDb:  Float = 0f
    private var eqHighDb: Float = 0f
    private val equalizers = mutableMapOf<String, Equalizer>()

    // Stereo width: 0.0 = all notes panned center, 1.0 = full spread.
    private var stereoWidth: Float = 0.5f

    // Metronome
    private var clickSoundId: Int = 0
    private var metronomeBPM:    Float = 80f
    private var metronomeVolume: Float = 0.7f
    @Volatile private var metronomeRunning = false
    private var metronomeJob: Job? = null

    fun init(context: Context) {
        if (isInitialized) return
        appContext     = context.applicationContext
        coroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

        soundPool = SoundPool.Builder()
            .setMaxStreams(12)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            ).build()

        // Grab the system AudioManager and:
        //  1. Request AUDIOFOCUS_GAIN. Without this, our playback is
        //     "secondary" and the OS will not engage an already-connected
        //     Bluetooth A2DP route until some other app starts primary
        //     audio. That was causing the silent-until-YouTube-plays bug.
        //  2. Register a callback so we can re-route active notes when the
        //     user plugs/unplugs a BT speaker mid-playback (otherwise
        //     MediaPlayer keeps rendering to the old route).
        val sys = context.applicationContext
            .getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        sysAudioManager = sys
        requestAudioFocusInternal()

        val cb = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
                if (addedDevices?.any { it.isSink } == true) refreshActiveNotesOnRouteChange()
            }
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
                if (removedDevices?.any { it.isSink } == true) refreshActiveNotesOnRouteChange()
            }
        }
        sys.registerAudioDeviceCallback(cb, null)
        deviceCallback = cb

        // Global EnvironmentalReverb — audioSession=0 applies to all app audio.
        // Hardware-accelerated; may be unavailable on some devices (caught silently).
        try {
            envReverb = EnvironmentalReverb(0, 0).apply {
                roomLevel         = (-1500).toShort()  // -9000..0 mB
                roomHFLevel       = (-800).toShort()   // -9000..0 mB
                decayTime         = 3800               // 100-20000 ms — large hall
                decayHFRatio      = 700.toShort()      // 100-2000
                reflectionsLevel  = (-2600).toShort()  // -9000..1000 mB
                reflectionsDelay  = 25                 // 0-300 ms
                reverbLevel       = (-1200).toShort()  // -9000..2000 mB
                reverbDelay       = 40                 // 0-100 ms
                diffusion         = 1000.toShort()     // 0-1000
                density           = 1000.toShort()     // 0-1000
                enabled           = false
            }
        } catch (e: Exception) {
            Log.w("AudioManager", "EnvironmentalReverb unavailable: $e")
        }

        isInitialized = true

        // Load sound IDs + durations on IO so init() returns instantly
        coroutineScope?.launch(Dispatchers.IO) {
            val keys = listOf("c","csharp","d","dsharp","e","f","fsharp","g","gsharp","a","asharp","b")
            val retriever = MediaMetadataRetriever()
            for (key in keys) {
                try {
                    val afd = context.applicationContext.assets.openFd("Audio/$key.mp3")
                    noteSoundIds[key] = soundPool.load(afd, 1)
                    retriever.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                        ?.toLongOrNull()
                        ?.takeIf { it > 0 }
                        ?.let { noteDurations[key] = it }
                    afd.close()
                } catch (e: Exception) {
                    Log.e("AudioManager", "Load error: $key", e)
                }
            }
            // Load metronome click into the same SoundPool.
            try {
                val afd = context.applicationContext.assets.openFd("Audio/click.mp3")
                clickSoundId = soundPool.load(afd, 1)
                afd.close()
            } catch (e: Exception) {
                Log.e("AudioManager", "Load error: click", e)
            }
            retriever.release()
            Log.d("AudioManager", "Ready. Durations: $noteDurations")
        }
    }

    private fun requestAudioFocusInternal() {
        val sys = sysAudioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
            val req = AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attrs)
                .setAcceptsDelayedFocusGain(true)
                .setOnAudioFocusChangeListener { change -> handleAudioFocusChange(change) }
                .build()
            focusRequest = req
            sys.requestAudioFocus(req)
        } else {
            @Suppress("DEPRECATION")
            sys.requestAudioFocus(
                { change -> handleAudioFocusChange(change) },
                android.media.AudioManager.STREAM_MUSIC,
                android.media.AudioManager.AUDIOFOCUS_GAIN
            )
        }
    }

    // Audio focus arbitration. Triggers when another app starts/stops
    // primary audio output — phone calls, navigation prompts, BT handoff.
    //
    //  - LOSS_TRANSIENT: another app needs focus briefly. Pause + snapshot.
    //    GAIN will replay the snapshot when it releases.
    //
    //  - LOSS (permanent): Android fires this — NOT LOSS_TRANSIENT — when a
    //    second phone takes over a shared Bluetooth speaker. We save the
    //    snapshot here too instead of clearing it, so that when the remote
    //    device disconnects and onAudioDevicesAdded fires (route change),
    //    refreshActiveNotesOnRouteChange can restore the drone automatically.
    //
    //  - LOSS_TRANSIENT_CAN_DUCK: a notification chime etc. Keep playing.
    //
    //  - GAIN: replay the snapshot regardless of which LOSS variant fired.
    private fun handleAudioFocusChange(change: Int) {
        val scope = coroutineScope ?: return
        when (change) {
            android.media.AudioManager.AUDIOFOCUS_GAIN -> {
                // Replay any saved snapshot — covers both LOSS_TRANSIENT and
                // LOSS (BT handoff) cases.
                val snap = pausedNotesSnapshot.toMap()
                pausedForFocus = false
                pausedNotesSnapshot = emptyMap()
                if (snap.isEmpty()) return
                scope.launch {
                    delay(120) // let the foreign app's render-tail clear
                    val master = currentMasterVolume
                    snap.forEach { (name, nv) -> playNote(name, master, nv) }
                }
            }
            android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                if (pausedForFocus) return
                if (activePlayers.isEmpty()) return
                pausedForFocus = true
                pausedNotesSnapshot = HashMap(noteVolumes)
                stopAllNotes()
            }
            android.media.AudioManager.AUDIOFOCUS_LOSS -> {
                // Save snapshot (don't clear it) so BT reconnect can restore.
                // pausedForFocus = false means we don't expect GAIN from this
                // specific cause, but GAIN is still consumed above if it arrives.
                if (activePlayers.isNotEmpty() && pausedNotesSnapshot.isEmpty()) {
                    pausedNotesSnapshot = HashMap(noteVolumes)
                }
                pausedForFocus = false
                if (activePlayers.isNotEmpty()) stopAllNotes()
            }
            android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                // Notification chime etc. — drone is a steady tone, keep playing.
            }
        }
    }

    // Output device added/removed (BT speaker connect/disconnect, headphones, etc.).
    // MediaPlayer doesn't auto-migrate to the new route reliably, so we snapshot
    // the current notes, stop them, re-claim audio focus, and replay.
    //
    // Key BT multi-device fix: when Device B had taken the speaker and we had
    // already stopped all notes (activePlayers is empty), we check whether a
    // saved snapshot exists from the earlier AUDIOFOCUS_LOSS and replay that
    // instead of silently doing nothing.
    private fun refreshActiveNotesOnRouteChange() {
        val scope = coroutineScope ?: return
        scope.launch {
            val liveSnapshot   = activePlayers.keys.toList()
            val liveVolumes    = HashMap(noteVolumes)
            val savedSnapshot  = pausedNotesSnapshot.toMap()

            if (liveSnapshot.isEmpty() && savedSnapshot.isEmpty()) {
                // No notes to restore — just re-request focus so the new
                // route is engaged as the primary output.
                requestAudioFocusInternal()
                return@launch
            }

            // Stop whatever is still live (may be empty if notes were already
            // stopped by AUDIOFOCUS_LOSS when Device B took the speaker).
            liveSnapshot.forEach { stopNote(it) }
            requestAudioFocusInternal()

            // Give the OS time to finish the A2DP route handoff.
            delay(150)

            val master = currentMasterVolume
            if (liveSnapshot.isNotEmpty()) {
                // Normal route-change restart: replay what was live.
                liveSnapshot.forEach { name ->
                    val nv = liveVolumes[name] ?: 1f
                    playNote(name, master, nv)
                }
            } else {
                // Device B has gone — restore the snapshot saved when it took
                // the speaker so the drone resumes without any user interaction.
                pausedNotesSnapshot = emptyMap()
                pausedForFocus      = false
                savedSnapshot.forEach { (name, nv) -> playNote(name, master, nv) }
            }
        }
    }

    private fun fineTuneRate(cents: Float): Float =
        2.0.pow(cents / 1200.0).toFloat().coerceIn(0.5f, 2.0f)

    // ---------- 3-band EQ ----------

    private fun attachEqualizer(player: MediaPlayer, noteName: String) {
        try {
            val eq = Equalizer(0, player.audioSessionId)
            eq.enabled = true
            applyEqLevels(eq)
            equalizers[noteName] = eq
        } catch (t: Throwable) {
            Log.w("AudioManager", "Equalizer attach failed: $t")
        }
    }

    private fun applyEqLevels(eq: Equalizer) {
        val numBands = eq.numberOfBands.toInt()
        if (numBands == 0) return
        // Map our 3 fixed bands onto the device's nearest center frequencies.
        // Equalizer reports center freqs in milliHertz.
        val targetsHz   = intArrayOf(100, 1000, 8000)
        val gainsDb     = floatArrayOf(eqLowDb, eqMidDb, eqHighDb)
        for (i in targetsHz.indices) {
            val targetMhz = targetsHz[i] * 1000
            var bestBand = 0
            var bestDist = Int.MAX_VALUE
            for (b in 0 until numBands) {
                val center = eq.getCenterFreq(b.toShort())
                val d = kotlin.math.abs(center - targetMhz)
                if (d < bestDist) { bestDist = d; bestBand = b }
            }
            // millibels (1 dB = 100 mB), clamped to ±15 dB which every device supports.
            val mb = (gainsDb[i] * 100).toInt().coerceIn(-1500, 1500)
            try { eq.setBandLevel(bestBand.toShort(), mb.toShort()) } catch (_: Throwable) {}
        }
    }

    fun updateEQ(low: Float, mid: Float, high: Float) {
        eqLowDb  = low.coerceIn(-12f, 12f)
        eqMidDb  = mid.coerceIn(-12f, 12f)
        eqHighDb = high.coerceIn(-12f, 12f)
        equalizers.values.forEach { runCatching { applyEqLevels(it) } }
    }

    // ---------- Stereo width ----------

    private fun panToLR(pan: Float, vol: Float): Pair<Float, Float> {
        // Equal-power pan curve. pan in [-1, +1].
        val angle = (pan.coerceIn(-1f, 1f) + 1f) * (Math.PI / 4).toFloat() // 0..PI/2
        val l = kotlin.math.cos(angle.toDouble()).toFloat() * vol * 1.4142135f
        val r = kotlin.math.sin(angle.toDouble()).toFloat() * vol * 1.4142135f
        return Pair(l.coerceIn(0f, 1f), r.coerceIn(0f, 1f))
    }

    private fun reapplyStereoSpread() {
        val sortedNames = activePlayers.keys.sorted()
        val n = sortedNames.size
        if (n == 0) return
        if (n == 1) {
            val name = sortedNames[0]
            val nv = noteVolumes[name] ?: 1f
            val v = (currentMasterVolume * nv).coerceIn(0f, 1f)
            activePlayers[name]?.runCatching { setVolume(v, v) }
            return
        }
        val step = (2f * stereoWidth) / (n - 1)
        for ((i, name) in sortedNames.withIndex()) {
            val pan  = -stereoWidth + step * i
            val nv   = noteVolumes[name] ?: 1f
            val base = (currentMasterVolume * nv).coerceIn(0f, 1f)
            val (l, r) = panToLR(pan, base)
            activePlayers[name]?.runCatching { setVolume(l, r) }
        }
    }

    fun updateStereoWidth(width: Float) {
        stereoWidth = width.coerceIn(0f, 1f)
        reapplyStereoSpread()
    }

    // ---------- Metronome ----------

    fun startMetronome() {
        if (metronomeRunning) return
        metronomeRunning = true
        metronomeJob = coroutineScope?.launch {
            while (metronomeRunning) {
                if (clickSoundId != 0 && ::soundPool.isInitialized) {
                    val v = metronomeVolume.coerceIn(0f, 1f)
                    runCatching { soundPool.play(clickSoundId, v, v, 1, 0, 1f) }
                }
                val intervalMs = (60_000f / metronomeBPM.coerceAtLeast(1f)).toLong()
                delay(intervalMs)
            }
        }
    }

    fun stopMetronome() {
        metronomeRunning = false
        metronomeJob?.cancel()
        metronomeJob = null
    }

    fun setMetronomeBPM(bpm: Float)        { metronomeBPM    = bpm.coerceIn(40f, 240f) }
    fun setMetronomeVolume(volume: Float)  { metronomeVolume = volume.coerceIn(0f, 1f) }

    // Creates and prepares a MediaPlayer on the IO thread. Returns ready-to-start player.
    private suspend fun buildPlayer(fileKey: String, volume: Float, pitch: Float): MediaPlayer? =
        withContext(Dispatchers.IO) {
            try {
                val ctx = appContext ?: return@withContext null
                val afd = ctx.assets.openFd("Audio/$fileKey.mp3")
                MediaPlayer().apply {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    setWakeMode(ctx, PowerManager.PARTIAL_WAKE_LOCK)
                    setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                    afd.close()
                    setVolume(volume, volume)
                    prepare()
                    if (pitch != 1f) runCatching {
                        playbackParams = PlaybackParams().setSpeed(1.0f).setPitch(pitch)
                    }
                }
            } catch (e: Exception) {
                Log.e("AudioManager", "buildPlayer error: $fileKey", e)
                null
            }
        }

    fun playNote(noteName: String, masterVolume: Float, noteVolume: Float = 1f) {
        if (activePlayers.size >= MAX_ACTIVE_NOTES) return
        currentMasterVolume = masterVolume
        val fileKey = noteName.lowercase().replace("#", "sharp")
        val volume  = (masterVolume * noteVolume).coerceIn(0f, 1f)

        coroutineScope?.launch {
            val pitch  = fineTuneRate(fineTuneCents)
            // Build the player at zero volume so we can fade in cleanly
            // (avoids a click/pop on tap-on, gives crossfade-feel when this
            // note is replacing another one the user just tapped off).
            val player = buildPlayer(fileKey, 0f, pitch) ?: return@launch

            player.start()
            activePlayers[noteName] = player
            noteVolumes[noteName]   = noteVolume

            // Attach a per-MediaPlayer Equalizer so EQ is applied to this note.
            attachEqualizer(player, noteName)

            // Fade the note in from 0 → target, then re-apply stereo spread
            // (which sets the proper L/R balance based on width + position).
            launch {
                val stepMs = NOTE_FADE_MS / NOTE_FADE_STEPS
                for (i in 1..NOTE_FADE_STEPS) {
                    val alpha = i.toFloat() / NOTE_FADE_STEPS
                    val v = volume * alpha
                    runCatching { player.setVolume(v, v) }
                    delay(stepMs)
                }
                reapplyStereoSpread()
            }

            val duration = noteDurations[fileKey]
            if (duration != null && duration > CROSSFADE_MS * 2) {
                loopJobs[noteName] = launch { crossfadeLoop(fileKey, noteName, volume, duration) }
            } else {
                // Duration unknown yet or very short — fall back to built-in looping
                player.isLooping = true
            }

            // Persistent delay/echo via real looping MediaPlayer instances.
            // (Reverb is handled globally by EnvironmentalReverb, no per-note work needed.)
            launchEffectPlayers(noteName, fileKey, volume, pitch)
        }
    }

    // Crossfade loop: starts the next player CROSSFADE_MS before the current one ends,
    // then smoothly hands volume over so there is never silence at the loop boundary.
    private suspend fun crossfadeLoop(
        fileKey: String, noteName: String, volume: Float, durationMs: Long
    ) {
        // delay() throws CancellationException when the job is cancelled — no isActive needed
        val waitBeforeFade = (durationMs - CROSSFADE_MS).coerceAtLeast(200L)
        while (true) {
            delay(waitBeforeFade)

            val pitch = fineTuneRate(fineTuneCents)

            val nextPlayer = buildPlayer(fileKey, 0f, pitch) ?: break

            val curPlayer = activePlayers[noteName]
            if (curPlayer == null) {
                nextPlayer.release()
                break
            }

            nextPlayer.start()
            val stepMs = CROSSFADE_MS / CROSSFADE_STEPS
            for (step in 1..CROSSFADE_STEPS) {
                val alpha = step.toFloat() / CROSSFADE_STEPS
                curPlayer.setVolume(volume * (1f - alpha), volume * (1f - alpha))
                nextPlayer.setVolume(volume * alpha, volume * alpha)
                delay(stepMs)
            }

            activePlayers[noteName] = nextPlayer
            runCatching { curPlayer.stop() }
            curPlayer.release()
        }
    }

    fun updateNoteVolume(noteName: String, noteVolume: Float, masterVolume: Float) {
        currentMasterVolume = masterVolume
        noteVolumes[noteName] = noteVolume
        // Re-apply spread so the pan stays correct as we change just one note's volume.
        reapplyStereoSpread()
    }

    fun updateMasterVolume(masterVolume: Float) {
        currentMasterVolume = masterVolume
        reapplyStereoSpread()
    }

    fun stopNote(noteName: String) {
        val player = activePlayers.remove(noteName) ?: return
        loopJobs.remove(noteName)?.cancel()
        echoJobs.remove(noteName)?.cancel()
        effectPlayers.remove(noteName)?.forEach { ep ->
            runCatching { ep.stop() }; ep.release()
        }
        val eq = equalizers.remove(noteName)
        val nv = noteVolumes.remove(noteName) ?: 1f
        val from = (currentMasterVolume * nv).coerceIn(0f, 1f)

        val scope = coroutineScope
        if (scope == null) {
            // Sync fallback (shouldn't happen during normal lifecycle).
            eq?.runCatching { release() }
            runCatching { player.stop() }; player.release()
            return
        }
        scope.launch {
            val stepMs = NOTE_FADE_MS / NOTE_FADE_STEPS
            for (i in 1..NOTE_FADE_STEPS) {
                val alpha = i.toFloat() / NOTE_FADE_STEPS
                val v = from * (1f - alpha)
                runCatching { player.setVolume(v, v) }
                delay(stepMs)
            }
            eq?.runCatching { release() }
            runCatching { player.stop() }
            player.release()
        }
        // Re-spread the remaining notes immediately so their pan rebalances
        // as soon as the user taps off — no need to wait for the fade to finish.
        reapplyStereoSpread()
    }

    fun stopAllNotes() {
        loopJobs.values.forEach { it.cancel() };  loopJobs.clear()
        echoJobs.values.forEach { it.cancel() };  echoJobs.clear()
        effectPlayers.values.forEach { list ->
            list.forEach { ep -> runCatching { ep.stop() }; ep.release() }
        };  effectPlayers.clear()
        equalizers.values.forEach { runCatching { it.release() } };  equalizers.clear()
        activePlayers.values.forEach { runCatching { it.stop() }; it.release() }
        activePlayers.clear(); noteVolumes.clear()
    }

    fun updateEffects(
        reverbMixVal: Float, fineTune: Float,
        echoMixVal: Float, echoDelayVal: Float,
        delayMixVal: Float, delayTimeVal: Float
    ) {
        if (fineTune != fineTuneCents) {
            fineTuneCents = fineTune
            val pitch = fineTuneRate(fineTune)
            activePlayers.values.forEach { player ->
                runCatching { player.playbackParams = PlaybackParams().setSpeed(1.0f).setPitch(pitch) }
            }
        }

        // Update reverb immediately via hardware effect.
        applyReverbLevel(reverbMixVal)
        reverbMix = reverbMixVal

        val echoChanged  = echoMixVal  != echoMix  || echoDelayVal  != echoDelayMs
        val delayChanged = delayMixVal != delayMix || delayTimeVal  != delayTimeMs
        echoMix     = echoMixVal
        echoDelayMs = echoDelayVal
        delayMix    = delayMixVal
        delayTimeMs = delayTimeVal

        // Restart delay/echo players for all active notes when parameters change.
        if ((echoChanged || delayChanged) && activePlayers.isNotEmpty()) {
            val scope = coroutineScope ?: return
            activePlayers.keys.toList().forEach { noteName ->
                echoJobs.remove(noteName)?.cancel()
                effectPlayers.remove(noteName)?.forEach { ep ->
                    runCatching { ep.stop() }; ep.release()
                }
                val fileKey = noteName.lowercase().replace("#", "sharp")
                val nv   = noteVolumes[noteName] ?: 1f
                val vol  = (currentMasterVolume * nv).coerceIn(0f, 1f)
                val pitch = fineTuneRate(fineTuneCents)
                scope.launchEffectPlayers(noteName, fileKey, vol, pitch)
            }
        }
    }

    // Maps mix 0-100 to EnvironmentalReverb parameters with a sqrt curve so
    // mid-range settings are clearly audible, not buried in the noise floor.
    private fun applyReverbLevel(mix: Float) {
        val er = envReverb ?: return
        if (mix <= 0f) { runCatching { er.enabled = false }; return }
        runCatching {
            val t = sqrt(mix / 100f)                             // 0..1, perceptually linear
            er.roomLevel   = ((-9000f + t * 8500f).toInt()).coerceIn(-9000, 0).toShort()
            er.reverbLevel = ((-5000f + t * 7000f).toInt()).coerceIn(-9000, 2000).toShort()
            er.enabled     = true
        }
    }

    // Launches coroutines that create real looping MediaPlayer instances for
    // each echo/delay tap. These persist until the note is stopped so the
    // effect is heard for the full lifetime of the drone, not just at onset.
    private fun CoroutineScope.launchEffectPlayers(
        noteName: String, fileKey: String, volume: Float, pitch: Float
    ) {
        if (echoMix <= 0f && delayMix <= 0f) return
        echoJobs[noteName] = launch {
            // Echo: up to 4 decaying looping copies, each offset by echoDelayMs.
            if (echoMix > 0f) launch {
                var tapVol  = (volume * echoMix.coerceAtMost(1f)).coerceIn(0f, 1f)
                var tapNum  = 0
                while (tapNum < 4 && tapVol >= 0.04f) {
                    delay(echoDelayMs.toLong())
                    if (!isActive) break
                    val ep = buildPlayer(fileKey, tapVol, pitch) ?: break
                    ep.isLooping = true
                    ep.start()
                    effectPlayers.getOrPut(noteName) { mutableListOf() }.add(ep)
                    tapVol *= 0.50f
                    tapNum++
                }
            }
            // Delay: a single looping copy that starts after delayTimeMs.
            if (delayMix > 0f) launch {
                delay(delayTimeMs.toLong())
                if (!isActive) return@launch
                val tapVol = (volume * delayMix).coerceIn(0f, 1f)
                val ep = buildPlayer(fileKey, tapVol, pitch) ?: return@launch
                ep.isLooping = true
                ep.start()
                effectPlayers.getOrPut(noteName) { mutableListOf() }.add(ep)
            }
        }
    }

    fun release() {
        stopMetronome()
        stopAllNotes()
        coroutineScope?.cancel()
        coroutineScope = null

        deviceCallback?.let { sysAudioManager?.unregisterAudioDeviceCallback(it) }
        deviceCallback = null

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { sysAudioManager?.abandonAudioFocusRequest(it) }
            focusRequest = null
        } else {
            @Suppress("DEPRECATION")
            sysAudioManager?.abandonAudioFocus(null)
        }
        sysAudioManager = null

        runCatching { envReverb?.release() }; envReverb = null
        if (::soundPool.isInitialized) soundPool.release()
        noteSoundIds.clear(); noteDurations.clear()
        appContext    = null
        isInitialized = false
        Log.d("AudioManager", "AudioManager released")
    }
}

// ------------------------------
// TunerManager — real-time chromatic pitch detector
// ------------------------------
object TunerManager {
    private val _frequency   = MutableStateFlow(0f)
    private val _noteName    = MutableStateFlow("—")
    private val _cents       = MutableStateFlow(0f)
    private val _isListening = MutableStateFlow(false)
    private val _inputLevel  = MutableStateFlow(0f)
    private val _detected    = MutableStateFlow(false)

    val frequency:   StateFlow<Float>   = _frequency.asStateFlow()
    val noteName:    StateFlow<String>  = _noteName.asStateFlow()
    val cents:       StateFlow<Float>   = _cents.asStateFlow()
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()
    val inputLevel:  StateFlow<Float>   = _inputLevel.asStateFlow()
    val detected:    StateFlow<Boolean> = _detected.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var audioRecord: AudioRecord? = null
    private var processingJob: Job? = null

    private const val SAMPLE_RATE = 44100
    private const val FRAME_SIZE  = 2048

    fun start() {
        if (_isListening.value) return
        val minBuf  = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT
        )
        val bufSize = maxOf(minBuf, FRAME_SIZE * 4)
        val record  = AudioRecord(
            AndroidMediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_FLOAT,
            bufSize
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            Log.e("TunerManager", "AudioRecord init failed")
            return
        }
        audioRecord = record
        record.startRecording()
        _isListening.value = true

        processingJob = scope.launch {
            val buf = FloatArray(FRAME_SIZE)
            while (isActive) {
                val read = record.read(buf, 0, FRAME_SIZE, AudioRecord.READ_BLOCKING)
                if (read > 0) process(buf, read)
            }
        }
    }

    fun stop() {
        processingJob?.cancel()
        processingJob = null
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord      = null
        _isListening.value = false
        _detected.value    = false
        _inputLevel.value  = 0f
        _frequency.value   = 0f
        _cents.value       = 0f
        _noteName.value    = "—"
    }

    private fun process(samples: FloatArray, count: Int) {
        var sumSq = 0f
        for (i in 0 until count) sumSq += samples[i] * samples[i]
        val rms   = sqrt(sumSq / count)
        val level = minOf(1f, rms * 5f)

        if (rms < 0.005f) {
            _inputLevel.value = level; _detected.value = false; return
        }

        // Autocorrelation pitch detection (same algorithm as iOS TunerManager)
        val minLag   = maxOf(2, SAMPLE_RATE / 1000)
        val maxLag   = minOf(count - 1, SAMPLE_RATE / 60)
        if (maxLag <= minLag + 4) return

        val zeroLag  = sumSq
        val corrSize = maxLag - minLag + 1
        val corr     = FloatArray(corrSize)
        for (k in 0 until corrSize) {
            val lag = k + minLag; var s = 0f
            for (i in 0 until count - lag) s += samples[i] * samples[i + lag]
            corr[k] = s
        }

        val threshold = zeroLag * 0.3f
        var passedDip = false; var bestIdx = -1; var bestCorr = 0f; var k = 1
        while (k < corrSize - 1) {
            val c = corr[k]
            if (!passedDip) { if (c < threshold) passedDip = true }
            else if (c > corr[k - 1] && c > corr[k + 1]) {
                if (c > bestCorr) { bestCorr = c; bestIdx = k }
                if (c > zeroLag * 0.5f) break
            }
            k++
        }
        if (bestIdx < 0) { _inputLevel.value = level; _detected.value = false; return }

        // Parabolic interpolation for sub-sample accuracy
        val yL    = corr[maxOf(0, bestIdx - 1)]
        val yP    = corr[bestIdx]
        val yR    = corr[minOf(corrSize - 1, bestIdx + 1)]
        val denom = yL - 2 * yP + yR
        val shift = if (denom == 0f) 0f else 0.5f * (yL - yR) / denom
        val lag   = (bestIdx + minLag).toFloat() + shift
        if (lag <= 0) return

        val freq    = SAMPLE_RATE.toFloat() / lag
        val clarity = if (zeroLag == 0f) 0f else bestCorr / zeroLag
        if (!freq.isFinite() || freq < 50f || freq > 1500f || clarity < 0.3f) {
            _inputLevel.value = level; _detected.value = false; return
        }

        val (note, centsVal) = noteAndCents(freq.toDouble())
        val alpha = 0.6f
        val smoothFreq  = if (_frequency.value == 0f) freq
            else _frequency.value * alpha + freq * (1f - alpha)
        val smoothCents = _cents.value * alpha + centsVal.toFloat() * (1f - alpha)

        _frequency.value  = smoothFreq
        _cents.value      = smoothCents
        _noteName.value   = note
        _inputLevel.value = level
        _detected.value   = true
    }

    private fun noteAndCents(freq: Double): Pair<String, Double> {
        val names   = arrayOf("C","C#","D","D#","E","F","F#","G","G#","A","A#","B")
        val semis   = 12.0 * log2(freq / 440.0) + 69.0
        val nearest = round(semis)
        val cents   = (semis - nearest) * 100.0
        val midi    = nearest.toInt()
        val octave  = midi / 12 - 1
        val idx     = ((midi % 12) + 12) % 12
        return Pair("${names[idx]}$octave", cents)
    }
}

// ------------------------------
// Data model
// ------------------------------
data class PianoKey(val name: String, val isSharp: Boolean)

val octaveKeys = listOf(
    PianoKey("C", false), PianoKey("C#", true),
    PianoKey("D", false), PianoKey("D#", true),
    PianoKey("E", false), PianoKey("F",  false),
    PianoKey("F#", true), PianoKey("G",  false),
    PianoKey("G#", true), PianoKey("A",  false),
    PianoKey("A#", true), PianoKey("B",  false)
)

// ------------------------------
// PianoView
// ------------------------------
@Suppress("UnusedBoxWithConstraintsScope")
@Composable
fun PianoView(
    activeNotes: MutableState<Set<String>>,
    activeNoteVolumes: MutableState<Map<String, Float>>,
    masterVolume: Float
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(200.dp)
            .background(Color.LightGray)
    ) {
        val whiteKeys    = octaveKeys.filter { !it.isSharp }
        val whiteKeyWidth = maxWidth / whiteKeys.size

        Row(modifier = Modifier.fillMaxSize()) {
            whiteKeys.forEach { key ->
                Box(
                    modifier = Modifier
                        .width(whiteKeyWidth)
                        .fillMaxHeight()
                        .background(if (activeNotes.value.contains(key.name)) Color.Yellow else Color.White)
                        .border(1.dp, Color.Black)
                        .clickable {
                            if (activeNotes.value.contains(key.name)) {
                                AudioManager.stopNote(key.name)
                                activeNotes.value -= key.name
                                activeNoteVolumes.value -= key.name
                            } else {
                                if (activeNotes.value.size >= MAX_ACTIVE_NOTES) return@clickable
                                activeNotes.value += key.name
                                activeNoteVolumes.value += key.name to 1f
                                AudioManager.playNote(key.name, masterVolume, 1f)
                            }
                        },
                    contentAlignment = Alignment.Center
                ) { Text(key.name, color = Color.Black) }
            }
        }

        val blackKeyPositions = mapOf(
            "C#" to 0.75f, "D#" to 1.75f, "F#" to 3.75f, "G#" to 4.75f, "A#" to 5.75f
        )
        octaveKeys.filter { it.isSharp }.forEach { key ->
            val pos         = blackKeyPositions[key.name] ?: 0f
            val bkWidth     = whiteKeyWidth * 0.6f
            val offsetX     = whiteKeyWidth * pos - bkWidth / 2
            Box(
                modifier = Modifier
                    .offset(x = offsetX)
                    .width(bkWidth)
                    .height(120.dp)
                    .background(if (activeNotes.value.contains(key.name)) Color.Yellow else Color.Black)
                    .border(1.dp, Color.Black)
                    .clickable {
                        if (activeNotes.value.contains(key.name)) {
                            AudioManager.stopNote(key.name)
                            activeNotes.value -= key.name
                            activeNoteVolumes.value -= key.name
                        } else {
                            if (activeNotes.value.size >= MAX_ACTIVE_NOTES) return@clickable
                            activeNotes.value += key.name
                            activeNoteVolumes.value += key.name to 1f
                            AudioManager.playNote(key.name, masterVolume, 1f)
                        }
                    },
                contentAlignment = Alignment.Center
            ) { Text(key.name, color = Color.White, fontSize = 10.sp) }
        }
    }
}

// ------------------------------
// ActiveNotesVolumeView
// ------------------------------
@Composable
fun ActiveNotesVolumeView(
    activeNoteVolumes: MutableState<Map<String, Float>>,
    masterVolume: Float
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xAA000000), shape = RoundedCornerShape(8.dp))
            .padding(8.dp)
    ) {
        Text("Active Notes Volume", color = Color.White, fontSize = 18.sp)
        Row(modifier = Modifier.horizontalScroll(rememberScrollState()).fillMaxWidth()) {
            activeNoteVolumes.value.forEach { (note, volume) ->
                Column(
                    modifier = Modifier.padding(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(note, color = Color.White)
                    Slider(
                        value = volume,
                        onValueChange = { v ->
                            activeNoteVolumes.value += note to v
                            AudioManager.updateNoteVolume(note, v, masterVolume)
                        },
                        valueRange = 0f..1f,
                        modifier = Modifier.width(150.dp),
                        colors = SliderDefaults.colors(
                            thumbColor = Color(0xFFFFA500),
                            activeTrackColor = Color(0xFFFFA500)
                        )
                    )
                }
            }
        }
    }
}

// ------------------------------
// EffectsPanel
// ------------------------------
@Composable
fun EffectsPanel(
    reverb: MutableState<Float>, fineTune: MutableState<Float>,
    echoMix: MutableState<Float>, echoDelay: MutableState<Float>,
    delayMix: MutableState<Float>, delayTime: MutableState<Float>,
    eqLow: MutableState<Float>, eqMid: MutableState<Float>, eqHigh: MutableState<Float>,
    stereoWidth: MutableState<Float>
) {
    val eqColor    = Color(0xFF66D966)
    val widthColor = Color(0xFF80B0FF)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xAA000000), shape = RoundedCornerShape(8.dp))
            .padding(8.dp)
    ) {
        Text("Effects", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)

        EffectSectionHeader("Fine Tune")
        SliderWithLabel("Pitch", fineTune.value, { fineTune.value = it }, -100f..100f,
            Color(0xFFFFD700)) { "${it.toInt()} cents" }

        EffectSectionHeader("Equalizer")
        SliderWithLabel("Low",  eqLow.value,  { eqLow.value  = it }, -12f..12f, eqColor) {
            String.format(Locale.getDefault(), "%+.1f dB", it)
        }
        SliderWithLabel("Mid",  eqMid.value,  { eqMid.value  = it }, -12f..12f, eqColor) {
            String.format(Locale.getDefault(), "%+.1f dB", it)
        }
        SliderWithLabel("High", eqHigh.value, { eqHigh.value = it }, -12f..12f, eqColor) {
            String.format(Locale.getDefault(), "%+.1f dB", it)
        }

        EffectSectionHeader("Stereo Width")
        SliderWithLabel("Width", stereoWidth.value, { stereoWidth.value = it }, 0f..1f, widthColor) {
            "${(it * 100).toInt()}%"
        }

        EffectSectionHeader("Reverb")
        SliderWithLabel("Mix", reverb.value, { reverb.value = it }, 0f..100f, Color.Magenta)

        EffectSectionHeader("Echo")
        SliderWithLabel("Mix",   echoMix.value,   { echoMix.value = it },   0f..1f,    Color.Cyan)
        SliderWithLabel("Delay", echoDelay.value, { echoDelay.value = it }, 50f..1000f, Color.Cyan
        ) { "${it.toInt()} ms" }

        EffectSectionHeader("Delay")
        SliderWithLabel("Mix",  delayMix.value,  { delayMix.value = it },  0f..1f,     Color(0xFFFFA500))
        SliderWithLabel("Time", delayTime.value, { delayTime.value = it }, 50f..2000f, Color(0xFFFFA500)
        ) { "${it.toInt()} ms" }
    }
}

// ------------------------------
// MetronomePanel — toggle, BPM slider, tap-tempo, click volume
// ------------------------------
@Composable
fun MetronomePanel(
    isOn: MutableState<Boolean>,
    bpm: MutableState<Float>,
    volume: MutableState<Float>
) {
    val accent = Color(0xFFFFA500)
    // Tap-tempo: keep last few timestamps, average the inter-tap intervals.
    val tapTimes = remember { mutableStateOf(listOf<Long>()) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xAA000000), shape = RoundedCornerShape(8.dp))
            .padding(8.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Metronome", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.weight(1f))
            Switch(
                checked = isOn.value,
                onCheckedChange = { isOn.value = it }
            )
        }

        Row(modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
            Text("BPM", color = Color.White)
            Spacer(modifier = Modifier.weight(1f))
            Text(bpm.value.toInt().toString(), color = Color(0xFFCCCCCC), fontSize = 13.sp)
        }
        Slider(
            value = bpm.value,
            onValueChange = { bpm.value = it },
            valueRange = 40f..240f,
            colors = SliderDefaults.colors(thumbColor = accent, activeTrackColor = accent)
        )

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .background(accent, shape = RoundedCornerShape(20.dp))
                    .clickable {
                        val now = System.currentTimeMillis()
                        val times = tapTimes.value.toMutableList()
                        if (times.isNotEmpty() && now - times.last() > 2000L) {
                            times.clear() // user paused — restart sequence
                        }
                        times.add(now)
                        while (times.size > 5) times.removeAt(0)
                        if (times.size >= 2) {
                            var sum = 0L
                            for (i in 1 until times.size) sum += times[i] - times[i - 1]
                            val avg = sum.toFloat() / (times.size - 1)
                            if (avg > 0f) {
                                val newBpm = (60_000f / avg).coerceIn(40f, 240f)
                                bpm.value = newBpm
                            }
                        }
                        tapTimes.value = times
                    }
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text("Tap Tempo", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(modifier = Modifier.weight(1f))
            Text(
                if (tapTimes.value.size >= 2) "tapping…" else "tap 4× for tempo",
                color = Color(0xFFB0B0B0),
                fontSize = 12.sp
            )
        }

        Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Text("Volume", color = Color.White)
            Spacer(modifier = Modifier.weight(1f))
            Text("${(volume.value * 100).toInt()}%", color = Color(0xFFCCCCCC), fontSize = 13.sp)
        }
        Slider(
            value = volume.value,
            onValueChange = { volume.value = it },
            valueRange = 0f..1f,
            colors = SliderDefaults.colors(thumbColor = accent, activeTrackColor = accent)
        )
    }
}

@Composable
private fun EffectSectionHeader(title: String) {
    Text(
        text = title,
        color = Color(0xFFFFA500),
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 10.dp, bottom = 2.dp)
    )
}

@Composable
fun SliderWithLabel(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    color: Color,
    formatter: (Float) -> String = { String.format(Locale.getDefault(), "%.2f", it) }
) {
    Column(modifier = Modifier.padding(vertical = 2.dp)) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(label, color = Color.White)
            Spacer(modifier = Modifier.weight(1f))
            Text(formatter(value), color = Color(0xFFCCCCCC), fontSize = 13.sp)
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            colors = SliderDefaults.colors(thumbColor = color, activeTrackColor = color)
        )
    }
}

// ------------------------------
// AudioOutputButton — opens the system audio output picker so the user
// can route playback to a Bluetooth speaker, wired headset, etc.
// ------------------------------
@Composable
fun AudioOutputButton() {
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .background(Color(0xAA000000), shape = RoundedCornerShape(20.dp))
            .clickable {
                val panel = Intent("com.android.settings.panel.action.MEDIA_OUTPUT").apply {
                    putExtra("android.provider.extra.PACKAGE_NAME", context.packageName)
                }
                runCatching { context.startActivity(panel) }.onFailure {
                    runCatching {
                        context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                    }
                }
            }
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "\uD83D\uDD0A  Audio Output",
            color = Color.White,
            fontSize = 14.sp
        )
    }
}

// ------------------------------
// MasterVolumeView
// ------------------------------
@Composable
fun MasterVolumeView(masterVolume: MutableState<Float>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xAA000000), shape = RoundedCornerShape(8.dp))
            .padding(8.dp)
    ) {
        Text("Master Volume", color = Color.White, fontSize = 18.sp)
        Slider(
            value = masterVolume.value,
            onValueChange = { masterVolume.value = it },
            valueRange = 0f..1f,
            colors = SliderDefaults.colors(thumbColor = Color.Blue, activeTrackColor = Color.Blue)
        )
    }
}

// ------------------------------
// TunerDial — Canvas analog meter (-50¢ … +50¢)
// ------------------------------
@Composable
private fun TunerDial(
    cents: Float,
    inTune: Boolean,
    detected: Boolean,
    modifier: Modifier = Modifier
) {
    val animatedCents by animateFloatAsState(
        targetValue    = cents.coerceIn(-50f, 50f),
        animationSpec  = tween(durationMillis = 120),
        label          = "needle"
    )

    // Map cents to angle in radians: 0¢ = 12-o'clock (−90°), ±50¢ = ±60°
    val centsToRad = { c: Float ->
        val frac = c.coerceIn(-50f, 50f) / 50f
        (frac * 60.0 - 90.0) * (PI / 180.0)
    }
    // Compose drawArc uses 3-o'clock = 0°, clockwise. The full ±60° arc
    // starts at 210° (Compose) and spans 120°.
    val centsToArcDeg = { c: Float -> 210f + (c + 50f) / 100f * 120f }

    Canvas(modifier = modifier) {
        val cx     = size.width  / 2f
        val cy     = size.height / 2f + size.height * 0.12f
        val radius = minOf(size.width, size.height) / 2f - 8.dp.toPx()

        // Backplate
        drawCircle(Color.Black.copy(alpha = 0.55f), radius = radius, center = Offset(cx, cy))
        drawCircle(
            color  = Color.White.copy(alpha = 0.25f), radius = radius, center = Offset(cx, cy),
            style  = Stroke(width = 2.dp.toPx())
        )

        // Color bands
        val bandR      = radius - 6.dp.toPx()
        val bandStroke = Stroke(width = 10.dp.toPx(), cap = StrokeCap.Butt)
        listOf(
            Triple(-50f, -15f, Color.Red),
            Triple(-15f,  -5f, Color.Yellow),
            Triple(  -5f,  5f, Color.Green),
            Triple(   5f, 15f, Color.Yellow),
            Triple(  15f, 50f, Color.Red)
        ).forEach { (a, b, color) ->
            drawArc(
                color      = color.copy(alpha = 0.55f),
                startAngle = centsToArcDeg(a),
                sweepAngle = (b - a) / 100f * 120f,
                useCenter  = false,
                topLeft    = Offset(cx - bandR, cy - bandR),
                size       = Size(bandR * 2f, bandR * 2f),
                style      = bandStroke
            )
        }

        // Tick marks every 5¢
        for (c in -50..50 step 5) {
            val isMajor = c % 25 == 0
            val isMid   = c % 10 == 0
            val inner   = radius - (if (isMajor) 26 else if (isMid) 20 else 14).dp.toPx()
            val outer   = radius - 6.dp.toPx()
            val angle   = centsToRad(c.toFloat())
            val cosA    = cos(angle).toFloat()
            val sinA    = sin(angle).toFloat()
            drawLine(
                color       = Color.White.copy(alpha = if (isMajor) 0.95f else if (isMid) 0.7f else 0.45f),
                start       = Offset(cx + cosA * inner, cy + sinA * inner),
                end         = Offset(cx + cosA * outer, cy + sinA * outer),
                strokeWidth = (if (isMajor) 2.2f else if (isMid) 1.6f else 1.0f).dp.toPx(),
                cap         = StrokeCap.Round
            )
        }

        // Needle
        val needleAngle = centsToRad(animatedCents)
        val cosN = cos(needleAngle).toFloat()
        val sinN = sin(needleAngle).toFloat()
        val tipR = radius - 14.dp.toPx(); val tailR = 18.dp.toPx()
        drawLine(
            color       = if (detected) (if (inTune) Color.Green else Color.White) else Color.Gray.copy(alpha = 0.55f),
            start       = Offset(cx - cosN * tailR, cy - sinN * tailR),
            end         = Offset(cx + cosN * tipR,  cy + sinN * tipR),
            strokeWidth = 3.5f.dp.toPx(),
            cap         = StrokeCap.Round
        )

        // Center pivot
        val pivotR = 9.dp.toPx()
        drawCircle(Color(0xFFFFA500), radius = pivotR, center = Offset(cx, cy))
        drawCircle(
            color  = Color.White.copy(alpha = 0.85f), radius = pivotR, center = Offset(cx, cy),
            style  = Stroke(width = 1.5f.dp.toPx())
        )
    }
}

// ------------------------------
// TunerScreen
// ------------------------------
@Composable
fun TunerScreen() {
    val frequency  by TunerManager.frequency.collectAsState()
    val noteName   by TunerManager.noteName.collectAsState()
    val cents      by TunerManager.cents.collectAsState()
    val detected   by TunerManager.detected.collectAsState()
    val inputLevel by TunerManager.inputLevel.collectAsState()

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) TunerManager.start() }

    LaunchedEffect(Unit) { launcher.launch(Manifest.permission.RECORD_AUDIO) }

    val inTune     = detected && abs(cents) < 5f
    val centsColor = when {
        !detected       -> Color(0xFFAAAAAA)
        abs(cents) < 5f  -> Color.Green
        abs(cents) < 15f -> Color.Yellow
        else            -> Color.Red
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color.Blue.copy(alpha = 0.6f), Color(0xFF800080).copy(alpha = 0.8f))
                )
            )
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(20.dp))
        Text("Tuner", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text      = "Sing or play a sustained note.\nTanpura is paused while you tune.",
            color     = Color(0xFFDDDDDD),
            fontSize  = 13.sp,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(8.dp))

        TunerDial(
            cents    = cents,
            inTune   = inTune,
            detected = detected,
            modifier = Modifier.width(280.dp).height(280.dp)
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Note name
        Text(
            text       = if (detected) noteName else "—",
            color      = Color.White,
            fontSize   = 44.sp,
            fontWeight = FontWeight.ExtraBold
        )
        // Cents offset
        Text(
            text      = if (detected) String.format(Locale.getDefault(), "%+.0f cents", cents) else "listening…",
            color     = centsColor,
            fontSize  = 18.sp,
            fontWeight = FontWeight.SemiBold
        )
        // Frequency
        Text(
            text     = if (detected) String.format(Locale.getDefault(), "%.1f Hz", frequency) else " ",
            color    = Color(0xFFAAAAAA),
            fontSize = 13.sp
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Input level bar
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .height(6.dp)
                .background(Color.White.copy(alpha = 0.15f), RoundedCornerShape(3.dp))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(inputLevel.coerceIn(0f, 1f))
                    .background(Color(0xFFFFA500), RoundedCornerShape(3.dp))
            )
        }
    }
}

// ------------------------------
// TanpuraKingsApp
// ------------------------------
@Composable
fun TanpuraKingsApp() {
    val context = LocalContext.current

    val activeNotes      = remember { mutableStateOf(setOf<String>()) }
    val activeNoteVolumes = remember { mutableStateOf(mapOf<String, Float>()) }
    val masterVolume = remember { mutableFloatStateOf(1f) }
    val reverb       = remember { mutableFloatStateOf(0f) }
    val fineTune     = remember { mutableFloatStateOf(0f) }
    val echoMix      = remember { mutableFloatStateOf(0f) }
    val echoDelay    = remember { mutableFloatStateOf(300f) }
    val delayMix     = remember { mutableFloatStateOf(0f) }
    val delayTime    = remember { mutableFloatStateOf(500f) }

    // New: 3-band EQ + stereo width
    val eqLow       = remember { mutableFloatStateOf(0f) }
    val eqMid       = remember { mutableFloatStateOf(0f) }
    val eqHigh      = remember { mutableFloatStateOf(0f) }
    val stereoWidth = remember { mutableFloatStateOf(0.5f) }

    // New: metronome
    val metronomeOn     = remember { mutableStateOf(false) }
    val metronomeBpm    = remember { mutableFloatStateOf(80f) }
    val metronomeVolume = remember { mutableFloatStateOf(0.7f) }

    DisposableEffect(Unit) {
        AudioManager.init(context)
        onDispose { AudioManager.release() }
    }

    LaunchedEffect(masterVolume.value) {
        AudioManager.updateMasterVolume(masterVolume.value)
    }

    LaunchedEffect(reverb.value, fineTune.value, echoMix.value, echoDelay.value, delayMix.value, delayTime.value) {
        AudioManager.updateEffects(
            reverb.value, fineTune.value,
            echoMix.value, echoDelay.value,
            delayMix.value, delayTime.value
        )
    }

    LaunchedEffect(eqLow.value, eqMid.value, eqHigh.value) {
        AudioManager.updateEQ(eqLow.value, eqMid.value, eqHigh.value)
    }
    LaunchedEffect(stereoWidth.value) {
        AudioManager.updateStereoWidth(stereoWidth.value)
    }
    LaunchedEffect(metronomeOn.value) {
        if (metronomeOn.value) AudioManager.startMetronome() else AudioManager.stopMetronome()
    }
    LaunchedEffect(metronomeBpm.value) {
        AudioManager.setMetronomeBPM(metronomeBpm.value)
    }
    LaunchedEffect(metronomeVolume.value) {
        AudioManager.setMetronomeVolume(metronomeVolume.value)
    }

    var selectedTab by remember { mutableIntStateOf(0) }

    // Tab-switch side-effects: mirror iOS ContentView.onChange(of: selectedTab)
    LaunchedEffect(selectedTab) {
        when (selectedTab) {
            1 -> {  // switched to Tuner — stop drone so mic doesn't pick it up
                metronomeOn.value = false
                AudioManager.stopMetronome()
                AudioManager.stopAllNotes()
                activeNotes.value      = emptySet()
                activeNoteVolumes.value = emptyMap()
            }
            0 -> TunerManager.stop()   // switched back to Drone — stop mic
        }
    }

    Scaffold(
        containerColor = Color.Transparent,
        bottomBar = {
            NavigationBar(containerColor = Color.Black.copy(alpha = 0.85f)) {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick  = { selectedTab = 0 },
                    icon     = { Text("♪", fontSize = 22.sp, color = if (selectedTab == 0) Color(0xFFFFA500) else Color(0xFF888888)) },
                    label    = { Text("Drone", color = if (selectedTab == 0) Color(0xFFFFA500) else Color(0xFF888888)) }
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick  = { selectedTab = 1 },
                    icon     = { Text("🎤", fontSize = 18.sp) },
                    label    = { Text("Tuner", color = if (selectedTab == 1) Color(0xFFFFA500) else Color(0xFF888888)) }
                )
            }
        }
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            when (selectedTab) {
                0 -> DroneScreen(
                    activeNotes, activeNoteVolumes, masterVolume,
                    reverb, fineTune, echoMix, echoDelay, delayMix, delayTime,
                    eqLow, eqMid, eqHigh, stereoWidth,
                    metronomeOn, metronomeBpm, metronomeVolume
                )
                1 -> TunerScreen()
            }
        }
    }
}

// ------------------------------
// DroneScreen — the scrolling drone UI (extracted from TanpuraKingsApp)
// ------------------------------
@Composable
fun DroneScreen(
    activeNotes: MutableState<Set<String>>,
    activeNoteVolumes: MutableState<Map<String, Float>>,
    masterVolume: MutableState<Float>,
    reverb: MutableState<Float>,
    fineTune: MutableState<Float>,
    echoMix: MutableState<Float>,
    echoDelay: MutableState<Float>,
    delayMix: MutableState<Float>,
    delayTime: MutableState<Float>,
    eqLow: MutableState<Float>,
    eqMid: MutableState<Float>,
    eqHigh: MutableState<Float>,
    stereoWidth: MutableState<Float>,
    metronomeOn: MutableState<Boolean>,
    metronomeBpm: MutableState<Float>,
    metronomeVolume: MutableState<Float>
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .background(
                Brush.verticalGradient(
                    listOf(Color.Blue.copy(alpha = 0.6f), Color(0xFF800080).copy(alpha = 0.8f))
                )
            )
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(20.dp))
        Image(
            painter            = painterResource(id = R.drawable.app_logo),
            contentDescription = "Tanpura Kings logo",
            modifier           = Modifier.width(96.dp).height(96.dp)
        )
        Text("Tanpura Kings", fontSize = 24.sp, color = Color.White, fontWeight = FontWeight.SemiBold)
        Spacer(modifier = Modifier.height(12.dp))
        AudioOutputButton()
        Spacer(modifier = Modifier.height(16.dp))
        PianoView(activeNotes, activeNoteVolumes, masterVolume.value)
        Spacer(modifier = Modifier.height(16.dp))
        if (activeNoteVolumes.value.isNotEmpty()) {
            ActiveNotesVolumeView(activeNoteVolumes, masterVolume.value)
            Spacer(modifier = Modifier.height(16.dp))
        }
        MetronomePanel(metronomeOn, metronomeBpm, metronomeVolume)
        Spacer(modifier = Modifier.height(16.dp))
        EffectsPanel(reverb, fineTune, echoMix, echoDelay, delayMix, delayTime, eqLow, eqMid, eqHigh, stereoWidth)
        Spacer(modifier = Modifier.height(16.dp))
        MasterVolumeView(masterVolume)
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            "© kingsman software solutions",
            fontSize = 14.sp, color = Color.White,
            textAlign = TextAlign.Center,
            modifier  = Modifier.fillMaxWidth()
        )
    }
}

// ------------------------------
// MainActivity
// ------------------------------
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent { TanpuraKingsApp() }
    }
}

@Preview(showBackground = true)
@Composable
fun DefaultPreview() { TanpuraKingsApp() }
