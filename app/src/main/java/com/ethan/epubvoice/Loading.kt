package com.ethan.epubvoice

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** The loading screen: the app icon (a wyrm eating its tail, a book in front) with the ring moving, plus the book's name. */
@Composable
fun WyrmLoading(title: String) {
    val t = rememberInfiniteTransition(label = "wyrm")
    val phase by t.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2600, easing = LinearEasing)),
        label = "flow"
    )
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Canvas(Modifier.size(200.dp)) { drawWyrm(phase) }
        Spacer(Modifier.height(24.dp))
        Text(
            title.ifBlank { "Opening your book" },
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            "Loading...",
            color = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.padding(top = 6.dp)
        )
    }
}

/** Draws the launcher icon artwork, using the same 108-unit coordinates, with a stripe pattern running around the body. */
private fun DrawScope.drawWyrm(phase: Float) {
    val k = size.minDimension / 108f
    fun pt(x: Float, y: Float) = Offset(x * k, y * k)
    val twoPi = (2.0 * PI).toFloat()
    val navy = Color(0xFF0D2B52)

    // dark disc
    drawCircle(navy, 24f * k, pt(54f, 54f))

    // body: small round pieces along the ring, with bands of lighter blue travelling around it
    val n = 44
    for (i in 0 until n) {
        val a = twoPi * i / n
        val shade = 0.5f + 0.5f * sin(twoPi * (i * 5f / n - phase))
        val c = lerp(Color(0xFF2F80FF), Color(0xFF8CC4FF), shade)
        drawCircle(c, 4.6f * k, pt(54f + 27f * cos(a), 54f + 27f * sin(a)))
    }

    // head bobs a little
    val bob = sin(twoPi * phase * 2f) * 0.9f
    fun h(x: Float, y: Float) = pt(x + bob * 0.64f, y - bob * 0.77f)
    val head = Path().apply {
        val p = listOf(63.84f to 36.09f, 72.84f to 25.37f, 83.8f to 37.19f, 82.89f to 42.95f, 77.38f to 44.85f)
        moveTo(h(p[0].first, p[0].second).x, h(p[0].first, p[0].second).y)
        for (i in 1 until p.size) lineTo(h(p[i].first, p[i].second).x, h(p[i].first, p[i].second).y)
        close()
    }
    drawPath(head, Color(0xFF7DBDFF))
    drawPath(head, navy, style = Stroke(width = 1.6f * k, join = StrokeJoin.Round))
    val horn = Path().apply {
        moveTo(h(71f, 26.5f).x, h(71f, 26.5f).y)
        lineTo(h(65.5f, 19f).x, h(65.5f, 19f).y)
        lineTo(h(75.5f, 24f).x, h(75.5f, 24f).y)
        close()
    }
    drawPath(horn, Color(0xFFEAF4FF))
    drawCircle(Color.White, 1.9f * k, h(75.9f, 32.6f))
    drawCircle(Color.Black, 0.9f * k, h(76f, 32.6f))
    val fang = Path().apply {
        moveTo(h(80.2f, 44.4f).x, h(80.2f, 44.4f).y)
        lineTo(h(81.4f, 48.4f).x, h(81.4f, 48.4f).y)
        lineTo(h(78.6f, 46f).x, h(78.6f, 46f).y)
        close()
    }
    drawPath(fang, Color.White)

    // the book in front
    val book = Path().apply {
        moveTo(54f * k, 50f * k)
        cubicTo(49f * k, 47f * k, 42f * k, 46.5f * k, 37f * k, 48f * k)
        lineTo(37f * k, 68f * k)
        cubicTo(42f * k, 66.5f * k, 49f * k, 67f * k, 54f * k, 70f * k)
        cubicTo(59f * k, 67f * k, 66f * k, 66.5f * k, 71f * k, 68f * k)
        lineTo(71f * k, 48f * k)
        cubicTo(66f * k, 46.5f * k, 59f * k, 47f * k, 54f * k, 50f * k)
        close()
    }
    drawPath(book, Color.Black)
    drawPath(book, Color.Black, style = Stroke(width = 3.4f * k, join = StrokeJoin.Round))
    for (side in intArrayOf(-1, 1)) {
        val s = side.toFloat()
        val page = Path().apply {
            moveTo((54f + s * 0.6f) * k, 50.6f * k)
            cubicTo((54f + s * 5f) * k, 47.6f * k, (54f + s * 12f) * k, 47.1f * k, (54f + s * 16.6f) * k, 48.4f * k)
            lineTo((54f + s * 16.6f) * k, 67.4f * k)
            cubicTo((54f + s * 12f) * k, 66.2f * k, (54f + s * 5f) * k, 66.6f * k, (54f + s * 0.6f) * k, 69.4f * k)
            close()
        }
        drawPath(page, Color.White)
        for (row in 0..2) {
            val y = 53f + row * 4.5f
            drawLine(
                Color(0xFFB8C4D6),
                pt(54f + s * 13.5f, y),
                pt(54f + s * 3f, y + 0.4f),
                strokeWidth = 1.2f * k,
                cap = StrokeCap.Round
            )
        }
    }
}
