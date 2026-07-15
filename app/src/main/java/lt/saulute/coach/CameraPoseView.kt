package lt.saulute.coach

import android.annotation.SuppressLint
import android.content.Context
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.accurate.AccuratePoseDetectorOptions
import java.io.File
import java.util.concurrent.Executors

class CameraRecorder {
    private var capture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null

    internal fun attach(value: VideoCapture<Recorder>) { capture = value }

    fun start(context: Context, onSaved: (File) -> Unit, onError: (String) -> Unit) {
        if (recording != null) return
        val file = File(context.cacheDir, "saulute-${System.currentTimeMillis()}.mp4")
        val output = FileOutputOptions.Builder(file).build()
        recording = capture?.output
            ?.prepareRecording(context, output)
            ?.start(ContextCompat.getMainExecutor(context)) { event ->
                if (event is VideoRecordEvent.Finalize) {
                    recording = null
                    if (event.hasError()) onError("Nepavyko išsaugoti vaizdo įrašo") else onSaved(file)
                }
            }
    }

    fun stop() { recording?.stop() }
}

@SuppressLint("UnsafeOptInUsageError")
@Composable
fun CameraPoseView(
    modifier: Modifier = Modifier,
    onFrame: (PoseFrame) -> Unit,
    onRecorderReady: (CameraRecorder) -> Unit
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    val controller = remember { CameraRecorder() }
    val detector = remember {
        PoseDetection.getClient(
            AccuratePoseDetectorOptions.Builder()
                .setDetectorMode(AccuratePoseDetectorOptions.STREAM_MODE)
                .build()
        )
    }

    AndroidView(
        modifier = modifier,
        factory = { context ->
            val previewView = PreviewView(context).apply {
                scaleType = PreviewView.ScaleType.FILL_CENTER
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            }
            ProcessCameraProvider.getInstance(context).addListener({
                val provider = ProcessCameraProvider.getInstance(context).get()
                val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(executor) { proxy ->
                    val media = proxy.image
                    if (media == null) { proxy.close(); return@setAnalyzer }
                    val image = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
                    detector.process(image)
                        .addOnSuccessListener { pose ->
                            onFrame(PoseFrame.from(pose, System.currentTimeMillis(), image.width, image.height))
                        }
                        .addOnCompleteListener { proxy.close() }
                }

                val recorder = Recorder.Builder()
                    .setQualitySelector(
                        QualitySelector.from(
                            Quality.HD,
                            FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)
                        )
                    ).build()
                val video = VideoCapture.withOutput(recorder)
                controller.attach(video)
                provider.unbindAll()
                provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis,
                    video
                )
                onRecorderReady(controller)
            }, ContextCompat.getMainExecutor(context))
            previewView
        }
    )

    DisposableEffect(Unit) {
        onDispose {
            controller.stop()
            detector.close()
            executor.shutdown()
        }
    }
}
