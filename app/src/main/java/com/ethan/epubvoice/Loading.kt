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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** The loading screen: the book's name, "Loading...", and a little worm skimming an open book in a loop. */
@Composable
fun BookwormLoading(title: String) {
    val t = rememberInfiniteTransition(label = "worm")
    val phase by t.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(3600, easing = LinearEasing)),
        label = "travel"
    )
    val wave by t.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1000, easing = LinearEasing)),
        label = "wiggle"
    )
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Canvas(Modifier.size(width = 280.dp, height = 160.dp)) { drawWormBook(phase, wave) }
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

private fun DrawScope.drawWormBook(phase: Float, wave: Float) {
    val w = size.width
    val h = size.height
    val cx = w / 2f
    val top = h * 0.60f
    val pageW = w * 0.40f
    val pageH = h * 0.32f

    // cover
    drawRoundRect(
        Blue,
        topLeft = Offset(cx - pageW - 12f, top - 4f),
        size = Size(pageW * 2f + 24f, pageH + 22f),
        cornerRadius = CornerRadius(18f, 18f)
    )
    // pages
    for (side in intArrayOf(-1, 1)) {
        val s = side.toFloat()
        val page = Path().apply {
            moveTo(cx, top + 6f)
            cubicTo(cx + s * pageW * 0.35f, top - 8f, cx + s * pageW * 0.75f, top - 8f, cx + s * pageW, top + 4f)
            lineTo(cx + s * pageW, top + pageH)
            cubicTo(cx + s * pageW * 0.75f, top + pageH - 8f, cx + s * pageW * 0.35f, top + pageH - 8f, cx, top + pageH + 6f)
            close()
        }
        drawPath(page, Color.White)
        for (i in 0..3) {
            val y = top + 22f + i * ((pageH - 44f) / 3f)
            drawLine(
                Color(0xFFB8C4D6),
                Offset(cx + s * 16f, y),
                Offset(cx + s * (pageW - 18f), y),
                strokeWidth = 4f,
                cap = StrokeCap.Round
            )
        }
    }

    // worm: moves back and forth along the top of the pages while a hump runs down its body
    val twoPi = (2.0 * PI).toFloat()
    val amp = pageW * 0.72f
    val u = phase * twoPi
    val headX = cx + amp * sin(u)
    val dir = if (cos(u) >= 0f) 1f else -1f
    val r = h * 0.075f
    val segs = 8
    val outline = Color(0xFF0D2B52)
    val body = Color(0xFF5AA9FF)

    val xs = FloatArray(segs)
    val ys = FloatArray(segs)
    val rs = FloatArray(segs)
    for (i in 0 until segs) {
        val hump = abs(sin(wave * twoPi - i * 0.8f))
        xs[i] = headX - dir * i * r * 1.2f
        ys[i] = top - r * 0.9f - hump * r * 1.1f
        rs[i] = if (i == 0) r * 1.2f else r * (1f - i * 0.04f)
    }
    for (i in segs - 1 downTo 0) drawCircle(outline, rs[i] + 3.5f, Offset(xs[i], ys[i]))
    for (i in segs - 1 downTo 0) drawCircle(body, rs[i], Offset(xs[i], ys[i]))

    // head: reading glasses and a pupil looking along the page
    val hx = xs[0]
    val hy = ys[0]
    val eye = Offset(hx + dir * rs[0] * 0.35f, hy - rs[0] * 0.15f)
    drawCircle(Color.White, rs[0] * 0.42f, eye)
    drawCircle(Color(0xFF1C1C1C), rs[0] * 0.18f, Offset(eye.x + dir * rs[0] * 0.1f, eye.y + rs[0] * 0.08f))
    drawCircle(Color(0xFF1C1C1C), rs[0] * 0.46f, eye, style = Stroke(width = 3f))
}
