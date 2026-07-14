package lt.saulute.coach

import android.os.CountDownTimer
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Ink = Color(0xFF111318)
private val Violet = Color(0xFF7656F6)
private val Mint = Color(0xFF53E0B8)

private enum class Stage { SETUP, COUNTDOWN, RECORDING, RESULT }

@Composable
fun SauluteApp(cameraAllowed: Boolean, requestCamera: () -> Unit) {
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

    fun begin() {
        frames.clear(); seconds = 3; stage = Stage.COUNTDOWN
        object : CountDownTimer(3000, 1000) {
            override fun onTick(ms: Long) { seconds = (ms / 1000L + 1).toInt() }
            override fun onFinish() {
                stage = Stage.RECORDING
                object : CountDownTimer(6500, 1000) {
                    override fun onTick(ms: Long) { seconds = (ms / 1000L + 1).toInt() }
                    override fun onFinish() { result = evaluateCartwheel(frames.toList()); stage = Stage.RESULT }
                }.start()
            }
        }.start()
    }

    Box(Modifier.fillMaxSize().background(Ink)) {
        if (stage != Stage.RESULT) {
            CameraPoseView(Modifier.fillMaxSize()) { frame ->
                latest = frame
                if (stage == Stage.SETUP) setup = checkSetup(frame)
                if (stage == Stage.RECORDING && frame.points.size >= 10) frames.add(frame)
            }
            Box(Modifier.fillMaxSize().padding(18.dp)) {
                Column(Modifier.align(Alignment.TopStart).fillMaxWidth()) {
                    Surface(color = Color.Black.copy(alpha = .68f), shape = RoundedCornerShape(22.dp)) {
                        Column(Modifier.padding(20.dp)) {
                            Text("SAULUTĖ", color = Mint, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                            Spacer(Modifier.height(8.dp))
                            Text(
                                when (stage) {
                                    Stage.SETUP -> setup.message
                                    Stage.COUNTDOWN -> "Pasiruošk… $seconds"
                                    Stage.RECORDING -> "Daryk saulutę!  $seconds s"
                                    else -> ""
                                }, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold
                            )
                            if (stage == Stage.SETUP) {
                                Spacer(Modifier.height(12.dp))
                                LinearProgressIndicator(progress = { setup.progress }, color = if (setup.ready) Mint else Violet)
                                Spacer(Modifier.height(10.dp))
                                Text("Telefoną laikyk vertikaliai, maždaug klubų aukštyje. Atsitrauk tiek, kad kadre tilptų visas kūnas ir liktų vietos virš galvos.", color = Color.White.copy(.78f))
                            }
                        }
                    }
                }
                if (stage == Stage.SETUP) {
                    Button(
                        onClick = { begin() }, enabled = setup.ready,
                        modifier = Modifier.align(Alignment.BottomCenter).height(58.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Violet, disabledContainerColor = Color.DarkGray)
                    ) { Text(if (setup.ready) "Pradėti bandymą" else "Pirmiausia pastatyk kamerą", fontSize = 17.sp) }
                }
                if (stage == Stage.RECORDING) {
                    Box(Modifier.align(Alignment.TopEnd).size(18.dp).background(Color.Red, CircleShape))
                }
            }
        } else {
            ResultScreen(result ?: evaluateCartwheel(emptyList())) {
                stage = Stage.SETUP; frames.clear(); result = null
            }
        }
    }
}

@Composable
private fun ResultScreen(result: Evaluation, retry: () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("REZULTATAS", color = Mint, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
            Text("${result.total}", color = Color.White, fontSize = 78.sp, fontWeight = FontWeight.Black)
            Text(result.headline, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(20.dp))
            Surface(color = Violet.copy(alpha = .22f), shape = RoundedCornerShape(18.dp)) {
                Text(result.advice, Modifier.padding(18.dp), color = Color.White, fontSize = 19.sp)
            }
        }
        Spacer(Modifier.height(20.dp))
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            result.metrics.forEach { metric ->
                Surface(color = Color.White.copy(alpha = .08f), shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(metric.name, color = Color.White, fontWeight = FontWeight.SemiBold)
                            Text("${metric.score}/100", color = if (metric.score >= 70) Mint else Color(0xFFFFC857), fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.height(7.dp))
                        LinearProgressIndicator(progress = { metric.score / 100f }, color = if (metric.score >= 70) Mint else Violet, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(6.dp))
                        Text(metric.detail, color = Color.White.copy(.62f), fontSize = 13.sp)
                    }
                }
            }
        }
        Spacer(Modifier.height(22.dp))
        Button(onClick = retry, colors = ButtonDefaults.buttonColors(containerColor = Violet), modifier = Modifier.height(54.dp)) {
            Text("Bandyti dar kartą")
        }
        Spacer(Modifier.height(12.dp))
        Text("Tai mokymosi pagalbininkas, ne trenerio ar saugos priežiūros pakaitalas.", color = Color.White.copy(.48f), fontSize = 12.sp)
    }
}
