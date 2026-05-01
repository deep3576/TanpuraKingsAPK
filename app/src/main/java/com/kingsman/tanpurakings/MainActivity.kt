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
import kotlin.math.pow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    private val echoJobs = mutableMapOf<String, Job>()
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
    // primary audio output — phone calls, navigation prompts, voice
    // assistant, even brief sounds from a background app.
    //
    //  - LOSS_TRANSIENT: pause our drones and snapshot which notes were
    //    playing + at what per-note volumes. When the foreign app finishes
    //    we'll get GAIN and replay them. This is the case the user hit:
    //    "another background app makes a sound and we don't resume".
    //  - LOSS (permanent — a phone call, or another media app took over for
    //    an indefinite period): stop everything and DROP the snapshot.
    //    The user will tap notes again when they come back.
    //  - LOSS_TRANSIENT_CAN_DUCK: a notification chime, etc. The OS lets
    //    us keep playing at lower mix. A drone sounds fine through that,
    //    so we do nothing.
    //  - GAIN: replay the LOSS_TRANSIENT snapshot if any.
    private fun handleAudioFocusChange(change: Int) {
        val scope = coroutineScope ?: return
        when (change) {
            android.media.AudioManager.AUDIOFOCUS_GAIN -> {
                if (!pausedForFocus) return
                pausedForFocus = false
                val snap = pausedNotesSnapshot
                pausedNotesSnapshot = emptyMap()
                if (snap.isEmpty()) return
                scope.launch {
                    // Tiny settle so the foreign app's render-tail isn't
                    // overlapping our first frame on Bluetooth.
                    delay(120)
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
                pausedForFocus = false
                pausedNotesSnapshot = emptyMap()
                if (activePlayers.isNotEmpty()) stopAllNotes()
            }
            android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                // Drone is steady; let it keep playing. The foreign app
                // will mix on top briefly.
            }
        }
    }

    // Output device added/removed (Bluetooth speaker connect/disconnect, headphones
    // plugged/unplugged, etc.). MediaPlayer doesn't auto-migrate to the new route
    // reliably, so we snapshot the current notes, stop them, re-claim audio focus
    // (so the new route is engaged as primary), and replay them with their previous
    // per-note volumes.
    private fun refreshActiveNotesOnRouteChange() {
        val scope = coroutineScope ?: return
        scope.launch {
            val snapshot = activePlayers.keys.toList()
            if (snapshot.isEmpty()) {
                requestAudioFocusInternal()
                return@launch
            }
            val volumesAtSwitch = HashMap(noteVolumes)
            snapshot.forEach { stopNote(it) }
            requestAudioFocusInternal()
            // Tiny delay lets the OS finish the route handoff before we
            // build new MediaPlayers — without this some head units take
            // the new players' first second and drop it.
            delay(150)
            val master = currentMasterVolume
            snapshot.forEach { name ->
                val nv = volumesAtSwitch[name] ?: 1f
                playNote(name, master, nv)
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

            // Transient effects via SoundPool
            val soundId = noteSoundIds[fileKey]
            if (soundId != null && (reverbMix > 0f || echoMix > 0f || delayMix > 0f)) {
                echoJobs[noteName]?.cancel()
                echoJobs[noteName] = launch {
                    if (reverbMix > 0f) launch {
                        val intervals = longArrayOf(17, 23, 31, 41, 53, 67, 83, 101, 127)
                        var vol = (volume * (reverbMix / 100f) * 0.75f).coerceAtMost(1f)
                        var i = 0
                        while (i < intervals.size && vol >= 0.02f) {
                            delay(intervals[i++])
                            soundPool.play(soundId, vol, vol, 0, 0, pitch)
                            vol *= 0.6f
                        }
                    }
                    if (delayMix > 0f) launch {
                        delay(delayTimeMs.toLong())
                        val vol = (volume * delayMix).coerceIn(0f, 1f)
                        soundPool.play(soundId, vol, vol, 0, 0, pitch)
                    }
                    if (echoMix > 0f) launch {
                        var vol = volume * echoMix
                        while (vol > 0.02f) {
                            delay(echoDelayMs.toLong())
                            soundPool.play(soundId, vol.coerceIn(0f, 1f), vol.coerceIn(0f, 1f), 0, 0, pitch)
                            vol *= 0.55f
                        }
                    }
                }
            }
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
        reverbMix   = reverbMixVal
        echoMix     = echoMixVal
        echoDelayMs = echoDelayVal
        delayMix    = delayMixVal
        delayTimeMs = delayTimeVal
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

        if (::soundPool.isInitialized) soundPool.release()
        noteSoundIds.clear(); noteDurations.clear()
        appContext    = null
        isInitialized = false
        Log.d("AudioManager", "AudioManager released")
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
            painter = painterResource(id = R.drawable.app_logo),
            contentDescription = "Tanpura Kings logo",
            modifier = Modifier.width(96.dp).height(96.dp)
        )
        Text(
            "Tanpura Kings",
            fontSize = 24.sp,
            color = Color.White,
            fontWeight = FontWeight.SemiBold
        )
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
        EffectsPanel(
            reverb, fineTune, echoMix, echoDelay, delayMix, delayTime,
            eqLow, eqMid, eqHigh, stereoWidth
        )
        Spacer(modifier = Modifier.height(16.dp))
        MasterVolumeView(masterVolume)
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            "© kingsman software solutions",
            fontSize = 14.sp, color = Color.White,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
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
