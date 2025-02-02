package com.kingsman.tanpurakings
import androidx.constraintlayout.widget.ConstraintLayout
import android.content.Context
import android.media.SoundPool
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.view.WindowCompat

// ------------------------------
// AudioManager: Handles Audio Playback and Global Effects
// ------------------------------
object AudioManager {
    private lateinit var soundPool: SoundPool
    private val noteSoundIds = mutableMapOf<String, Int>()
    private val activeStreamIds = mutableMapOf<String, Int>()
    var isInitialized = false

    // Global effect parameters (for logging only)
    var bass: Float = 0f
    var treble: Float = 0f
    var reverbMix: Float = 0f
    var echoMix: Float = 0f

    fun init(context: Context) {
        if (isInitialized) return
        // Create a SoundPool that supports up to 3 simultaneous streams.
        soundPool = SoundPool.Builder().setMaxStreams(3).build()
        // List of note filenames (without extension); e.g., "c", "csharp", etc.
        val keys = listOf("c", "csharp", "d", "dsharp", "e", "f", "fsharp", "g", "gsharp", "a", "asharp", "b")
        for (key in keys) {
            try {
                val afd = context.assets.openFd("Audio/$key.mp3")
                val soundId = soundPool.load(afd, 1)
                noteSoundIds[key] = soundId
            } catch (e: Exception) {
                Log.e("AudioManager", "Error loading sound for key: $key", e)
            }
        }
        isInitialized = true
        Log.d("AudioManager", "AudioManager initialized")
    }

    fun playNote(noteName: String, volume: Float) {
        // Limit to 3 active notes.
        if (activeStreamIds.size >= 3) return
        // Convert note name: e.g., "C#" becomes "csharp"
        val fileKey = noteName.lowercase().replace("#", "sharp")
        val soundId = noteSoundIds[fileKey] ?: return
        val streamId = soundPool.play(soundId, volume, volume, 1, 0, 1f)
        activeStreamIds[noteName] = streamId
        Log.d("AudioManager", "Playing note $noteName with volume $volume")
    }

    fun updateVolume(noteName: String, volume: Float) {
        val streamId = activeStreamIds[noteName] ?: return
        soundPool.setVolume(streamId, volume, volume)
        Log.d("AudioManager", "Updated volume for note $noteName to $volume")
    }

    fun stopNote(noteName: String) {
        val streamId = activeStreamIds[noteName] ?: return
        soundPool.stop(streamId)
        activeStreamIds.remove(noteName)
        Log.d("AudioManager", "Stopped note $noteName")
    }

    fun stopAllNotes() {
        for (streamId in activeStreamIds.values) {
            soundPool.stop(streamId)
        }
        activeStreamIds.clear()
        Log.d("AudioManager", "Stopped all notes")
    }

    fun updateEffects(bass: Float, treble: Float, reverbMix: Float, echoMix: Float) {
        this.bass = bass
        this.treble = treble
        this.reverbMix = reverbMix
        this.echoMix = echoMix
        Log.d("AudioManager", "Effects updated: Bass=$bass, Treble=$treble, Reverb=$reverbMix, Echo=$echoMix")
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
@Composable
fun PianoView(
    activeNotes: MutableState<Set<String>>,
    activeNoteVolumes: MutableState<Map<String, Float>>,
    masterVolume: Float
) {
    val context = LocalContext.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(200.dp)
            .background(Color.LightGray)
    ) {
        // White keys row
        val whiteKeys = octaveKeys.filter { !it.isSharp }
        val whiteKeyCount = whiteKeys.size
        Row(modifier = Modifier.fillMaxSize()) {
            whiteKeys.forEach { key ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .background(if (activeNotes.value.contains(key.name)) Color.Yellow else Color.White)
                        .border(1.dp, Color.Black)
                        .clickable {
                            if (activeNotes.value.contains(key.name)) {
                                AudioManager.stopNote(key.name)
                                activeNotes.value = activeNotes.value - key.name
                                activeNoteVolumes.value = activeNoteVolumes.value.toMutableMap().apply { remove(key.name) }
                            } else {
                                if (activeNotes.value.size >= 3) return@clickable
                                activeNotes.value = activeNotes.value + key.name
                                activeNoteVolumes.value = activeNoteVolumes.value.toMutableMap().apply { put(key.name, 1f) }
                                AudioManager.playNote(key.name, masterVolume)
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(text = key.name, color = Color.Black)
                }
            }
        }
        // Black keys overlay
        Box(modifier = Modifier.fillMaxSize()) {
            // Approximate positions (adjust if needed)
            val keyPositions = mapOf(
                "C#" to 0.66f,
                "D#" to 1.66f,
                "F#" to 3.66f,
                "G#" to 4.66f,
                "A#" to 5.66f
            )
            octaveKeys.filter { it.isSharp }.forEach { key ->
                val posMultiplier = keyPositions[key.name] ?: 0f
                Box(
                    modifier = Modifier
                        .height(120.dp)
                        .width(30.dp)
                        .offset(x = ((posMultiplier / whiteKeyCount) * context.resources.displayMetrics.widthPixels).dp)
                        .background(if (activeNotes.value.contains(key.name)) Color.Yellow else Color.Black)
                        .border(1.dp, Color.Black)
                        .clickable {
                            if (activeNotes.value.contains(key.name)) {
                                AudioManager.stopNote(key.name)
                                activeNotes.value = activeNotes.value - key.name
                                activeNoteVolumes.value = activeNoteVolumes.value.toMutableMap().apply { remove(key.name) }
                            } else {
                                if (activeNotes.value.size >= 3) return@clickable
                                activeNotes.value = activeNotes.value + key.name
                                activeNoteVolumes.value = activeNoteVolumes.value.toMutableMap().apply { put(key.name, 1f) }
                                AudioManager.playNote(key.name, masterVolume)
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(text = key.name, color = Color.White, fontSize = 10.sp)
                }
            }
        }
    }
}

// ------------------------------
// Composable: ActiveNotesVolumeView
// ------------------------------
@Composable
fun ActiveNotesVolumeView(activeNoteVolumes: MutableState<Map<String, Float>>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xAA000000), shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
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
                            activeNoteVolumes.value = activeNoteVolumes.value.toMutableMap().apply { put(note, newVolume) }
                            AudioManager.updateVolume(note, newVolume)
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
    bass: MutableState<Float>,
    treble: MutableState<Float>,
    reverb: MutableState<Float>,
    echo: MutableState<Float>
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xAA000000), shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
            .padding(8.dp)
    ) {
        Text("Effects", color = Color.White, fontSize = 18.sp)
        SliderWithLabel("Bass", bass.value, { bass.value = it }, -20f..20f, Color.Blue)
        SliderWithLabel("Treble", treble.value, { treble.value = it }, -20f..20f, Color.Green)
        SliderWithLabel("Reverb", reverb.value, { reverb.value = it }, 0f..100f, Color.Magenta)
        SliderWithLabel("Echo", echo.value, { echo.value = it }, 0f..100f, Color.Cyan)
    }
}

@Composable
fun SliderWithLabel(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    color: Color
) {
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(label, color = Color.White)
            Spacer(modifier = Modifier.weight(1f))
            Text(String.format("%.2f", value), color = Color.White)
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
            .background(Color(0xAA000000), shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
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
// Composable: GradientBackground
// ------------------------------
@Composable
fun GradientBackground() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color.Blue.copy(alpha = 0.6f),
                        Color(0xFF800080).copy(alpha = 0.8f)
                    )
                )
            )
    )
}

// ------------------------------
// Composable: TanpuraKingsApp (Main Screen)
// ------------------------------
@Composable
fun TanpuraKingsApp() {
    val context = LocalContext.current
    LaunchedEffect(Unit) { AudioManager.init(context) }

    val activeNotes = remember { mutableStateOf(setOf<String>()) }
    val activeNoteVolumes = remember { mutableStateOf(mapOf<String, Float>()) }
    val masterVolume = remember { mutableStateOf(1f) }
    val bass = remember { mutableStateOf(0f) }
    val treble = remember { mutableStateOf(0f) }
    val reverb = remember { mutableStateOf(0f) }
    val echo = remember { mutableStateOf(0f) }

    LaunchedEffect(bass.value, treble.value, reverb.value, echo.value) {
        AudioManager.updateEffects(bass.value, treble.value, reverb.value, echo.value)
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
            ActiveNotesVolumeView(activeNoteVolumes)
            Spacer(modifier = Modifier.height(16.dp))
        }
        EffectsPanel(bass, treble, reverb, echo)
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
        // Enable edge-to-edge display.
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
