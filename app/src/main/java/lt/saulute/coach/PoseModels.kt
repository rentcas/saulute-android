package lt.saulute.coach

import com.google.mlkit.vision.pose.Pose
import com.google.mlkit.vision.pose.PoseLandmark
import kotlin.math.acos
import kotlin.math.hypot

data class Pt(val x: Float, val y: Float, val confidence: Float)

data class PoseFrame(
    val timeMs: Long,
    val width: Int,
    val height: Int,
    val points: Map<Int, Pt>
) {
    fun p(type: Int) = points[type]

    companion object {
        val required = listOf(
            PoseLandmark.NOSE, PoseLandmark.LEFT_SHOULDER, PoseLandmark.RIGHT_SHOULDER,
            PoseLandmark.LEFT_ELBOW, PoseLandmark.RIGHT_ELBOW, PoseLandmark.LEFT_WRIST,
            PoseLandmark.RIGHT_WRIST, PoseLandmark.LEFT_HIP, PoseLandmark.RIGHT_HIP,
            PoseLandmark.LEFT_KNEE, PoseLandmark.RIGHT_KNEE, PoseLandmark.LEFT_ANKLE,
            PoseLandmark.RIGHT_ANKLE
        )

        fun from(pose: Pose, timeMs: Long, width: Int, height: Int): PoseFrame {
            val map = required.mapNotNull { type ->
                pose.getPoseLandmark(type)?.let { type to Pt(it.position.x, it.position.y, it.inFrameLikelihood) }
            }.toMap()
            return PoseFrame(timeMs, width, height, map)
        }
    }
}

data class SetupStatus(val ready: Boolean, val message: String, val progress: Float)

fun checkSetup(frame: PoseFrame?): SetupStatus {
    if (frame == null || frame.points.size < PoseFrame.required.size - 1) {
        return SetupStatus(false, "Atsistok visa figūra prieš kamerą", .15f)
    }
    val pts = frame.points.values
    if (pts.any { it.confidence < .55f }) return SetupStatus(false, "Atsitrauk – kamera turi matyti visą kūną", .3f)
    val minX = pts.minOf { it.x } / frame.width
    val maxX = pts.maxOf { it.x } / frame.width
    val minY = pts.minOf { it.y } / frame.height
    val maxY = pts.maxOf { it.y } / frame.height
    if (minY < .06f || maxY > .94f || minX < .08f || maxX > .92f) {
        return SetupStatus(false, "Atsitrauk maždaug vieną žingsnį", .45f)
    }
    val ls = frame.p(PoseLandmark.LEFT_SHOULDER)!!
    val rs = frame.p(PoseLandmark.RIGHT_SHOULDER)!!
    val lh = frame.p(PoseLandmark.LEFT_HIP)!!
    val rh = frame.p(PoseLandmark.RIGHT_HIP)!!
    val shoulderWidth = distance(ls, rs)
    val torso = (distance(ls, lh) + distance(rs, rh)) / 2f
    if (shoulderWidth / torso > .48f) return SetupStatus(false, "Pasisuk šonu į kamerą", .65f)
    val bodyCenter = (pts.minOf { it.x } + pts.maxOf { it.x }) / 2f / frame.width
    if (bodyCenter !in .35f..65f) return SetupStatus(false, "Atsistok kadro centre", .8f)
    return SetupStatus(true, "Puiku. Telefonas pastatytas tinkamai", 1f)
}

data class Metric(val name: String, val score: Int, val detail: String)
data class Evaluation(
    val total: Int,
    val headline: String,
    val advice: String,
    val metrics: List<Metric>,
    val errorFrame: PoseFrame? = null,
    val errorOffsetMs: Long = 0L
)

fun evaluateCartwheel(frames: List<PoseFrame>): Evaluation {
    if (frames.size < 8) return Evaluation(0, "Bandymo nepavyko įvertinti", "Pakartok ir lik visame kadre.", emptyList())

    val inversion = frames.minByOrNull { f ->
        listOfNotNull(f.p(PoseLandmark.LEFT_ANKLE), f.p(PoseLandmark.RIGHT_ANKLE)).map { it.y }.average()
    } ?: frames[frames.size / 2]

    fun jointAngle(a: Int, b: Int, c: Int): Double {
        val pa = inversion.p(a) ?: return 90.0
        val pb = inversion.p(b) ?: return 90.0
        val pc = inversion.p(c) ?: return 90.0
        val abx = pa.x - pb.x; val aby = pa.y - pb.y
        val cbx = pc.x - pb.x; val cby = pc.y - pb.y
        val dot = abx * cbx + aby * cby
        val mag = hypot(abx.toDouble(), aby.toDouble()) * hypot(cbx.toDouble(), cby.toDouble())
        return Math.toDegrees(acos((dot / mag).coerceIn(-1.0, 1.0)))
    }

    val leftKnee = jointAngle(PoseLandmark.LEFT_HIP, PoseLandmark.LEFT_KNEE, PoseLandmark.LEFT_ANKLE)
    val rightKnee = jointAngle(PoseLandmark.RIGHT_HIP, PoseLandmark.RIGHT_KNEE, PoseLandmark.RIGHT_ANKLE)
    val kneeAngle = minOf(leftKnee, rightKnee)
    val straightScore = (((kneeAngle - 120) / 55) * 100).toInt().coerceIn(0, 100)

    val la = inversion.p(PoseLandmark.LEFT_ANKLE)
    val ra = inversion.p(PoseLandmark.RIGHT_ANKLE)
    val lh = inversion.p(PoseLandmark.LEFT_HIP)
    val rh = inversion.p(PoseLandmark.RIGHT_HIP)
    val hip = if (lh != null && rh != null) Pt((lh.x + rh.x) / 2, (lh.y + rh.y) / 2, 1f) else null
    val splitAngle = if (la != null && ra != null && hip != null) angle(la, hip, ra) else 60.0
    val splitScore = (((splitAngle - 70) / 90) * 100).toInt().coerceIn(0, 100)

    val shoulderY = listOfNotNull(inversion.p(PoseLandmark.LEFT_SHOULDER), inversion.p(PoseLandmark.RIGHT_SHOULDER)).map { it.y }.average()
    val hipY = listOfNotNull(lh, rh).map { it.y }.average()
    val heightDelta = ((shoulderY - hipY) / inversion.height).toFloat()
    val heightScore = ((heightDelta + .03f) / .24f * 100).toInt().coerceIn(0, 100)

    val tail = frames.takeLast((frames.size / 5).coerceAtLeast(3))
    val centers = tail.mapNotNull { f ->
        val l = f.p(PoseLandmark.LEFT_HIP); val r = f.p(PoseLandmark.RIGHT_HIP)
        if (l != null && r != null) (l.x + r.x) / 2f / f.width else null
    }
    val drift = (centers.maxOrNull() ?: 0f) - (centers.minOrNull() ?: 0f)
    val landingScore = (100 - drift * 520).toInt().coerceIn(0, 100)

    val metrics = listOf(
        Metric("Tiesios kojos", straightScore, "Kelio kampas ${kneeAngle.toInt()}°"),
        Metric("Kojų išskyrimas", splitScore, "Išskyrimas ${splitAngle.toInt()}°"),
        Metric("Klubų aukštis", heightScore, if (heightScore > 70) "Klubai pakilo virš pečių" else "Klubus kelk aukščiau"),
        Metric("Nusileidimas", landingScore, if (landingScore > 70) "Stabilus" else "Po nusileidimo išlaikyk poziciją")
    )
    val total = metrics.map { it.score }.average().toInt()
    val weakest = metrics.minBy { it.score }
    val advice = when (weakest.name) {
        "Tiesios kojos" -> "Svarbiausia kitam bandymui: įtempk kelius ir ištiesk kojų pirštus."
        "Kojų išskyrimas" -> "Svarbiausia kitam bandymui: ore kojas išskirk plačiau."
        "Klubų aukštis" -> "Svarbiausia kitam bandymui: stipriau atsispirk ir kelk klubus virš pečių."
        else -> "Svarbiausia kitam bandymui: nusileidusi sustink dviem sekundėms."
    }
    val headline = when {
        total >= 85 -> "Labai gera saulutė!"
        total >= 70 -> "Geras bandymas"
        total >= 50 -> "Jau neblogai – pataisyk vieną dalyką"
        else -> "Pabandyk dar kartą"
    }
    return Evaluation(
        total = total,
        headline = headline,
        advice = advice,
        metrics = metrics,
        errorFrame = inversion,
        errorOffsetMs = (inversion.timeMs - frames.first().timeMs).coerceAtLeast(0L)
    )
}

private fun distance(a: Pt, b: Pt) = hypot((a.x - b.x).toDouble(), (a.y - b.y).toDouble()).toFloat()
private fun angle(a: Pt, b: Pt, c: Pt): Double {
    val bax = a.x - b.x; val bay = a.y - b.y
    val bcx = c.x - b.x; val bcy = c.y - b.y
    val dot = bax * bcx + bay * bcy
    val mag = hypot(bax.toDouble(), bay.toDouble()) * hypot(bcx.toDouble(), bcy.toDouble())
    return Math.toDegrees(acos((dot / mag).coerceIn(-1.0, 1.0)))
}
