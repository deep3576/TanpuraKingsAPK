package com.kingsman.tanpurakings

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.media.SoundPool
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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

    // Effect parameters
    private var fineTuneCents: Float = 0f
    private var reverbMix:     Float = 0f
    private var echoMix:       Float = 0f
    private var echoDelayMs:   Float = 300f
    private var delayMix:      Float = 0f
    private var delayTimeMs:   Float = 500f

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
            retriever.release()
            Log.d("AudioManager", "Ready. Durations: $noteDurations")
        }
    }

    private fun fineTuneRate(cents: Float): Float =
        2.0.pow(cents / 1200.0).toFloat().coerceIn(0.5f, 2.0f)

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
        val fileKey = noteName.lowercase().replace("#", "sharp")
        val volume  = (masterVolume * noteVolume).coerceIn(0f, 1f)

        coroutineScope?.launch {
            val pitch  = fineTuneRate(fineTuneCents)
            val player = buildPlayer(fileKey, volume, pitch) ?: return@launch

            player.start()
            activePlayers[noteName] = player
            noteVolumes[noteName]   = noteVolume

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
        noteVolumes[noteName] = noteVolume
        val vol = (masterVolume * noteVolume).coerceIn(0f, 1f)
        activePlayers[noteName]?.setVolume(vol, vol)
    }

    fun updateMasterVolume(masterVolume: Float) {
        activePlayers.forEach { (noteName, player) ->
            val vol = (masterVolume * (noteVolumes[noteName] ?: 1f)).coerceIn(0f, 1f)
            player.setVolume(vol, vol)
        }
    }

    fun stopNote(noteName: String) {
        loopJobs.remove(noteName)?.cancel()
        echoJobs.remove(noteName)?.cancel()
        activePlayers.remove(noteName)?.apply { runCatching { stop() }; release() }
        noteVolumes.remove(noteName)
    }

    fun stopAllNotes() {
        loopJobs.values.forEach { it.cancel() };  loopJobs.clear()
        echoJobs.values.forEach { it.cancel() };  echoJobs.clear()
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
        stopAllNotes()
        coroutineScope?.cancel()
        coroutineScope = null
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
    delayMix: MutableState<Float>, delayTime: MutableState<Float>
) {
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
        Spacer(modifier = Modifier.height(28.dp))
        Text("Tanpura Kings", fontSize = 22.sp, color = Color.White )
        Spacer(modifier = Modifier.height(16.dp))
        PianoView(activeNotes, activeNoteVolumes, masterVolume.value)
        Spacer(modifier = Modifier.height(16.dp))
        if (activeNoteVolumes.value.isNotEmpty()) {
            ActiveNotesVolumeView(activeNoteVolumes, masterVolume.value)
            Spacer(modifier = Modifier.height(16.dp))
        }
        EffectsPanel(reverb, fineTune, echoMix, echoDelay, delayMix, delayTime)
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
