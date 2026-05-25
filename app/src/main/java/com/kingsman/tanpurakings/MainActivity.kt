package com.kingsman.tanpurakings

import android.app.Activity
import android.content.Context
import android.graphics.Paint as NativePaint
import android.graphics.Typeface
import android.content.Intent
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Switch
import android.provider.Settings
import android.util.Log
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import com.google.android.gms.ads.FullScreenContentCallback
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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeJoin
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.runtime.mutableLongStateOf
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
import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder as AndroidMediaRecorder
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresPermission
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.material3.TextButton
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.graphics.nativeCanvas
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import androidx.core.graphics.toColorInt

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
    /** True when at least one drone note is sounding. */
    val isPlaying: Boolean get() = activePlayers.isNotEmpty()
    private val loopJobs     = mutableMapOf<String, Job>()
    private val noteVolumes  = mutableMapOf<String, Float>()

    // SoundPool used only for transient echo/delay/reverb copies
    private lateinit var soundPool: SoundPool
    // noteSoundIds removed — SoundPool.load() decompresses each file to PCM
    // and holds it in memory indefinitely.  The notes never actually use
    // soundPool.play(); effects are all MediaPlayer-based (buildPlayer).
    // Removing these loads matches the iOS lazy-buffer fix and prevents the
    // same OOM crash pattern on Android.
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
    private var subOctaveMix:      Float = 0f
    private var warmth:            Float = 0f
    private var compressionAmount: Float = 0f
    private val subOctavePlayers    = mutableMapOf<String, MediaPlayer>()
    private val warmthBoosts        = mutableMapOf<String, android.media.audiofx.BassBoost>()
    private val loudnessEnhancers   = mutableMapOf<String, android.media.audiofx.LoudnessEnhancer>()

    // 3-band EQ state (gain in dB, -12..+12). Per-MediaPlayer Equalizer objects
    // are attached on creation and tracked here for cleanup.
    private var eqLowDb:  Float = 0f
    private var eqMidDb:  Float = 0f
    private var eqHighDb: Float = 0f
    private val equalizers = mutableMapOf<String, Equalizer>()

    // Stereo width: 0.0 = all notes panned center, 1.0 = full spread.
    private var stereoWidth: Float = 0.5f

    // Octave selector: -1 = Lower, 0 = Mid (default), +1 = Higher.
    // All notes are pitch-shifted from a single fsharp.mp3 base file.
    // F# sits at the centre of the chromatic scale → max ±6 semitones shift.
    private val semitoneOffsets: Map<String, Int> = mapOf(
        "c" to -6, "csharp" to -5, "d" to -4, "dsharp" to -3,
        "e" to -2, "f" to -1, "fsharp" to 0, "g" to 1,
        "gsharp" to 2, "a" to 3, "asharp" to 4, "b" to 5
    )
    private var octaveLevel: Int = 0  // -1, 0, or +1

    // MediaSession — enables lock-screen / headphone / steering-wheel controls
    // and is the bridge to Android Auto in Phase 2.
    private var mediaSession: MediaSessionCompat? = null
    /** Exposed so AudioPlaybackService can attach it to the notification style. */
    val mediaSessionToken: MediaSessionCompat.Token? get() = mediaSession?.sessionToken

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
        // Baseline preset mirrors iOS AVAudioUnitReverb(.cathedral): very long
        // decay, high diffusion, dense reflections. applyReverbLevel() scales
        // these dynamically as the slider moves.
        try {
            envReverb = EnvironmentalReverb(0, 0).apply {
                roomLevel         = (-1000).toShort()  // -9000..0 mB  (warmer baseline)
                roomHFLevel       = (-600).toShort()   // -9000..0 mB
                decayTime         = 5000               // 100-20000 ms — cathedral (~5 s tail)
                decayHFRatio      = 600.toShort()      // 100-2000  (more HF decay = airy)
                reflectionsLevel  = (-2000).toShort()  // -9000..1000 mB
                reflectionsDelay  = 20                 // 0-300 ms
                reverbLevel       = (-600).toShort()   // -9000..2000 mB (louder initial wet)
                reverbDelay       = 35                 // 0-100 ms
                diffusion         = 1000.toShort()     // 0-1000  (maximum spread)
                density           = 1000.toShort()     // 0-1000  (maximum density)
                enabled           = false
            }
        } catch (e: Exception) {
            Log.w("AudioManager", "EnvironmentalReverb unavailable: $e")
        }

        isInitialized = true
        setupMediaSession()

        // Fetch note durations (header-only read, no PCM in memory) and load
        // only the metronome click into SoundPool (it's a tiny transient file).
        // Note files are NOT loaded into SoundPool — they were never played via
        // soundPool.play() and decompressing 12 large MP3s to PCM caused OOM.
        coroutineScope?.launch(Dispatchers.IO) {
            // Only fsharp.mp3 is used as the base for all notes — fetch its duration.
            val retriever = MediaMetadataRetriever()
            try {
                val afd = context.applicationContext.assets.openFd("Audio/fsharp.mp3")
                retriever.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull()
                    ?.takeIf { it > 0 }
                    ?.let { noteDurations["fsharp"] = it }
                afd.close()
            } catch (e: Exception) {
                Log.e("AudioManager", "Duration read error: fsharp", e)
            }
            retriever.release()
            // Metronome click is a short transient — safe to keep in SoundPool.
            try {
                val afd = context.applicationContext.assets.openFd("Audio/click.mp3")
                clickSoundId = soundPool.load(afd, 1)
                afd.close()
            } catch (e: Exception) {
                Log.e("AudioManager", "Load error: click", e)
            }
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

    /**
     * Combined playback pitch rate for [noteName]:
     *   semitone offset from F# base  ×  octave multiplier  ×  fine-tune rate.
     * Mirrors the iOS formula: pitchCents = semitones*100 + octaveShift + fineTune.
     */
    private fun pitchRate(noteName: String): Float {
        val key       = noteName.lowercase().replace("#", "sharp")
        val semitones = semitoneOffsets[key] ?: 0
        val semitoneRate = 2.0.pow(semitones / 12.0).toFloat()
        val octaveRate   = 2.0.pow(octaveLevel.toDouble()).toFloat()  // 0.5 / 1.0 / 2.0
        return semitoneRate * octaveRate * fineTuneRate(fineTuneCents)
    }

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
    // Always loads fsharp.mp3 — the caller supplies the pre-computed pitch rate so each
    // note sounds at the correct pitch via PlaybackParams.setPitch().
    private suspend fun buildPlayer(volume: Float, pitch: Float): MediaPlayer? =
        withContext(Dispatchers.IO) {
            try {
                val ctx = appContext ?: return@withContext null
                val afd = ctx.assets.openFd("Audio/fsharp.mp3")
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
                Log.e("AudioManager", "buildPlayer error (fsharp base)", e)
                null
            }
        }

    fun playNote(noteName: String, masterVolume: Float, noteVolume: Float = 1f) {
        if (activePlayers.size >= MAX_ACTIVE_NOTES) return
        currentMasterVolume = masterVolume
        val volume  = (masterVolume * noteVolume).coerceIn(0f, 1f)

        coroutineScope?.launch {
            // Combined pitch: semitone-from-F# × octave × fine-tune.
            val pitch  = pitchRate(noteName)
            // Build the player at zero volume so we can fade in cleanly
            // (avoids a click/pop on tap-on, gives crossfade-feel when this
            // note is replacing another one the user just tapped off).
            val player = buildPlayer(0f, pitch) ?: return@launch

            player.start()
            activePlayers[noteName] = player
            noteVolumes[noteName]   = noteVolume
            updateMediaSession()
            // Keep the process alive in the background.  Start the foreground
            // service on the first note; subsequent notes are no-ops.
            if (activePlayers.size == 1) startPlaybackService()

            // Attach a per-MediaPlayer Equalizer so EQ is applied to this note.
            attachEqualizer(player, noteName)

            // Warmth: BassBoost per player (full 0-1000 range for clear audibility)
            try {
                val bb = android.media.audiofx.BassBoost(0, player.audioSessionId)
                bb.setStrength((warmth * 1000).toInt().toShort())
                bb.enabled = true
                warmthBoosts[noteName] = bb
            } catch (t: Throwable) {
                Log.w("AudioManager", "BassBoost attach failed: $t")
            }

            // Compressor: LoudnessEnhancer adds psychoacoustic loudness in
            // millibels — goes beyond the 0-1 volume ceiling so compression
            // is clearly audible (up to +9 dB at full slider).
            try {
                val le = android.media.audiofx.LoudnessEnhancer(player.audioSessionId)
                le.setTargetGain((compressionAmount * 900).toInt())
                le.enabled = compressionAmount > 0f
                loudnessEnhancers[noteName] = le
            } catch (t: Throwable) {
                Log.w("AudioManager", "LoudnessEnhancer attach failed: $t")
            }

            // Sub-octave companion player: same pitch shifted one octave down.
            val subVol = (volume * subOctaveMix).coerceIn(0f, 1f)
            coroutineScope?.launch {
                val sp = buildPlayer(subVol, pitchRate(noteName) * 0.5f)
                    ?: return@launch
                sp.isLooping = true
                sp.start()
                subOctavePlayers[noteName] = sp
            }

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

            val duration = noteDurations["fsharp"]
            if (duration != null && duration > CROSSFADE_MS * 2) {
                loopJobs[noteName] = launch { crossfadeLoop(noteName, volume, duration) }
            } else {
                // Duration unknown yet or very short — fall back to built-in looping
                player.isLooping = true
            }

            // Persistent delay/echo via real looping MediaPlayer instances.
            // (Reverb is handled globally by EnvironmentalReverb, no per-note work needed.)
            launchEffectPlayers(noteName, volume, pitch)
        }
    }

    // Crossfade loop: starts the next player CROSSFADE_MS before the current one ends,
    // then smoothly hands volume over so there is never silence at the loop boundary.
    private suspend fun crossfadeLoop(
        noteName: String, volume: Float, durationMs: Long
    ) {
        // delay() throws CancellationException when the job is cancelled — no isActive needed
        val waitBeforeFade = (durationMs - CROSSFADE_MS).coerceAtLeast(200L)
        while (true) {
            delay(waitBeforeFade)

            val pitch      = pitchRate(noteName)
            val nextPlayer = buildPlayer(0f, pitch) ?: break

            // Bug fix: if stopNote() cancels this coroutine while nextPlayer is already
            // started (during delay(stepMs) inside the fade loop), nextPlayer would keep
            // playing with no owner in activePlayers — a permanent MediaPlayer leak.
            // try/finally guarantees nextPlayer is stopped+released on any exit path
            // (CancellationException, curPlayer disappearing, or normal completion).
            // ownedByMap = true once activePlayers owns the player so finally is a no-op.
            var ownedByMap = false
            try {
                val curPlayer = activePlayers[noteName]
                if (curPlayer == null) break   // note stopped; finally releases nextPlayer

                nextPlayer.start()
                val stepMs = CROSSFADE_MS / CROSSFADE_STEPS
                for (step in 1..CROSSFADE_STEPS) {
                    val alpha = step.toFloat() / CROSSFADE_STEPS
                    curPlayer.setVolume(volume * (1f - alpha), volume * (1f - alpha))
                    nextPlayer.setVolume(volume * alpha, volume * alpha)
                    delay(stepMs)   // CancellationException lands here if job is cancelled
                }

                activePlayers[noteName] = nextPlayer
                ownedByMap = true           // map now owns nextPlayer — skip finally cleanup
                runCatching { curPlayer.stop() }
                curPlayer.release()
            } finally {
                if (!ownedByMap) {
                    // Coroutine was cancelled or note disappeared mid-fade — stop the
                    // orphaned next player before it leaks.
                    runCatching { nextPlayer.stop() }
                    nextPlayer.release()
                }
            }
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
        subOctavePlayers.forEach { (noteName, sp) ->
            val nv  = noteVolumes[noteName] ?: 1f
            val vol = (masterVolume * nv * subOctaveMix).coerceIn(0f, 1f)
            runCatching { sp.setVolume(vol, vol) }
        }
    }

    fun stopNote(noteName: String) {
        val player = activePlayers.remove(noteName) ?: return
        loopJobs.remove(noteName)?.cancel()
        echoJobs.remove(noteName)?.cancel()
        val effects = effectPlayers.remove(noteName)
        val subPlayer = subOctavePlayers.remove(noteName)
        val warmth = warmthBoosts.remove(noteName)
        val loudness = loudnessEnhancers.remove(noteName)
        val eq = equalizers.remove(noteName)
        noteVolumes.remove(noteName)

        // Stop immediately — no fade. Silence first to minimise click.
        runCatching { player.setVolume(0f, 0f) }
        subPlayer?.runCatching { setVolume(0f, 0f) }
        effects?.forEach { ep -> runCatching { ep.setVolume(0f, 0f) } }
        warmth?.runCatching { release() }
        loudness?.runCatching { release() }
        eq?.runCatching { release() }
        effects?.forEach { ep -> runCatching { ep.stop() }; ep.release() }
        subPlayer?.let { sp -> runCatching { sp.stop() }; sp.release() }
        runCatching { player.stop() }
        player.release()
        updateMediaSession()
        // Stop the foreground service once the last note is gone so the
        // persistent notification is dismissed.
        if (activePlayers.isEmpty()) stopPlaybackService()
        // Re-spread the remaining notes immediately so their pan rebalances
        // as soon as the user taps off — no need to wait for the fade to finish.
        reapplyStereoSpread()
    }

    fun stopAllNotes() {
        loopJobs.values.forEach { it.cancel() };  loopJobs.clear()
        echoJobs.values.forEach { it.cancel() };  echoJobs.clear()

        val allPlayers  = activePlayers.values.toList()
        val allSubs     = subOctavePlayers.values.toList()
        val allEffects  = effectPlayers.values.flatMap { it }
        val allEqs      = equalizers.values.toList()
        val allWarmth   = warmthBoosts.values.toList()
        val allLoudness = loudnessEnhancers.values.toList()

        warmthBoosts.clear(); loudnessEnhancers.clear()
        equalizers.clear();   effectPlayers.clear()
        subOctavePlayers.clear(); activePlayers.clear(); noteVolumes.clear()
        updateMediaSession()

        // Stop immediately — silence first then release.
        allPlayers.forEach  { p  -> runCatching { p.setVolume(0f, 0f) } }
        allSubs.forEach     { sp -> runCatching { sp.setVolume(0f, 0f) } }
        allEffects.forEach  { ep -> runCatching { ep.setVolume(0f, 0f) } }
        allWarmth.forEach   { runCatching { it.release() } }
        allLoudness.forEach { runCatching { it.release() } }
        allEqs.forEach      { runCatching { it.release() } }
        allEffects.forEach  { ep -> runCatching { ep.stop() }; ep.release() }
        allSubs.forEach     { sp -> runCatching { sp.stop() }; sp.release() }
        allPlayers.forEach  { p  -> runCatching { p.stop() }; p.release() }

        if (pausedNotesSnapshot.isEmpty()) stopPlaybackService()
    }

    fun updateEffects(
        reverbMixVal: Float, fineTune: Float,
        echoMixVal: Float, echoDelayVal: Float
    ) {
        if (fineTune != fineTuneCents) {
            fineTuneCents = fineTune
            // Each note has its own pitch (semitone + octave + fine-tune) — update individually.
            // Must re-pitch ALL player types: main drone, sub-octave, and echo copies.
            activePlayers.forEach { (noteName, player) ->
                val pitch = pitchRate(noteName)
                runCatching { player.playbackParams = PlaybackParams().setSpeed(1.0f).setPitch(pitch) }
            }
            subOctavePlayers.forEach { (noteName, sp) ->
                val pitch = pitchRate(noteName) * 0.5f
                runCatching { sp.playbackParams = PlaybackParams().setSpeed(1.0f).setPitch(pitch) }
            }
            effectPlayers.forEach { (noteName, epList) ->
                val pitch = pitchRate(noteName)
                epList.forEach { ep ->
                    runCatching { ep.playbackParams = PlaybackParams().setSpeed(1.0f).setPitch(pitch) }
                }
            }
        }

        // Update reverb immediately via hardware effect.
        applyReverbLevel(reverbMixVal)
        reverbMix = reverbMixVal

        val echoChanged  = echoMixVal  != echoMix  || echoDelayVal  != echoDelayMs
        echoMix     = echoMixVal
        echoDelayMs = echoDelayVal

        // Restart echo players for all active notes when parameters change.
        if (echoChanged && activePlayers.isNotEmpty()) {
            val scope = coroutineScope ?: return
            activePlayers.keys.toList().forEach { noteName ->
                echoJobs.remove(noteName)?.cancel()
                effectPlayers.remove(noteName)?.forEach { ep ->
                    runCatching { ep.stop() }; ep.release()
                }
                val nv    = noteVolumes[noteName] ?: 1f
                val vol   = (currentMasterVolume * nv).coerceIn(0f, 1f)
                val pitch = pitchRate(noteName)
                scope.launchEffectPlayers(noteName, vol, pitch)
            }
        }
    }

    fun updateOctaveBlend(mix: Float) {
        subOctaveMix = mix.coerceIn(0f, 1f)
        subOctavePlayers.forEach { (noteName, sp) ->
            val nv  = noteVolumes[noteName] ?: 1f
            val vol = (currentMasterVolume * nv * subOctaveMix).coerceIn(0f, 1f)
            runCatching { sp.setVolume(vol, vol) }
        }
    }

    /**
     * Switch to [octave]: -1 = Lower, 0 = Mid, +1 = Higher.
     * Instantly re-pitches every currently-playing note (including sub-octave
     * and echo copies) so the change is heard without restarting the notes.
     * Mirrors iOS AudioManager.updateOctave(_:).
     */
    fun updateOctave(octave: Int) {
        octaveLevel = octave.coerceIn(-1, 1)
        activePlayers.forEach { (noteName, player) ->
            val pitch = pitchRate(noteName)
            runCatching { player.playbackParams = PlaybackParams().setSpeed(1.0f).setPitch(pitch) }
        }
        subOctavePlayers.forEach { (noteName, sp) ->
            val pitch = pitchRate(noteName) * 0.5f
            runCatching { sp.playbackParams = PlaybackParams().setSpeed(1.0f).setPitch(pitch) }
        }
        effectPlayers.forEach { (noteName, epList) ->
            val pitch = pitchRate(noteName)
            epList.forEach { ep ->
                runCatching { ep.playbackParams = PlaybackParams().setSpeed(1.0f).setPitch(pitch) }
            }
        }
    }

    fun updateWarmth(amount: Float) {
        warmth = amount.coerceIn(0f, 1f)
        val strength = (warmth * 700).toInt().toShort()
        warmthBoosts.values.forEach { bb ->
            runCatching { bb.setStrength(strength) }
        }
    }

    fun updateCompressor(amount: Float) {
        compressionAmount = amount.coerceIn(0f, 1f)
        // LoudnessEnhancer adds 0–900 mB (~9 dB) of makeup gain beyond the
        // 0–1 volume ceiling, so the effect is clearly audible.
        val gainMb = (compressionAmount * 900).toInt()
        loudnessEnhancers.values.forEach { le ->
            runCatching {
                le.setTargetGain(gainMb)
                le.enabled = compressionAmount > 0f
            }
        }
    }

    // Maps mix 0-100 to EnvironmentalReverb parameters using the same sqrt
    // perceptual curve as iOS, so the two platforms feel identical at each
    // slider position.
    //
    // At mix=50 (t≈0.71): decay ~11 s, room 0 dB — matches iOS cathedral feel.
    // At mix=100 (t=1.0):  decay 20 s, fully cavernous.
    private fun applyReverbLevel(mix: Float) {
        val er = envReverb ?: return
        if (mix <= 0f) { runCatching { er.enabled = false }; return }
        runCatching {
            val t = sqrt(mix / 100f)                        // 0..1, perceptually linear
            er.roomLevel   = ((-4000f + t * 4000f).toInt()).coerceIn(-9000, 0).toShort()
            er.reverbLevel = ((-2000f + t * 4000f).toInt()).coerceIn(-9000, 2000).toShort()
            // Decay: 2 s baseline, grows to 20 s at full slider (cathedral range)
            er.decayTime   = (2000 + (t * 18000f).toInt()).coerceIn(100, 20000)
            er.diffusion   = 1000.toShort()                 // always max spread
            er.density     = 1000.toShort()                 // always max density
            er.enabled     = true
        }
    }

    // Launches coroutines that create real looping MediaPlayer instances for
    // each echo/delay tap. These persist until the note is stopped so the
    // effect is heard for the full lifetime of the drone, not just at onset.
    //
    // Phase-correct seeking: after the wait period, we read the main player's
    // current position and seek each effect copy to (mainPos − tapDelay) % duration.
    // This means the copy is always playing audio that is exactly tapDelay ms
    // behind the main player — a true echo rather than a chorus of identical
    // copies starting from the top of the file.
    private fun CoroutineScope.launchEffectPlayers(
        noteName: String, volume: Float, pitch: Float
    ) {
        if (echoMix <= 0f) return
        val duration = noteDurations["fsharp"] ?: 0L

        echoJobs[noteName] = launch {
            // Echo: up to 6 decaying looping copies, each spaced echoDelayMs apart.
            // Decay factor 0.68 keeps each tap at 68 % of the previous.
            if (echoMix > 0f) launch {
                var tapVol = (volume * echoMix * 2f).coerceIn(0f, 1f)
                var tapNum = 0
                while (tapNum < 6 && tapVol >= 0.03f) {
                    delay(echoDelayMs.toLong())
                    if (!isActive) break
                    val ep = buildPlayer(tapVol, pitch) ?: break
                    // Seek to the phase-correct position so this copy sounds like
                    // a true echo of the main player echoDelayMs ago, not a chorus.
                    if (duration > 0) {
                        val mainPos  = activePlayers[noteName]?.currentPosition?.toLong() ?: 0L
                        val echoPos  = ((mainPos - echoDelayMs.toLong()) % duration + duration) % duration
                        runCatching { ep.seekTo(echoPos.toInt()) }
                    }
                    ep.isLooping = true
                    ep.start()
                    effectPlayers.getOrPut(noteName) { mutableListOf() }.add(ep)
                    tapVol *= 0.68f
                    tapNum++
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // MediaSession — lock screen / headphone / steering-wheel controls
    // -------------------------------------------------------------------------

    private fun setupMediaSession() {
        val ctx = appContext ?: return
        val session = MediaSessionCompat(ctx, "TanpuraKings").apply {
            // setFlags() is deprecated — FLAG_HANDLES_MEDIA_BUTTONS and
            // FLAG_HANDLES_TRANSPORT_CONTROLS are set implicitly by setCallback().
            setCallback(object : MediaSessionCompat.Callback() {
                /** Pause: snapshot active notes then stop so Play can restore them. */
                override fun onPause() {
                    if (activePlayers.isNotEmpty()) {
                        pausedNotesSnapshot = HashMap(noteVolumes)
                        pausedForFocus = false
                    }
                    stopAllNotes()
                }

                /** Stop: same as pause for a drone — snapshot then stop. */
                override fun onStop() {
                    if (activePlayers.isNotEmpty()) {
                        pausedNotesSnapshot = HashMap(noteVolumes)
                        pausedForFocus = false
                    }
                    stopAllNotes()
                }

                /** Play: restore whichever notes were playing before pause/stop. */
                override fun onPlay() {
                    val snap = pausedNotesSnapshot.toMap()
                    if (snap.isEmpty()) return
                    pausedNotesSnapshot = emptyMap()
                    pausedForFocus = false
                    // Restart the foreground service so its notification is live
                    // before the first note is created (avoids a gap where the
                    // service is absent between onPlay() and playNote() launching).
                    startPlaybackService()
                    val master = currentMasterVolume
                    val scope = coroutineScope ?: return
                    scope.launch { snap.forEach { (name, nv) -> playNote(name, master, nv) } }
                }

                override fun onMediaButtonEvent(intent: Intent): Boolean {
                    // Let MediaButtonReceiver handle the routing; we rely on
                    // onStop/onPause for the actual work.
                    return super.onMediaButtonEvent(intent)
                }

                /**
                 * Android Auto calls this when the user taps a browse item.
                 * [mediaId] matches one of the note keys defined in
                 * [TanpuraMediaBrowserService] ("c", "csharp", …, "b").
                 */
                override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) {
                    val validKeys = listOf(
                        "c","csharp","d","dsharp","e","f",
                        "fsharp","g","gsharp","a","asharp","b"
                    )
                    if (mediaId != null && mediaId in validKeys) {
                        playNote(mediaId, currentMasterVolume)
                    }
                }
            })
            // Initial state — nothing playing yet
            setPlaybackState(
                PlaybackStateCompat.Builder()
                    .setState(PlaybackStateCompat.STATE_NONE, 0L, 1f)
                    .build()
            )
            isActive = true
        }
        mediaSession = session
    }

    /**
     * Refreshes the MediaSession playback state and metadata to match the
     * current set of active notes. Call this whenever notes are added or removed.
     */
    private fun updateMediaSession() {
        val session   = mediaSession ?: return
        val isPlaying = activePlayers.isNotEmpty()
        val hasPause  = pausedNotesSnapshot.isNotEmpty()

        // Show last-played notes even when paused so the user knows what
        // will resume when they hit Play.
        val artist = when {
            isPlaying -> activePlayers.keys.sorted().joinToString(" • ")
            hasPause  -> pausedNotesSnapshot.keys.sorted().joinToString(" • ")
            else      -> "Drone"
        }

        // STATE_PAUSED keeps the Play button visible on the lock screen;
        // STATE_STOPPED would hide the media widget on some OEMs.
        val state = when {
            isPlaying -> PlaybackStateCompat.STATE_PLAYING
            hasPause  -> PlaybackStateCompat.STATE_PAUSED
            else      -> PlaybackStateCompat.STATE_STOPPED
        }

        session.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_STOP or
                    PlaybackStateCompat.ACTION_PAUSE or
                    PlaybackStateCompat.ACTION_PLAY or
                    PlaybackStateCompat.ACTION_PLAY_PAUSE or
                    PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID
                )
                .setState(state, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1f)
                .build()
        )

        session.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE,  "Tanpura Kings")
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist)
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM,  "Tanpura Drone")
                // Duration unknown (continuous drone) — omit so the lock
                // screen doesn't show a progress bar.
                .build()
        )
    }

    // -------------------------------------------------------------------------
    // Foreground-service lifecycle helpers
    // -------------------------------------------------------------------------

    /**
     * Start [AudioPlaybackService] so Android treats this process as a
     * foreground service and won't kill it while audio is playing in the
     * background.  Safe to call repeatedly — the service ignores duplicate
     * start commands.
     */
    private fun startPlaybackService() {
        val ctx = appContext ?: return
        try {
            val intent = Intent(ctx, AudioPlaybackService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(intent)
            } else {
                ctx.startService(intent)
            }
        } catch (e: Exception) {
            // ForegroundServiceStartNotAllowedException on Android 12+ if
            // the system blocks the start; SecurityException on Android 14+
            // if service-type permissions are missing. Audio still plays —
            // only the background-keep-alive notification is lost.
            Log.w("AudioManager", "startPlaybackService failed: $e")
        }
    }

    /**
     * Stop [AudioPlaybackService] once no notes remain — dismisses the
     * persistent notification and lets Android reclaim the foreground-service
     * slot.
     */
    private fun stopPlaybackService() {
        val ctx = appContext ?: return
        ctx.stopService(Intent(ctx, AudioPlaybackService::class.java))
    }

    fun release() {
        stopMetronome()
        stopAllNotes()
        subOctavePlayers.values.forEach { sp -> runCatching { sp.stop() }; sp.release() }
        subOctavePlayers.clear()
        warmthBoosts.values.forEach { runCatching { it.release() } }
        warmthBoosts.clear()
        loudnessEnhancers.values.forEach { runCatching { it.release() } }
        loudnessEnhancers.clear()
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
        noteDurations.clear()
        mediaSession?.isActive = false
        mediaSession?.release()
        mediaSession = null
        appContext    = null
        isInitialized = false
        Log.d("AudioManager", "AudioManager released")
    }
}

// ------------------------------
// TunerManager — real-time chromatic pitch detector
// ------------------------------
object TunerManager {
    private val _frequency    = MutableStateFlow(0f)
    private val _noteName     = MutableStateFlow("—")
    private val _cents        = MutableStateFlow(0f)
    private val _isListening  = MutableStateFlow(false)
    private val _inputLevel   = MutableStateFlow(0f)
    private val _detected     = MutableStateFlow(false)
    private val _centsHistory = MutableStateFlow<List<Float>>(emptyList())

    val frequency:    StateFlow<Float>        = _frequency.asStateFlow()
    val noteName:     StateFlow<String>       = _noteName.asStateFlow()
    val cents:        StateFlow<Float>        = _cents.asStateFlow()
    @Suppress("unused")   // Public API — used by Android Auto / future callers
    val isListening:  StateFlow<Boolean>      = _isListening.asStateFlow()
    val inputLevel:   StateFlow<Float>        = _inputLevel.asStateFlow()
    val detected:     StateFlow<Boolean>      = _detected.asStateFlow()
    val centsHistory: StateFlow<List<Float>>  = _centsHistory.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var audioRecord: AudioRecord? = null
    private var processingJob: Job? = null

    // Note stabilisation: require 3 consecutive frames of the same note
    private var pendingNote      = ""
    private var pendingNoteCount = 0

    private const val SAMPLE_RATE = 44100
    private const val FRAME_SIZE  = 2048

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
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
        audioRecord         = null
        _isListening.value  = false
        _detected.value     = false
        _inputLevel.value   = 0f
        _frequency.value    = 0f
        _cents.value        = 0f
        _noteName.value     = "—"
        _centsHistory.value = emptyList()
        pendingNote         = ""
        pendingNoteCount    = 0
    }

    private fun process(samples: FloatArray, count: Int) {
        var sumSq = 0f
        for (i in 0 until count) sumSq += samples[i] * samples[i]
        val rms   = sqrt(sumSq / count)
        val level = minOf(1f, rms * 5f)

        if (rms < 0.005f) {
            _inputLevel.value = level
            _detected.value   = false
            appendHistory(Float.NaN)
            return
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
        if (bestIdx < 0) {
            _inputLevel.value = level; _detected.value = false
            appendHistory(Float.NaN); return
        }

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
            _inputLevel.value = level; _detected.value = false
            appendHistory(Float.NaN); return
        }

        val (note, centsVal) = noteAndCents(freq.toDouble())
        val alpha       = 0.85f
        val smoothFreq  = if (_frequency.value == 0f) freq
            else _frequency.value * alpha + freq * (1f - alpha)
        val smoothCents = _cents.value * alpha + centsVal.toFloat() * (1f - alpha)

        // 3-frame note hysteresis — prevents flicker at note boundaries
        if (note == pendingNote) pendingNoteCount++
        else { pendingNote = note; pendingNoteCount = 1 }
        if (pendingNoteCount >= 3) _noteName.value = note

        _frequency.value  = smoothFreq
        _cents.value      = smoothCents
        _inputLevel.value = level
        _detected.value   = true
        appendHistory(smoothCents)
    }

    private fun appendHistory(value: Float) {
        val cur = _centsHistory.value
        val next = if (cur.size >= 150) cur.drop(1) + value else cur + value
        _centsHistory.value = next
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
    subOctaveMix: MutableState<Float>,
    warmth: MutableState<Float>,
    compressionAmount: MutableState<Float>,
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

        EffectSectionHeader("Octave Blend")
        SliderWithLabel("Sub Octave", subOctaveMix.value, { subOctaveMix.value = it }, 0f..1f,
            Color(0xFF9966FF)) { "${(it * 100).toInt()}%" }

        EffectSectionHeader("Warmth")
        SliderWithLabel("Saturation", warmth.value, { warmth.value = it }, 0f..1f,
            Color(0xFFFF8C1A)) { "${(it * 100).toInt()}%" }

        EffectSectionHeader("Compressor")
        SliderWithLabel("Amount", compressionAmount.value, { compressionAmount.value = it }, 0f..1f,
            Color(0xFFE5334D)) { "${(it * 100).toInt()}%" }
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
// OctavePickerCard — dropdown to choose Lower / Mid / Higher octave.
// Mirrors iOS OctavePickerView with the same orange accent style.
// ------------------------------
@Composable
fun OctavePickerCard(selectedOctave: MutableState<Int>) {
    val accent  = Color(0xFFFFA500)
    data class OctaveOption(val id: Int, val label: String)
    val options = listOf(
        OctaveOption(-1, "Lower Octave"),
        OctaveOption( 0, "Mid Octave"),
        OctaveOption( 1, "Higher Octave")
    )
    val currentLabel = options.first { it.id == selectedOctave.value }.label
    var expanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xDD000000), shape = RoundedCornerShape(8.dp))
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "Octave",
            color      = Color.White,
            fontSize   = 15.sp,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.weight(1f))

        Box {
            Row(
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(50.dp))
                    .border(1.dp, accent.copy(alpha = 0.65f), RoundedCornerShape(50.dp))
                    .clickable { expanded = true }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(currentLabel, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Text("⌃⌄", color = accent, fontSize = 11.sp)
            }

            DropdownMenu(
                expanded          = expanded,
                onDismissRequest  = { expanded = false }
            ) {
                options.forEach { opt ->
                    DropdownMenuItem(
                        text   = {
                            Text(
                                text  = if (selectedOctave.value == opt.id) "✓  ${opt.label}" else opt.label,
                                color = if (selectedOctave.value == opt.id) accent else Color.Unspecified
                            )
                        },
                        onClick = {
                            selectedOctave.value = opt.id
                            expanded = false
                        }
                    )
                }
            }
        }
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
// TunerDial — 360° chromatic wheel with all 12 notes.
// Each note occupies a 30° segment. Active note is highlighted with
// accuracy colour. Inner ring shows ±50¢ colour zones.
// The needle rotates from centre to the exact note+cents position.
// ------------------------------
@Composable
private fun TunerDial(
    noteName: String,
    cents: Float,
    inTune: Boolean,
    detected: Boolean,
    modifier: Modifier = Modifier
) {
    val noteNames = listOf("C","C#","D","D#","E","F","F#","G","G#","A","A#","B")
    val base      = noteName.takeWhile { !it.isDigit() && it != '-' }
    val noteIndex = if (detected) noteNames.indexOf(base).takeIf { it >= 0 } ?: -1 else -1

    // Animate the needle angle (unwrapped degrees, shortest-path)
    // Each note = 30°; ±50¢ → ±15° within its slot.
    val targetAngle = if (noteIndex >= 0) {
        noteIndex * 30f + (cents.coerceIn(-50f, 50f) / 50f) * 15f
    } else 0f

    var displayAngle by remember { mutableFloatStateOf(0f) }

    // Shortest-path update whenever target changes
    LaunchedEffect(noteIndex, cents, detected) {
        if (!detected || noteIndex < 0) return@LaunchedEffect
        val cur   = displayAngle % 360f
        var delta = targetAngle - cur
        if (delta >  180f) delta -= 360f
        if (delta < -180f) delta += 360f
        displayAngle += delta
    }

    val animAngle by animateFloatAsState(
        targetValue   = displayAngle,
        animationSpec = spring(dampingRatio = 0.68f, stiffness = 220f),
        label         = "wheel_needle"
    )

    // Dial angle → Compose Canvas degrees (3-o'clock = 0, CW+)
    // dialDeg 0 = 12-o'clock = -90° in Canvas, so canvasDeg = dialDeg - 90
    fun dialToCanvas(d: Float) = d - 90f   // Compose drawArc startAngle convention

    val paint = remember {
        NativePaint(NativePaint.ANTI_ALIAS_FLAG).apply {
            textAlign = NativePaint.Align.CENTER
            typeface  = Typeface.DEFAULT_BOLD
        }
    }

    Canvas(modifier = modifier) {
        val cx     = size.width  / 2f
        val cy     = size.height / 2f
        val outerR = minOf(cx, cy) - 4.dp.toPx()

        // Segment band metrics
        val segArcR = outerR - 10.dp.toPx()
        val segArcW = 26.dp.toPx()
        val segInner = segArcR - segArcW / 2f - 2.dp.toPx()

        // Inner ±50¢ ring metrics
        val centsArcR = segInner - 10.dp.toPx()
        val centsArcW = 10.dp.toPx()

        // ── Background disc ──
        drawCircle(Color.Black.copy(alpha = 0.70f), radius = outerR, center = Offset(cx, cy))
        drawCircle(Color.White.copy(alpha = 0.10f), radius = outerR, center = Offset(cx, cy),
            style = Stroke(width = 1.5.dp.toPx()))

        // ── 12 Note segments ──
        for (i in 0 until 12) {
            val isActive  = (i == noteIndex && detected)
            val midDial   = i * 30f
            val startDial = midDial - 15f
            val isNatural = !noteNames[i].contains("#")

            // Segment colour
            val segColor = when {
                isActive && abs(cents) < 5f  -> Color(0xFF2EE16B)
                isActive && abs(cents) < 15f -> Color(0xFFFFD210)
                isActive                     -> Color(0xFFFF4747)
                isNatural                    -> Color.White.copy(alpha = 0.13f)
                else                         -> Color.White.copy(alpha = 0.07f)
            }

            // Segment arc (30° each)
            drawArc(
                color      = segColor,
                startAngle = dialToCanvas(startDial),
                sweepAngle = 30f,
                useCenter  = false,
                topLeft    = Offset(cx - segArcR, cy - segArcR),
                size       = Size(segArcR * 2f, segArcR * 2f),
                style      = Stroke(width = segArcW, cap = StrokeCap.Butt)
            )

            // Divider line at segment boundary
            val divDial = dialToCanvas(midDial + 15f)
            val divRad  = divDial * (PI / 180.0)
            val dOuter  = segArcR + segArcW / 2f + 2.dp.toPx()
            val dInner  = segArcR - segArcW / 2f - 2.dp.toPx()
            drawLine(
                color       = Color.Black.copy(alpha = 0.80f),
                start       = Offset(cx + cos(divRad).toFloat() * dInner,
                                     cy + sin(divRad).toFloat() * dInner),
                end         = Offset(cx + cos(divRad).toFloat() * dOuter,
                                     cy + sin(divRad).toFloat() * dOuter),
                strokeWidth = 3.dp.toPx(),
                cap         = StrokeCap.Butt
            )

            // Centre tick inside segment
            val midRad  = dialToCanvas(midDial) * (PI / 180.0)
            val tOuter  = segArcR - segArcW / 2f - 4.dp.toPx()
            val tInner  = tOuter - (if (isActive) 14.dp.toPx() else 8.dp.toPx())
            drawLine(
                color       = Color.White.copy(alpha = if (isActive) 1f else 0.35f),
                start       = Offset(cx + cos(midRad).toFloat() * tInner,
                                     cy + sin(midRad).toFloat() * tInner),
                end         = Offset(cx + cos(midRad).toFloat() * tOuter,
                                     cy + sin(midRad).toFloat() * tOuter),
                strokeWidth = (if (isActive) 2.5f else 1.2f).dp.toPx(),
                cap         = StrokeCap.Round
            )

            // Note label
            val labelR    = outerR * 0.58f
            val labelRad  = dialToCanvas(midDial) * (PI / 180.0)
            val labelX    = cx + cos(labelRad).toFloat() * labelR
            val labelY    = cy + sin(labelRad).toFloat() * labelR
            val labelSize = (if (isNatural) 15f else 11f).dp.toPx()

            paint.textSize  = labelSize
            paint.color = when {
                isActive && abs(cents) < 5f  -> "#2EE16B".toColorInt()
                isActive && abs(cents) < 15f -> "#FFD210".toColorInt()
                isActive                     -> "#FF4747".toColorInt()
                isNatural                    -> android.graphics.Color.argb(153, 255, 255, 255)
                else                         -> android.graphics.Color.argb(97, 255, 255, 255)
            }
            drawContext.canvas.nativeCanvas.drawText(
                noteNames[i], labelX, labelY + labelSize / 3f, paint
            )
        }

        // ── Inner ±50¢ colour-zone arc (centred on active note) ──
        if (detected && noteIndex >= 0) {
            val midDial = noteIndex * 30f
            data class CZone(val fromC: Float, val toC: Float, val col: Color)
            listOf(
                CZone(-50f, -15f, Color(0xFFFF4747).copy(alpha = 0.80f)),
                CZone(-15f,  -5f, Color(0xFFFFD210).copy(alpha = 0.80f)),
                CZone( -5f,   5f, Color(0xFF2EE16B).copy(alpha = 0.95f)),
                CZone(  5f,  15f, Color(0xFFFFD210).copy(alpha = 0.80f)),
                CZone( 15f,  50f, Color(0xFFFF4747).copy(alpha = 0.80f)),
            ).forEach { z ->
                val sAngle = dialToCanvas(midDial + z.fromC / 50f * 15f)
                val sweep  = (z.toC - z.fromC) / 50f * 15f
                drawArc(
                    color      = z.col,
                    startAngle = sAngle,
                    sweepAngle = sweep,
                    useCenter  = false,
                    topLeft    = Offset(cx - centsArcR, cy - centsArcR),
                    size       = Size(centsArcR * 2f, centsArcR * 2f),
                    style      = Stroke(width = centsArcW, cap = StrokeCap.Butt)
                )
            }

            // Tick marks at ±50, ±25, 0¢ within the active segment
            for (c in listOf(-50f, -25f, 0f, 25f, 50f)) {
                val isZero = c == 0f
                val tRad   = dialToCanvas(noteIndex * 30f + c / 50f * 15f) * (PI / 180.0)
                val tOut   = centsArcR + centsArcW / 2f + 2.dp.toPx()
                val tIn    = centsArcR - centsArcW / 2f - (if (isZero) 6.dp.toPx() else 3.dp.toPx())
                drawLine(
                    color       = Color.White.copy(alpha = if (isZero) 0.85f else 0.50f),
                    start       = Offset(cx + cos(tRad).toFloat() * tIn,
                                         cy + sin(tRad).toFloat() * tIn),
                    end         = Offset(cx + cos(tRad).toFloat() * tOut,
                                         cy + sin(tRad).toFloat() * tOut),
                    strokeWidth = (if (isZero) 2.2f else 1.2f).dp.toPx(),
                    cap         = StrokeCap.Round
                )
            }
        }

        // ── Hub disc ──
        val hubR = outerR * 0.22f
        drawCircle(Color(0xFF111111), radius = hubR, center = Offset(cx, cy))
        drawCircle(Color.White.copy(alpha = 0.14f), radius = hubR, center = Offset(cx, cy),
            style = Stroke(width = 1.dp.toPx()))

        // ── Needle ──
        val needleRad = dialToCanvas(animAngle) * (PI / 180.0)
        val cosN = cos(needleRad).toFloat()
        val sinN = sin(needleRad).toFloat()
        val tipLen  = segInner * 0.88f
        val tailLen = 13.dp.toPx()
        val nc = when {
            !detected -> Color.Gray.copy(alpha = 0.35f)
            inTune    -> Color(0xFF2EE16B)
            else      -> Color.White
        }
        drawLine(
            color       = nc,
            start       = Offset(cx - cosN * tailLen, cy - sinN * tailLen),
            end         = Offset(cx + cosN * tipLen,  cy + sinN * tipLen),
            strokeWidth = 3.dp.toPx(),
            cap         = StrokeCap.Round
        )

        // ── Pivot dot ──
        val pivR = 7.dp.toPx()
        drawCircle(Color(0xFFFFA500), radius = pivR, center = Offset(cx, cy))
        drawCircle(Color.White.copy(alpha = 0.75f), radius = pivR,
            center = Offset(cx, cy), style = Stroke(width = 1.5.dp.toPx()))
    }
}

// ------------------------------
// TunerScreen
// ------------------------------
@Composable
fun TunerScreen() {
    val noteName     by TunerManager.noteName.collectAsState()
    val cents        by TunerManager.cents.collectAsState()
    val detected     by TunerManager.detected.collectAsState()
    val inputLevel   by TunerManager.inputLevel.collectAsState()
    val centsHistory by TunerManager.centsHistory.collectAsState()

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        @SuppressLint("MissingPermission")  // Permission just granted above
        if (granted) TunerManager.start()
    }

    LaunchedEffect(Unit) { launcher.launch(Manifest.permission.RECORD_AUDIO) }

    val inTune  = detected && abs(cents) < 5f
    val isFlat  = detected && cents < -5f
    val isSharp = detected && cents >  5f

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF121212))
            .padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(14.dp))

        // ── Header ──
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("CHROMATIC TUNER", color = Color(0xFF555555), fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold, letterSpacing = 2.sp)
            Text("A₄ = 440 Hz", color = Color(0xFFFFA500), fontSize = 11.sp,
                fontWeight = FontWeight.Medium)
        }

        Spacer(modifier = Modifier.height(10.dp))

        // ── InsTuner-style Tuning Status Card ──
        TuningStatusCard(
            noteName = noteName,
            cents    = cents,
            detected = detected,
            inTune   = inTune,
            isFlat   = isFlat,
            isSharp  = isSharp,
            modifier = Modifier.fillMaxWidth().height(130.dp)
        )

        Spacer(modifier = Modifier.height(8.dp))

        // ── 360° Chromatic Wheel ──
        TunerDial(
            noteName = noteName,
            cents    = cents,
            inTune   = inTune,
            detected = detected,
            modifier = Modifier.fillMaxWidth().aspectRatio(1f)
        )

        Spacer(modifier = Modifier.height(8.dp))

        // ── Input level bar ──
        Box(
            modifier = Modifier
                .fillMaxWidth().padding(horizontal = 20.dp)
                .height(5.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(Color.White.copy(alpha = 0.08f))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(inputLevel.coerceIn(0f, 1f))
                    .background(
                        Brush.horizontalGradient(
                            listOf(Color(0xFF21D45A), Color(0xFFFFD210), Color(0xFFFF4040))
                        ),
                        RoundedCornerShape(3.dp)
                    )
            )
        }

        Spacer(modifier = Modifier.height(6.dp))

        // ── Pitch history graph ──
        TunerHistoryGraph(
            history  = centsHistory,
            modifier = Modifier.fillMaxWidth().height(66.dp)
        )

        Spacer(modifier = Modifier.height(6.dp))

        // ── Status dot ──
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Box(modifier = Modifier.size(6.dp).background(
                if (detected) Color(0xFF21D45A) else Color(0xFF444444), CircleShape))
            Spacer(modifier = Modifier.width(5.dp))
            Text(
                if (detected) "Signal detected" else "Listening for signal…",
                color = Color(0xFF555555), fontSize = 11.sp, fontWeight = FontWeight.Medium
            )
        }

        Spacer(modifier = Modifier.weight(1f))
    }
}

// InsTuner-style status card:
//  • Full green when in tune ("Got it! ✓")
//  • Dark + left red bar when flat ("Tune Up ↑")
//  • Dark + right red bar when sharp ("Tune Down ↓")
//  • ♭ on left edge, # on right edge
@Composable
private fun TuningStatusCard(
    noteName: String,
    cents: Float,
    detected: Boolean,
    inTune: Boolean,
    isFlat: Boolean,
    isSharp: Boolean,
    modifier: Modifier = Modifier
) {
    val baseName    = noteName.takeWhile { !it.isDigit() && it != '-' }
    val naturalNote = baseName.replace("#", "").replace("b", "")
    val accidental  = when { baseName.contains("#") -> "♯"; baseName.contains("b") -> "♭"; else -> "" }
    val octaveStr   = noteName.drop(baseName.length)

    // Bar fraction proportional to out-of-tune amount (0–50¢ → 0–42% of card width)
    val barFraction = (abs(cents).coerceIn(0f, 50f) / 50f * 0.42f)

    val statusText = when {
        !detected -> "Listening…"
        inTune    -> "Got it! ✓"
        isFlat    -> "Tune Up  ↑"
        else      -> "Tune Down  ↓"
    }

    val bgColor = animateColorAsState(
        targetValue = if (inTune) Color(0xFF1DB954) else Color(0xFF1C1C1C),
        animationSpec = tween(durationMillis = 250),
        label = "card_bg"
    ).value

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(bgColor)
    ) {
        // Red direction bar
        if (detected && !inTune) {
            val animBar by animateFloatAsState(
                targetValue   = barFraction,
                animationSpec = tween(80),
                label         = "bar"
            )
            if (isFlat) {
                // Left red bar (too flat)
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(animBar)
                        .align(Alignment.CenterStart)
                        .background(
                            Color(0xFFE03030).copy(alpha = 0.82f),
                            RoundedCornerShape(topStart = 14.dp, bottomStart = 14.dp)
                        )
                )
            } else {
                // Right red bar (too sharp)
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(animBar)
                        .align(Alignment.CenterEnd)
                        .background(
                            Color(0xFFE03030).copy(alpha = 0.82f),
                            RoundedCornerShape(topEnd = 14.dp, bottomEnd = 14.dp)
                        )
                )
            }
        }

        // ♭ / # edge labels
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("♭", color = Color.White.copy(alpha = if (isFlat) 1f else 0.25f),
                fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text("#", color = Color.White.copy(alpha = if (isSharp) 1f else 0.25f),
                fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }

        // Note name + octave + status text
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    text = if (detected) naturalNote else "—",
                    color = Color.White,
                    fontSize = 72.sp,
                    fontWeight = FontWeight.Black,
                    lineHeight = 72.sp
                )
                if (detected) {
                    Column(modifier = Modifier.padding(top = 10.dp)) {
                        if (accidental.isNotEmpty()) {
                            Text(accidental, color = Color.White,
                                fontSize = 26.sp, fontWeight = FontWeight.Bold,
                                lineHeight = 26.sp)
                        }
                        if (octaveStr.isNotEmpty()) {
                            Text(octaveStr, color = Color.White.copy(alpha = 0.75f),
                                fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
                                lineHeight = 20.sp)
                        }
                    }
                }
            }
            Text(
                text = statusText,
                color = Color.White.copy(alpha = 0.88f),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold
            )
        }

        // Cents badge bottom-right
        if (detected) {
            Text(
                text = String.format(Locale.getDefault(), "%+.0f¢", cents),
                color = Color.White.copy(alpha = 0.55f),
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.align(Alignment.BottomEnd).padding(10.dp)
            )
        }
    }
}

// Pitch history graph — scrolling line chart matching iOS PitchHistoryGraph
@Composable
private fun TunerHistoryGraph(history: List<Float>, modifier: Modifier = Modifier) {
    Box(modifier = modifier) {
        Canvas(modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp))) {
            val w    = size.width
            val h    = size.height
            val midY = h / 2f
            val range = 50f

            // Background
            drawRect(Color.Black.copy(alpha = 0.28f))

            // ±15¢ yellow band
            val y15t = midY - h / 2f * (15f / range)
            val band15H = h / 2f * (15f / range) * 2f
            drawRect(Color(0xFFFFD210).copy(alpha = 0.07f), topLeft = Offset(0f, y15t), size = Size(w, band15H))

            // ±5¢ green band
            val y5t = midY - h / 2f * (5f / range)
            val band5H = h / 2f * (5f / range) * 2f
            drawRect(Color(0xFF2EE16B).copy(alpha = 0.12f), topLeft = Offset(0f, y5t), size = Size(w, band5H))

            // Centre line
            drawLine(Color.White.copy(alpha = 0.18f), Offset(0f, midY), Offset(w, midY), strokeWidth = 1f)

            // Pitch trace — break at NaN
            if (history.isEmpty()) return@Canvas
            val cnt   = history.size
            val xStep = w / maxOf(cnt - 1, 1).toFloat()
            val path  = androidx.compose.ui.graphics.Path()
            var penDown = false
            history.forEachIndexed { i, v ->
                if (v.isNaN()) { penDown = false; return@forEachIndexed }
                val x  = i * xStep
                val cy = midY - (v.coerceIn(-range, range) / range) * (h / 2f)
                if (penDown) path.lineTo(x, cy) else { path.moveTo(x, cy); penDown = true }
            }
            drawPath(path, Color.White.copy(alpha = 0.88f),
                style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        // Labels row
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 3.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("+50¢", color = Color.White.copy(alpha = 0.28f), fontSize = 8.sp)
            Text("PITCH HISTORY", color = Color.White.copy(alpha = 0.28f), fontSize = 8.sp, letterSpacing = 1.sp)
            Text("-50¢", color = Color.White.copy(alpha = 0.28f), fontSize = 8.sp)
        }
    }
}

// ------------------------------
// AdMob — Interstitial Ad Manager
// ------------------------------
object InterstitialAdManager {
    private var interstitialAd: InterstitialAd? = null
    private var lastShowTime: Long = 0L
    private const val MIN_INTERVAL_MS = 180_000L // 3 minutes between interstitials

    /** True when an interstitial is loaded and ready to display. */
    val isReady: Boolean get() = interstitialAd != null

    fun load(context: Context) {
        if (interstitialAd != null) return
        try {
            val adRequest = AdRequest.Builder().build()
            InterstitialAd.load(
                context,
                "ca-app-pub-3492509358962490/9258488049",
                adRequest,
                object : InterstitialAdLoadCallback() {
                    override fun onAdLoaded(ad: InterstitialAd) {
                        interstitialAd = ad
                    }
                    override fun onAdFailedToLoad(error: LoadAdError) {
                        interstitialAd = null
                    }
                }
            )
        } catch (e: Exception) {
            Log.w("AdMob", "Interstitial load failed: $e")
        }
    }

    fun showIfReady(activity: Activity, onDismissed: () -> Unit = {}, ignoreCooldown: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!ignoreCooldown && now - lastShowTime < MIN_INTERVAL_MS) { onDismissed(); return }
        val ad = interstitialAd
        if (ad != null) {
            ad.fullScreenContentCallback = object : FullScreenContentCallback() {
                override fun onAdDismissedFullScreenContent() {
                    interstitialAd = null
                    lastShowTime = System.currentTimeMillis()
                    onDismissed()
                    // Pre-load next
                    load(activity)
                }
                override fun onAdFailedToShowFullScreenContent(error: com.google.android.gms.ads.AdError) {
                    interstitialAd = null
                    onDismissed()
                    load(activity)
                }
            }
            ad.show(activity)
        } else {
            onDismissed()
            load(activity)
        }
    }
}

// ------------------------------
// AdMob — Banner Ad Composable
// ------------------------------
@Composable
fun BannerAdView(modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier
            .fillMaxWidth()
            .height(50.dp),
        factory = { ctx ->
            try {
                AdView(ctx).apply {
                    setAdSize(AdSize.BANNER)
                    adUnitId = "ca-app-pub-3492509358962490/2717130426"
                    loadAd(AdRequest.Builder().build())
                }
            } catch (e: Exception) {
                Log.w("AdMob", "BannerAdView init failed: $e")
                // Return an empty View so the layout doesn't crash
                android.view.View(ctx)
            }
        }
    )
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
    val subOctaveMix      = remember { mutableFloatStateOf(0f) }
    val warmth            = remember { mutableFloatStateOf(0f) }
    val compressionAmount = remember { mutableFloatStateOf(0f) }

    // New: 3-band EQ + stereo width
    val eqLow       = remember { mutableFloatStateOf(0f) }
    val eqMid       = remember { mutableFloatStateOf(0f) }
    val eqHigh      = remember { mutableFloatStateOf(0f) }
    val stereoWidth = remember { mutableFloatStateOf(0.5f) }

    // New: metronome
    val metronomeOn     = remember { mutableStateOf(false) }
    val metronomeBpm    = remember { mutableFloatStateOf(80f) }
    val metronomeVolume = remember { mutableFloatStateOf(0.7f) }

    // Octave selector (-1 = Lower, 0 = Mid, +1 = Higher), persisted via SharedPreferences.
    val prefs = context.getSharedPreferences("TanpuraKingsPrefs", Context.MODE_PRIVATE)
    val selectedOctave = remember { mutableIntStateOf(prefs.getInt("selectedOctave", 0)) }

    DisposableEffect(Unit) {
        AudioManager.init(context)
        // Restore persisted octave into the audio engine on every launch.
        AudioManager.updateOctave(selectedOctave.intValue)
        onDispose { AudioManager.release() }
    }

    // Pre-load the first interstitial ad
    LaunchedEffect(Unit) {
        InterstitialAdManager.load(context)
    }

    LaunchedEffect(masterVolume.floatValue) {
        AudioManager.updateMasterVolume(masterVolume.floatValue)
    }

    LaunchedEffect(reverb.floatValue, fineTune.floatValue, echoMix.floatValue, echoDelay.floatValue) {
        AudioManager.updateEffects(
            reverb.floatValue, fineTune.floatValue,
            echoMix.floatValue, echoDelay.floatValue
        )
    }
    LaunchedEffect(subOctaveMix.floatValue) {
        AudioManager.updateOctaveBlend(subOctaveMix.floatValue)
    }
    LaunchedEffect(warmth.floatValue) {
        AudioManager.updateWarmth(warmth.floatValue)
    }
    LaunchedEffect(compressionAmount.floatValue) {
        AudioManager.updateCompressor(compressionAmount.floatValue)
    }

    LaunchedEffect(eqLow.floatValue, eqMid.floatValue, eqHigh.floatValue) {
        AudioManager.updateEQ(eqLow.floatValue, eqMid.floatValue, eqHigh.floatValue)
    }
    LaunchedEffect(stereoWidth.floatValue) {
        AudioManager.updateStereoWidth(stereoWidth.floatValue)
    }
    LaunchedEffect(metronomeOn.value) {
        if (metronomeOn.value) AudioManager.startMetronome() else AudioManager.stopMetronome()
    }
    LaunchedEffect(metronomeBpm.floatValue) {
        AudioManager.setMetronomeBPM(metronomeBpm.floatValue)
    }
    LaunchedEffect(metronomeVolume.floatValue) {
        AudioManager.setMetronomeVolume(metronomeVolume.floatValue)
    }
    LaunchedEffect(selectedOctave.intValue) {
        AudioManager.updateOctave(selectedOctave.intValue)
        prefs.edit().putInt("selectedOctave", selectedOctave.intValue).apply()
    }

    var selectedTab by remember { mutableIntStateOf(0) }
    // When true, the tab-transition loading screen is visible.
    var showTabTransition by remember { mutableStateOf(false) }
    // The tab we're navigating TO (used to commit the switch after the ad).
    var pendingTab by remember { mutableIntStateOf(0) }

    // Commit side-effects when the tab actually changes.
    LaunchedEffect(selectedTab) {
        when (selectedTab) {
            1 -> {  // Tuner is now active
                metronomeOn.value = false
                AudioManager.stopMetronome()
                AudioManager.stopAllNotes()
                activeNotes.value      = emptySet()
                activeNoteVolumes.value = emptyMap()
            }
            0 -> TunerManager.stop()
        }
    }

    Scaffold(
        containerColor = Color.Transparent,
        bottomBar = {
            Column {
                NavigationBar(containerColor = Color.Black.copy(alpha = 0.85f)) {
                    NavigationBarItem(
                        selected = selectedTab == 0,
                        onClick  = {
                            if (selectedTab != 0) {
                                // Show transition screen with ad before switching back to Drone.
                                pendingTab = 0
                                showTabTransition = true
                            }
                        },
                        icon     = { Text("♪", fontSize = 22.sp, color = if (selectedTab == 0) Color(0xFFFFA500) else Color(0xFF888888)) },
                        label    = { Text("Drone", color = if (selectedTab == 0) Color(0xFFFFA500) else Color(0xFF888888)) }
                    )
                    NavigationBarItem(
                        selected = selectedTab == 1,
                        onClick  = {
                            if (selectedTab != 1) {
                                // Show transition screen with ad before switching.
                                pendingTab = 1
                                showTabTransition = true
                            }
                        },
                        icon     = { Text("🎤", fontSize = 18.sp) },
                        label    = { Text("Tuner", color = if (selectedTab == 1) Color(0xFFFFA500) else Color(0xFF888888)) }
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            when (selectedTab) {
                0 -> DroneScreen(
                    activeNotes, activeNoteVolumes, masterVolume,
                    reverb, fineTune, echoMix, echoDelay,
                    subOctaveMix, warmth, compressionAmount,
                    eqLow, eqMid, eqHigh, stereoWidth,
                    metronomeOn, metronomeBpm, metronomeVolume,
                    selectedOctave
                )
                1 -> TunerScreen()
            }

            // Tab-transition overlay — shown when switching between Drone and Tuner.
            if (showTabTransition) {
                val goingToDrone = pendingTab == 0
                TabTransitionScreen(
                    stages  = if (goingToDrone) droneLoadingStages else tunerLoadingStages,
                    emoji   = if (goingToDrone) "♪" else "🎤",
                    title   = if (goingToDrone) "DRONE PLAYER" else "CHROMATIC TUNER",
                    onReady = {
                        showTabTransition = false
                        selectedTab = pendingTab
                    }
                )
            }
        }
    }
}

// ------------------------------
// TabTransitionScreen
// Shown when switching Drone → Tuner. Loads + shows an interstitial
// then calls onReady to complete the tab switch.
// ------------------------------
private val tunerLoadingStages = listOf(
    0.25f to "Stopping drone...",
    0.55f to "Starting microphone...",
    0.80f to "Loading advertisement...",
    1.00f to "Ready!"
)

private val droneLoadingStages = listOf(
    0.25f to "Stopping tuner...",
    0.55f to "Initializing drone...",
    0.80f to "Loading advertisement...",
    1.00f to "Ready!"
)

@Composable
fun TabTransitionScreen(
    stages: List<Pair<Float, String>>,
    emoji: String,
    title: String,
    onReady: () -> Unit
) {
    val context = LocalContext.current
    val activity = context as? Activity

    var progress by remember { mutableFloatStateOf(0f) }
    val animatedProgress by animateFloatAsState(
        targetValue = progress,
        animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing),
        label = "transitionProgress"
    )
    var stageLabel by remember { mutableStateOf(stages.first().second) }

    LaunchedEffect(Unit) {
        InterstitialAdManager.load(context)

        for ((target, label) in stages.dropLast(1)) {
            progress = target
            stageLabel = label
            delay(300)
        }

        // Poll for ad (up to 1.5s extra)
        var waited = 0L
        while (waited < 1500L && !InterstitialAdManager.isReady) {
            delay(100); waited += 100
        }

        progress = 1f
        stageLabel = "Ready!"
        delay(200)

        if (activity != null && InterstitialAdManager.isReady) {
            InterstitialAdManager.showIfReady(activity, onDismissed = { onReady() }, ignoreCooldown = true)
        } else {
            onReady()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0A1A)),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(280.dp)
                .background(
                    Brush.radialGradient(
                        listOf(Color(0xFF5500AA).copy(alpha = 0.4f), Color.Transparent)
                    )
                )
        )
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 40.dp)
        ) {
            Spacer(modifier = Modifier.weight(1f))
            Text(emoji, fontSize = 64.sp)
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = title,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 3.sp,
                color = Color.White
            )
            Spacer(modifier = Modifier.weight(1f))
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stageLabel,
                    fontSize = 12.sp,
                    color = Color(0xFFFFA500),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(Color.White.copy(alpha = 0.1f))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(animatedProgress)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(3.dp))
                            .background(
                                Brush.horizontalGradient(
                                    listOf(Color(0xFFFFA500), Color(0xFFFF6600))
                                )
                            )
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "${(animatedProgress * 100).toInt()}%",
                    fontSize = 11.sp,
                    color = Color(0xFF888888),
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = androidx.compose.ui.text.style.TextAlign.End
                )
            }
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

// ------------------------------
// DroneScreen — the scrolling drone UI (extracted from TanpuraKingsApp)
// ------------------------------
// Formats seconds → "MM:SS" or "H:MM:SS" when past one hour.
private fun formatElapsed(secs: Int): String {
    val h = secs / 3600
    val m = (secs % 3600) / 60
    val s = secs % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s)
    else "%02d:%02d".format(m, s)
}

/**
 * Pill-shaped badge showing how long the drone has been playing.
 * Green dot + "Playing" label when active; grey + "Stopped" when idle.
 * Resets to 00:00 whenever all notes are stopped.
 */
@Composable
private fun PlaybackTimerCard(elapsedSecs: Int, isPlaying: Boolean) {
    val green = Color(0xFF1DB954)
    Row(
        modifier = Modifier
            .background(Color.Black.copy(alpha = 0.42f), RoundedCornerShape(50.dp))
            .padding(horizontal = 18.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Indicator dot
        Box(
            modifier = Modifier
                .size(9.dp)
                .background(
                    color = if (isPlaying) green else Color.White.copy(alpha = 0.25f),
                    shape = CircleShape
                )
        )
        // "Playing" / "Stopped" label
        Text(
            text       = if (isPlaying) "Playing" else "Stopped",
            color      = if (isPlaying) Color.White else Color.White.copy(alpha = 0.35f),
            fontSize   = 12.sp,
            fontWeight = FontWeight.SemiBold
        )
        // Monospace clock
        Text(
            text       = formatElapsed(elapsedSecs),
            color      = if (isPlaying) Color.White else Color.White.copy(alpha = 0.35f),
            fontSize   = 20.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
fun DroneScreen(
    activeNotes: MutableState<Set<String>>,
    activeNoteVolumes: MutableState<Map<String, Float>>,
    masterVolume: MutableState<Float>,
    reverb: MutableState<Float>,
    fineTune: MutableState<Float>,
    echoMix: MutableState<Float>,
    echoDelay: MutableState<Float>,
    subOctaveMix: MutableState<Float>,
    warmth: MutableState<Float>,
    compressionAmount: MutableState<Float>,
    eqLow: MutableState<Float>,
    eqMid: MutableState<Float>,
    eqHigh: MutableState<Float>,
    stereoWidth: MutableState<Float>,
    metronomeOn: MutableState<Boolean>,
    metronomeBpm: MutableState<Float>,
    metronomeVolume: MutableState<Float>,
    selectedOctave: MutableState<Int>
) {
    // ── Playback timer state ──────────────────────────────────────────────────
    val isPlaying = activeNotes.value.isNotEmpty()
    var elapsedSecs by remember { mutableIntStateOf(0) }

    // Single LaunchedEffect keyed on isPlaying:
    //   • When a note starts (isPlaying → true): record start time, tick every second.
    //   • When all notes stop (isPlaying → false): cancel the loop, reset to 0.
    LaunchedEffect(isPlaying) {
        if (isPlaying) {
            val startMs = System.currentTimeMillis()
            while (true) {
                elapsedSecs = ((System.currentTimeMillis() - startMs) / 1000L).toInt()
                delay(1000L)
            }
        } else {
            elapsedSecs = 0
        }
    }
    // ─────────────────────────────────────────────────────────────────────────

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
        Spacer(modifier = Modifier.height(8.dp))
        PlaybackTimerCard(elapsedSecs = elapsedSecs, isPlaying = isPlaying)
        Spacer(modifier = Modifier.height(8.dp))
        AudioOutputButton()
        Spacer(modifier = Modifier.height(16.dp))
        OctavePickerCard(selectedOctave)
        Spacer(modifier = Modifier.height(8.dp))
        PianoView(activeNotes, activeNoteVolumes, masterVolume.value)
        Spacer(modifier = Modifier.height(16.dp))
        if (activeNoteVolumes.value.isNotEmpty()) {
            ActiveNotesVolumeView(activeNoteVolumes, masterVolume.value)
            Spacer(modifier = Modifier.height(16.dp))
        }
        MetronomePanel(metronomeOn, metronomeBpm, metronomeVolume)
        Spacer(modifier = Modifier.height(16.dp))
        EffectsPanel(reverb, fineTune, echoMix, echoDelay, subOctaveMix, warmth, compressionAmount, eqLow, eqMid, eqHigh, stereoWidth)
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
        try {
            MobileAds.initialize(this) {}
        } catch (e: Exception) {
            Log.w("AdMob", "MobileAds.initialize failed: $e")
        }
        setContent { AppRoot() }
    }
}

// ------------------------------
// OnboardingScreen
// ------------------------------
private data class OnboardingPage(val emoji: String, val title: String, val description: String)

private val onboardingPages = listOf(
    OnboardingPage("🎵", "Welcome to Tanpura Kings", "Your professional tanpura drone and chromatic tuner. Let's take a quick tour of everything you can do."),
    OnboardingPage("🎹", "Play the Drone", "Tap any key on the keyboard to start a continuous drone tone. Tap again to stop it. You can play multiple notes at once."),
    OnboardingPage("🎼", "Change Octave", "Switch between Lower (−1), Mid (0), and Higher (+1) octaves to match your vocal or instrument range."),
    OnboardingPage("🔊", "Per-Note Volume", "When notes are playing, individual sliders appear so you can balance each drone note precisely."),
    OnboardingPage("🥁", "Metronome", "Enable the metronome, set BPM (40–240) with the slider or Tap Tempo, and adjust tick volume independently."),
    OnboardingPage("✨", "Effects & EQ", "Shape your sound with Reverb, Echo, EQ (Low/Mid/High), Stereo Width, Sub Octave blend, Warmth, and Compression."),
    OnboardingPage("🎚️", "Master Volume", "The master fader controls the overall output level of all active drone notes together."),
    OnboardingPage("🎤", "Chromatic Tuner", "Tap the Tuner tab to switch to the chromatic tuner. It listens via your mic and shows pitch on an analog dial."),
    OnboardingPage("🙏", "You're All Set!", "Enjoy Tanpura Kings. This tour won't appear again — explore every feature at your own pace.")
)

@Composable
fun OnboardingScreen(onFinished: () -> Unit) {
    var currentPage by remember { mutableIntStateOf(0) }
    val isLast = currentPage == onboardingPages.size - 1

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0x991A0A6B), Color(0x99500050))
                )
            )
    ) {
        // Skip button
        if (!isLast) {
            TextButton(
                onClick = onFinished,
                modifier = Modifier.align(Alignment.TopEnd).padding(16.dp)
            ) {
                Text("Skip", color = Color.White.copy(alpha = 0.7f), fontSize = 16.sp)
            }
        }

        // Page content
        AnimatedContent(
            targetState = currentPage,
            transitionSpec = {
                (slideInHorizontally { it } + fadeIn()).togetherWith(slideOutHorizontally { -it } + fadeOut())
            },
            modifier = Modifier.fillMaxSize(),
            label = "onboardingPage"
        ) { pageIdx ->
            val p = onboardingPages[pageIdx]
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(p.emoji, fontSize = 80.sp)
                Spacer(Modifier.height(24.dp))
                Text(
                    p.title,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    p.description,
                    fontSize = 16.sp,
                    color = Color.White.copy(alpha = 0.88f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    lineHeight = 24.sp
                )
            }
        }

        // Bottom controls
        Column(
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Dot indicators
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                onboardingPages.forEachIndexed { idx, _ ->
                    Box(
                        modifier = Modifier
                            .size(if (idx == currentPage) 10.dp else 7.dp)
                            .background(
                                if (idx == currentPage) Color.White else Color.White.copy(alpha = 0.3f),
                                CircleShape
                            )
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
            // Next / Get Started button
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 32.dp)
                    .background(Color(0xFFFFA500), RoundedCornerShape(50))
                    .clickable { if (isLast) onFinished() else currentPage++ }
                    .padding(vertical = 14.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    if (isLast) "Get Started! 🎵" else "Next  →",
                    color = Color.Black,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

// ------------------------------
// AppRoot — splash → (ad) → app
// ------------------------------
@Composable
fun AppRoot() {
    val context = LocalContext.current
    val activity = context as? Activity
    val prefs = remember { context.getSharedPreferences("TanpuraKingsPrefs", Context.MODE_PRIVATE) }
    val isTablet = remember { context.resources.configuration.smallestScreenWidthDp >= 600 }

    var splashDone  by remember { mutableStateOf(false) }
    var showOnboarding by remember { mutableStateOf(false) }
    var showApp     by remember { mutableStateOf(false) }

    val afterAd = {
        val hasShownOnboarding = prefs.getBoolean("hasShownOnboarding", false)
        val hasShownTabletFix = prefs.getBoolean("hasShownOnboarding_tablet_fix", false)
        val shouldShowOnboarding = if (isTablet) !hasShownTabletFix else !hasShownOnboarding

        if (shouldShowOnboarding) {
            showOnboarding = true
        } else {
            showApp = true
        }
    }

    when {
        showApp        -> TanpuraKingsApp()
        showOnboarding -> OnboardingScreen {
            prefs.edit().putBoolean("hasShownOnboarding", true).apply()
            if (isTablet) {
                prefs.edit().putBoolean("hasShownOnboarding_tablet_fix", true).apply()
            }
            showOnboarding = false
            showApp = true
        }
        else           -> SplashScreen(onReady = {
            if (activity != null) {
                InterstitialAdManager.showIfReady(activity, onDismissed = { afterAd() }, ignoreCooldown = true)
            } else {
                afterAd()
            }
        })
    }
}

// ------------------------------
// SplashScreen
// ------------------------------
private val loadingStages = listOf(
    0.15f to "Initializing audio engine...",
    0.35f to "Loading tanpura samples...",
    0.55f to "Preparing sound effects...",
    0.75f to "Tuning strings...",
    0.90f to "Loading advertisements...",
    1.00f to "Ready!"
)

@Composable
fun SplashScreen(onReady: () -> Unit) {
    val context = LocalContext.current

    // Animated progress 0→1
    var progress by remember { mutableFloatStateOf(0f) }
    val animatedProgress by animateFloatAsState(
        targetValue = progress,
        animationSpec = tween(durationMillis = 400, easing = FastOutSlowInEasing),
        label = "loadingProgress"
    )
    var stageLabel by remember { mutableStateOf(loadingStages.first().second) }

    LaunchedEffect(Unit) {
        InterstitialAdManager.load(context)

        // Animate through each loading stage
        for ((target, label) in loadingStages.dropLast(1)) {
            progress = target
            stageLabel = label
            delay(350)
        }

        // Poll for ad ready (up to 2 extra seconds after stages)
        var waited = 0L
        while (waited < 2000L && !InterstitialAdManager.isReady) {
            delay(100); waited += 100
        }

        // Final step
        progress = 1f
        stageLabel = "Ready!"
        delay(300)
        onReady()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0A1A)), // deep dark base
        contentAlignment = Alignment.Center
    ) {
        // Subtle radial glow behind logo
        Box(
            modifier = Modifier
                .size(280.dp)
                .background(
                    Brush.radialGradient(
                        listOf(
                            Color(0xFF5500AA).copy(alpha = 0.5f),
                            Color.Transparent
                        )
                    )
                )
        )

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 40.dp)
        ) {
            Spacer(modifier = Modifier.weight(1f))

            // Logo
            Image(
                painter = painterResource(id = R.drawable.app_logo),
                contentDescription = "Tanpura Kings",
                modifier = Modifier.size(110.dp)
            )
            Spacer(modifier = Modifier.height(16.dp))

            // Title
            Text(
                text = "TANPURA KINGS",
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 4.sp,
                color = Color.White
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "by Kingsman Software Solutions",
                fontSize = 12.sp,
                color = Color(0xFFAAAAAA)
            )

            Spacer(modifier = Modifier.weight(1f))

            // Progress bar section
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Stage label
                Text(
                    text = stageLabel,
                    fontSize = 12.sp,
                    color = Color(0xFFFFA500),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Start
                )

                // Track background
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(Color.White.copy(alpha = 0.1f))
                ) {
                    // Filled bar with gradient
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(animatedProgress)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(3.dp))
                            .background(
                                Brush.horizontalGradient(
                                    listOf(Color(0xFFFFA500), Color(0xFFFF6600))
                                )
                            )
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Percentage
                Text(
                    text = "${(animatedProgress * 100).toInt()}%",
                    fontSize = 11.sp,
                    color = Color(0xFF888888),
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = androidx.compose.ui.text.style.TextAlign.End
                )
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Preview(showBackground = true)
@Composable
fun DefaultPreview() { TanpuraKingsApp() }
