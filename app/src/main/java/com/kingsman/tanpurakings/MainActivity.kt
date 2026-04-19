package com.kingsman.tanpurakings

import android.content.Context
import android.media.AudioAttributes
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
import kotlin.math.pow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val MAX_ACTIVE_NOTES = 3

// ------------------------------
// AudioManager: Handles Audio Playback and Global Effects
// ------------------------------
object AudioManager {
    private lateinit var soundPool: SoundPool
    private val noteSoundIds = mutableMapOf<String, Int>()
    private val activeStreamIds = mutableMapOf<String, Int>()
    private val noteVolumes = mutableMapOf<String, Float>()
    private val echoJobs = mutableMapOf<String, Job>()
    var isInitialized = false

    private var coroutineScope: CoroutineScope? = null

    // Effect parameters — updated via updateEffects()
    private var fineTuneCents: Float = 0f
    private var reverbMix: Float = 0f
    private var echoMix: Float = 0f
    private var echoDelayMs: Float = 300f
    private var delayMix: Float = 0f
    private var delayTimeMs: Float = 500f

    fun init(context: Context) {
        if (isInitialized) return
        coroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        soundPool = SoundPool.Builder()
            .setMaxStreams(MAX_ACTIVE_NOTES)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .build()

        val keys = listOf("c", "csharp", "d", "dsharp", "e", "f", "fsharp", "g", "gsharp", "a", "asharp", "b")
        for (key in keys) {
            try {
                val afd = context.assets.openFd("Audio/$key.mp3")
                noteSoundIds[key] = soundPool.load(afd, 1)
            } catch (e: Exception) {
                Log.e("AudioManager", "Error loading sound for key: $key", e)
            }
        }

        isInitialized = true
        Log.d("AudioManager", "AudioManager initialized")
    }

    private fun fineTuneRate(cents: Float): Float =
        2.0.pow(cents / 1200.0).toFloat().coerceIn(0.5f, 2.0f)

    fun playNote(noteName: String, masterVolume: Float, noteVolume: Float = 1f) {
        if (activeStreamIds.size >= MAX_ACTIVE_NOTES) return
        val fileKey = noteName.lowercase().replace("#", "sharp")
        val soundId = noteSoundIds[fileKey] ?: return
        val effectiveVolume = (masterVolume * noteVolume).coerceIn(0f, 1f)
        val rate = fineTuneRate(fineTuneCents)
        val streamId = soundPool.play(soundId, effectiveVolume, effectiveVolume, 1, -1, rate)
        activeStreamIds[noteName] = streamId
        noteVolumes[noteName] = noteVolume

        if (reverbMix > 0f || echoMix > 0f || delayMix > 0f) {
            scheduleEffects(soundId, noteName, effectiveVolume, rate)
        }
    }

    private fun scheduleEffects(soundId: Int, noteName: String, baseVolume: Float, rate: Float) {
        val scope = coroutineScope ?: return
        echoJobs[noteName]?.cancel()
        echoJobs[noteName] = scope.launch {
            // Reverb: dense rapid echoes at prime-number intervals to avoid comb filtering
            if (reverbMix > 0f) launch {
                val intervals = longArrayOf(17, 23, 31, 41, 53, 67, 83, 101, 127)
                var vol = (baseVolume * (reverbMix / 100f) * 0.75f).coerceAtMost(1f)
                for (ms in intervals) {
                    delay(ms)
                    if (!isActive) break
                    soundPool.play(soundId, vol, vol, 0, 0, rate)
                    vol *= 0.6f
                    if (vol < 0.02f) break
                }
            }

            // Delay: single repeat after delayTimeMs at reduced volume
            if (delayMix > 0f) launch {
                delay(delayTimeMs.toLong())
                if (!isActive) return@launch
                val vol = (baseVolume * delayMix).coerceIn(0f, 1f)
                soundPool.play(soundId, vol, vol, 0, 0, rate)
            }

            // Echo: multiple repeats with exponential volume decay
            if (echoMix > 0f) launch {
                var vol = baseVolume * echoMix
                while (vol > 0.02f && isActive) {
                    delay(echoDelayMs.toLong())
                    soundPool.play(soundId, vol.coerceIn(0f, 1f), vol.coerceIn(0f, 1f), 0, 0, rate)
                    vol *= 0.55f
                }
            }
        }
    }

    fun updateNoteVolume(noteName: String, noteVolume: Float, masterVolume: Float) {
        noteVolumes[noteName] = noteVolume
        val streamId = activeStreamIds[noteName] ?: return
        val effectiveVolume = (masterVolume * noteVolume).coerceIn(0f, 1f)
        soundPool.setVolume(streamId, effectiveVolume, effectiveVolume)
    }

    fun updateMasterVolume(masterVolume: Float) {
        activeStreamIds.forEach { (noteName, streamId) ->
            val noteVolume = noteVolumes[noteName] ?: 1f
            val effectiveVolume = (masterVolume * noteVolume).coerceIn(0f, 1f)
            soundPool.setVolume(streamId, effectiveVolume, effectiveVolume)
        }
    }

    fun stopNote(noteName: String) {
        echoJobs.remove(noteName)?.cancel()
        val streamId = activeStreamIds.remove(noteName) ?: return
        soundPool.stop(streamId)
        noteVolumes.remove(noteName)
    }

    fun stopAllNotes() {
        echoJobs.values.forEach { it.cancel() }
        echoJobs.clear()
        activeStreamIds.values.forEach { soundPool.stop(it) }
        activeStreamIds.clear()
        noteVolumes.clear()
    }

    fun updateEffects(
        reverbMixVal: Float,
        fineTune: Float,
        echoMixVal: Float,
        echoDelayVal: Float,
        delayMixVal: Float,
        delayTimeVal: Float
    ) {
        if (fineTune != fineTuneCents) {
            fineTuneCents = fineTune
            val rate = fineTuneRate(fineTune)
            activeStreamIds.values.forEach { soundPool.setRate(it, rate) }
        }
        reverbMix = reverbMixVal
        echoMix = echoMixVal
        echoDelayMs = echoDelayVal
        delayMix = delayMixVal
        delayTimeMs = delayTimeVal
    }

    fun release() {
        stopAllNotes()
        coroutineScope?.cancel()
        coroutineScope = null
        if (::soundPool.isInitialized) soundPool.release()
        noteSoundIds.clear()
        isInitialized = false
        Log.d("AudioManager", "AudioManager released")
    }
}

// ------------------------------
// Data Model for Piano Keys
// ------------------------------
data class PianoKey(val name: String, val isSharp: Boolean)

val octaveKeys = listOf(
    PianoKey("C", false),
    PianoKey("C#", true),
    PianoKey("D", false),
    PianoKey("D#", true),
    PianoKey("E", false),
    PianoKey("F", false),
    PianoKey("F#", true),
    PianoKey("G", false),
    PianoKey("G#", true),
    PianoKey("A", false),
    PianoKey("A#", true),
    PianoKey("B", false)
)

// ------------------------------
// Composable: PianoView
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
        val whiteKeys = octaveKeys.filter { !it.isSharp }
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
                ) {
                    Text(text = key.name, color = Color.Black)
                }
            }
        }

        val blackKeyPositions = mapOf(
            "C#" to 0.75f, "D#" to 1.75f, "F#" to 3.75f, "G#" to 4.75f, "A#" to 5.75f
        )
        octaveKeys.filter { it.isSharp }.forEach { key ->
            val posMultiplier = blackKeyPositions[key.name] ?: 0f
            val blackKeyWidth = whiteKeyWidth * 0.6f
            val offsetX = whiteKeyWidth * posMultiplier - blackKeyWidth / 2
            Box(
                modifier = Modifier
                    .offset(x = offsetX)
                    .width(blackKeyWidth)
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
            ) {
                Text(text = key.name, color = Color.White, fontSize = 10.sp)
            }
        }
    }
}

// ------------------------------
// Composable: ActiveNotesVolumeView
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
        Row(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .fillMaxWidth()
        ) {
            activeNoteVolumes.value.forEach { (note, volume) ->
                Column(
                    modifier = Modifier.padding(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(note, color = Color.White)
                    Slider(
                        value = volume,
                        onValueChange = { newVolume ->
                            activeNoteVolumes.value += note to newVolume
                            AudioManager.updateNoteVolume(note, newVolume, masterVolume)
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
// Composable: EffectsPanel
// ------------------------------
@Composable
fun EffectsPanel(
    reverb: MutableState<Float>,
    fineTune: MutableState<Float>,
    echoMix: MutableState<Float>,
    echoDelay: MutableState<Float>,
    delayMix: MutableState<Float>,
    delayTime: MutableState<Float>
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xAA000000), shape = RoundedCornerShape(8.dp))
            .padding(8.dp)
    ) {
        Text("Effects", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)

        EffectSectionHeader("Fine Tune")
        SliderWithLabel(
            label = "Pitch",
            value = fineTune.value,
            onValueChange = { fineTune.value = it },
            valueRange = -100f..100f,
            color = Color(0xFFFFD700),
            formatter = { v -> "${v.toInt()} cents" }
        )

        EffectSectionHeader("Reverb")
        SliderWithLabel(
            label = "Mix",
            value = reverb.value,
            onValueChange = { reverb.value = it },
            valueRange = 0f..100f,
            color = Color.Magenta
        )

        EffectSectionHeader("Echo")
        SliderWithLabel(
            label = "Mix",
            value = echoMix.value,
            onValueChange = { echoMix.value = it },
            valueRange = 0f..1f,
            color = Color.Cyan
        )
        SliderWithLabel(
            label = "Delay",
            value = echoDelay.value,
            onValueChange = { echoDelay.value = it },
            valueRange = 50f..1000f,
            color = Color.Cyan,
            formatter = { v -> "${v.toInt()} ms" }
        )

        EffectSectionHeader("Delay")
        SliderWithLabel(
            label = "Mix",
            value = delayMix.value,
            onValueChange = { delayMix.value = it },
            valueRange = 0f..1f,
            color = Color(0xFFFFA500)
        )
        SliderWithLabel(
            label = "Time",
            value = delayTime.value,
            onValueChange = { delayTime.value = it },
            valueRange = 50f..2000f,
            color = Color(0xFFFFA500),
            formatter = { v -> "${v.toInt()} ms" }
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
    formatter: (Float) -> String = { v -> String.format(Locale.getDefault(), "%.2f", v) }
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
            colors = SliderDefaults.colors(
                thumbColor = color,
                activeTrackColor = color
            )
        )
    }
}

// ------------------------------
// Composable: MasterVolumeView
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
            colors = SliderDefaults.colors(
                thumbColor = Color.Blue,
                activeTrackColor = Color.Blue
            )
        )
    }
}

// ------------------------------
// Composable: TanpuraKingsApp (Main Screen)
// ------------------------------
@Composable
fun TanpuraKingsApp() {
    val context = LocalContext.current

    val activeNotes = remember { mutableStateOf(setOf<String>()) }
    val activeNoteVolumes = remember { mutableStateOf(mapOf<String, Float>()) }
    val masterVolume = remember { mutableFloatStateOf(1f) }
    val reverb = remember { mutableFloatStateOf(0f) }
    val fineTune = remember { mutableFloatStateOf(0f) }
    val echoMix = remember { mutableFloatStateOf(0f) }
    val echoDelay = remember { mutableFloatStateOf(300f) }
    val delayMix = remember { mutableFloatStateOf(0f) }
    val delayTime = remember { mutableFloatStateOf(500f) }

    DisposableEffect(Unit) {
        AudioManager.init(context)
        onDispose { AudioManager.release() }
    }

    LaunchedEffect(masterVolume.value) {
        AudioManager.updateMasterVolume(masterVolume.value)
    }

    LaunchedEffect(
        reverb.value, fineTune.value,
        echoMix.value, echoDelay.value,
        delayMix.value, delayTime.value
    ) {
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
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color.Blue.copy(alpha = 0.6f),
                        Color(0xFF800080).copy(alpha = 0.8f)
                    )
                )
            )
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Tanpura Kings", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = Color.White)
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
            fontSize = 14.sp,
            color = Color.White,
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

// ------------------------------
// Preview
// ------------------------------
@Preview(showBackground = true)
@Composable
fun DefaultPreview() {
    TanpuraKingsApp()
}
