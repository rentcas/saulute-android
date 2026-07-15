package lt.saulute.coach

import android.content.ContentValues
import android.content.Context
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.os.Build
import android.os.CountDownTimer
import android.provider.MediaStore
import android.widget.Toast
import android.widget.VideoView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.mlkit.vision.pose.PoseLandmark
import java.io.File

private val Ink = Color(0xFF111318)
private val Violet = Color(0xFF7656F6)
private val Mint = Color(0xFF53E0B8)

private enum class Stage { SETUP, COUNTDOWN, RECORDING, ANALYZING, RESULT }

@Composable
fun SauluteApp(cameraAllowed: Boolean, requestCamera: () -> Unit) {
    val context = LocalContext.current
    if (!cameraAllowed) {
        Box(Modifier.fillMaxSize().background(Ink), contentAlignment = Alignment.Center) {
            Button(onClick = requestCamera) { Text("Leisti naudoti kamerą") }
        }
        return
    }

    var stage by remember { mutableStateOf(Stage.SETUP) }
    var latest by remember { mutableStateOf<PoseFrame?>(null) }
    var setup by remember { mutableStateOf(SetupStatus(false, "Atsistok visa figūra prieš kamerą", .1f)) }
    val frames = remember { mutableStateListOf<PoseFrame>() }
    var seconds by remember { mutableIntStateOf(3) }
    var result by remember { mutableStateOf<Evaluation?>(null) }
    var videoFile by remember { mutableStateOf<File?>(null) }
    var recorder by remember { mutableStateOf<CameraRecorder?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val bodyVisible = latest?.points?.size?.let { it >= 10 } == true

    fun reset(deleteVideo: Boolean) {
        if (deleteVideo) videoFile?.delete()
        videoFile = null
        result = null
        frames.clear()
        errorMessage = null
        stage = Stage.SETUP
    }

    fun begin() {
        frames.clear()
        videoFile?.delete()
        videoFile = null
        seconds = 3
        stage = Stage.COUNTDOWN
        object : CountDownTimer(3000, 1000) {
            override fun onTick(ms: Long) { seconds = (ms / 1000L + 1).toInt() }
            override fun onFinish() {
                recorder?.start(
                    context = context,
                    onSaved = { file ->
                        videoFile = file
                        if (result != null) stage = Stage.RESULT
                    },
                    onError = { message ->
                        errorMessage = message
                        if (result != null) stage = Stage.RESULT
                    }
                )
                stage = Stage.RECORDING
                object : CountDownTimer(6500, 1000) {
                    override fun onTick(ms: Long) { seconds = (ms / 1000L + 1).toInt() }
                    override fun onFinish() {
                        result = evaluateCartwheel(frames.toList())
                        stage = Stage.ANALYZING
                        recorder?.stop()
                    }
                }.start()
            }
        }.start()
    }

    Box(Modifier.fillMaxSize().background(Ink)) {
        if (stage != Stage.RESULT) {
            CameraPoseView(
                modifier = Modifier.fillMaxSize(),
                onFrame = { frame ->
                    latest = frame
                    if (stage == Stage.SETUP) {
                        setup = if (frame.points.size >= 10) {
                            SetupStatus(true, "Kūnas matomas. Gali pradėti", 1f)
                        } else checkSetup(frame)
                    }
                    if (stage == Stage.RECORDING && frame.points.size >= 10) frames.add(frame)
                },
                onRecorderReady = { recorder = it }
            )

            Box(Modifier.fillMaxSize().padding(18.dp)) {
                Surface(
                    modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth(),
                    color = Color.Black.copy(alpha = .68f),
                    shape = RoundedCornerShape(22.dp)
                ) {
                    Column(Modifier.padding(20.dp)) {
                        Text("SAULUTĖ", color = Mint, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            when (stage) {
                                Stage.SETUP -> setup.message
                                Stage.COUNTDOWN -> "Pasiruošk… $seconds"
                                Stage.RECORDING -> "Daryk saulutę!  $seconds s"
                                Stage.ANALYZING -> "Analizuojama…"
                                else -> ""
                            },
                            color = Color.White,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold
                        )
                        if (stage == Stage.SETUP) {
                            Spacer(Modifier.height(12.dp))
                            LinearProgressIndicator(
                                progress = { setup.progress },
                                color = if (setup.ready) Mint else Violet,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(Modifier.height(10.dp))
                            Text(
                                "Telefoną laikyk vertikaliai. Kadre turi tilpti visas kūnas ir vieta saulutei.",
                                color = Color.White.copy(.78f)
                            )
                        }
                    }
                }

                if (stage == Stage.SETUP) {
                    Button(
                        onClick = { begin() },
                        enabled = bodyVisible && recorder != null,
                        modifier = Modifier.align(Alignment.BottomCenter).height(58.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Violet, disabledContainerColor = Color.DarkGray)
                    ) {
                        Text(if (bodyVisible) "Pradėti bandymą" else "Atsistok visa figūra į kadrą", fontSize = 17.sp)
                    }
                }
                if (stage == Stage.RECORDING) {
                    Box(Modifier.align(Alignment.TopEnd).padding(12.dp).size(18.dp).background(Color.Red, CircleShape))
                }
            }
        } else {
            ResultScreen(
                result = result ?: evaluateCartwheel(emptyList()),
                videoFile = videoFile,
                errorMessage = errorMessage,
                retry = { reset(deleteVideo = true) },
                delete = {
                    videoFile?.delete()
                    Toast.makeText(context, "Vaizdo įrašas ištrintas", Toast.LENGTH_SHORT).show()
                    reset(deleteVideo = false)
                }
            )
        }
    }
}

@Composable
private fun ResultScreen(
    result: Evaluation,
    videoFile: File?,
    errorMessage: String?,
    retry: () -> Unit,
    delete: () -> Unit
) {
    val context = LocalContext.current
    var saved by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("REZULTATAS · ${result.total}/100", color = Mint, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        Text(result.headline, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(14.dp))

        if (videoFile != null) {
            VideoReview(videoFile, result)
        } else {
            Surface(color = Color.White.copy(.08f), shape = RoundedCornerShape(16.dp)) {
                Text(errorMessage ?: "Vaizdo įrašas nepasiekiamas", Modifier.padding(22.dp), color = Color.White)
            }
        }

        Spacer(Modifier.height(14.dp))
        Surface(color = Violet.copy(alpha = .22f), shape = RoundedCornerShape(18.dp)) {
            Text(result.advice, Modifier.fillMaxWidth().padding(16.dp), color = Color.White, fontSize = 18.sp)
        }
        Spacer(Modifier.height(14.dp))

        result.metrics.forEach { metric ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 5.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(metric.name, color = Color.White)
                Text("${metric.score}/100 · ${metric.detail}", color = if (metric.score >= 70) Mint else Color(0xFFFFC857))
            }
        }

        Spacer(Modifier.height(18.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = delete, modifier = Modifier.weight(1f)) { Text("Ištrinti") }
            Button(
                onClick = {
                    videoFile?.let {
                        saved = saveToGallery(context, it)
                        Toast.makeText(context, if (saved) "Išsaugota galerijoje" else "Nepavyko išsaugoti", Toast.LENGTH_SHORT).show()
                    }
                },
                enabled = videoFile != null && !saved,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = Violet)
            ) { Text(if (saved) "Išsaugota" else "Išsaugoti") }
        }
        Spacer(Modifier.height(10.dp))
        Button(onClick = retry, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Bandyti dar kartą") }
        Spacer(Modifier.height(12.dp))
        Text("Vertinimas yra eksperimentinis ir nepakeičia trenerio.", color = Color.White.copy(.48f), fontSize = 12.sp)
    }
}

@Composable
private fun VideoReview(file: File, result: Evaluation) {
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var playing by remember { mutableStateOf(false) }
    var slow by remember { mutableStateOf(true) }
    var showOverlay by remember { mutableStateOf(true) }

    Box(
        Modifier.fillMaxWidth().aspectRatio(9f / 16f).background(Color.Black, RoundedCornerShape(18.dp)),
        contentAlignment = Alignment.Center
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                VideoView(context).apply {
                    setVideoPath(file.absolutePath)
                    setOnPreparedListener { mp ->
                        player = mp
                        mp.isLooping = true
                        mp.seekTo(result.errorOffsetMs.toInt())
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            mp.playbackParams = PlaybackParams().setSpeed(.5f)
                        }
                    }
                }
            }
        )
        if (showOverlay) PoseOverlay(result.errorFrame, result.metrics.minByOrNull { it.score }?.detail.orEmpty())
    }

    Spacer(Modifier.height(10.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = {
                player?.let {
                    if (it.isPlaying) { it.pause(); playing = false } else { it.start(); playing = true }
                }
            },
            modifier = Modifier.weight(1f)
        ) { Text(if (playing) "Pauzė" else "Leisti") }
        OutlinedButton(
            onClick = {
                slow = !slow
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    player?.playbackParams = PlaybackParams().setSpeed(if (slow) .5f else 1f)
                }
            },
            modifier = Modifier.weight(1f)
        ) { Text(if (slow) "0,5×" else "1×") }
        OutlinedButton(
            onClick = {
                player?.pause()
                player?.seekTo(result.errorOffsetMs.toInt())
                playing = false
                showOverlay = true
            },
            modifier = Modifier.weight(1.35f)
        ) { Text("Klaidos kadras") }
    }
    TextButton(onClick = { showOverlay = !showOverlay }) {
        Text(if (showOverlay) "Slėpti kūno linijas" else "Rodyti kūno linijas")
    }
}

@Composable
private fun PoseOverlay(frame: PoseFrame?, label: String) {
    if (frame == null) return
    val links = listOf(
        PoseLandmark.LEFT_SHOULDER to PoseLandmark.RIGHT_SHOULDER,
        PoseLandmark.LEFT_SHOULDER to PoseLandmark.LEFT_ELBOW,
        PoseLandmark.LEFT_ELBOW to PoseLandmark.LEFT_WRIST,
        PoseLandmark.RIGHT_SHOULDER to PoseLandmark.RIGHT_ELBOW,
        PoseLandmark.RIGHT_ELBOW to PoseLandmark.RIGHT_WRIST,
        PoseLandmark.LEFT_SHOULDER to PoseLandmark.LEFT_HIP,
        PoseLandmark.RIGHT_SHOULDER to PoseLandmark.RIGHT_HIP,
        PoseLandmark.LEFT_HIP to PoseLandmark.RIGHT_HIP,
        PoseLandmark.LEFT_HIP to PoseLandmark.LEFT_KNEE,
        PoseLandmark.LEFT_KNEE to PoseLandmark.LEFT_ANKLE,
        PoseLandmark.RIGHT_HIP to PoseLandmark.RIGHT_KNEE,
        PoseLandmark.RIGHT_KNEE to PoseLandmark.RIGHT_ANKLE
    )
    Box(Modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize()) {
            fun x(p: Pt) = p.x / frame.width * size.width
            fun y(p: Pt) = p.y / frame.height * size.height
            links.forEach { (a, b) ->
                val pa = frame.p(a); val pb = frame.p(b)
                if (pa != null && pb != null) drawLine(Mint, androidx.compose.ui.geometry.Offset(x(pa), y(pa)), androidx.compose.ui.geometry.Offset(x(pb), y(pb)), strokeWidth = 7f)
            }
            frame.points.values.forEach { p ->
                drawCircle(Color.White, 8f, androidx.compose.ui.geometry.Offset(x(p), y(p)))
                drawCircle(Mint, 8f, androidx.compose.ui.geometry.Offset(x(p), y(p)), style = Stroke(3f))
            }
        }
        Surface(
            modifier = Modifier.align(Alignment.TopCenter).padding(12.dp),
            color = Color.Black.copy(.72f),
            shape = RoundedCornerShape(12.dp)
        ) { Text(label, Modifier.padding(horizontal = 12.dp, vertical = 8.dp), color = Color.White, fontWeight = FontWeight.Bold) }
    }
}

private fun saveToGallery(context: Context, source: File): Boolean = runCatching {
    val values = ContentValues().apply {
        put(MediaStore.Video.Media.DISPLAY_NAME, "Saulute-${System.currentTimeMillis()}.mp4")
        put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Saulute")
    }
    val uri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return false
    context.contentResolver.openOutputStream(uri)?.use { output -> source.inputStream().use { it.copyTo(output) } }
    true
}.getOrDefault(false)
