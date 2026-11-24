package com.eduberigo.pista

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.sin

object C {
    val bg = Color(0xFF15161A)
    val panel = Color(0xFF1D1F24)
    val panel2 = Color(0xFF24272D)
    val btn = Color(0xFF2A2D34)
    val line = Color(0xFF34373F)
    val txt = Color(0xFFE9EAEE)
    val dim = Color(0xFF8D919A)
    val accent = Color(0xFF2E8BFF)
    val deck1 = Color(0xFF3D9BFF)
    val deck2 = Color(0xFFFF9A3D)
    val green = Color(0xFF3DDC6E)
    val red = Color(0xFFFF3B30)
    val yellow = Color(0xFFFFD60A)
    val black = Color(0xFF0C0D10)
    val hot = arrayOf(
        Color(0xFFFF4D4D), Color(0xFFFF9F1C), Color(0xFFFFE14D), Color(0xFF4DFF88),
        Color(0xFF4DD2FF), Color(0xFF6B7BFF), Color(0xFFC26BFF), Color(0xFFFF6BD0),
    )
    fun deck(i: Int) = if (i == 0) deck1 else deck2
}

/** Relógio de quadros: só quem lê [frame] redesenha a cada quadro. */
object Tick {
    val frame = mutableLongStateOf(0L)
}

@Composable
fun TickDriver() {
    LaunchedEffect(Unit) {
        while (true) withFrameNanos { Tick.frame.longValue = it }
    }
}

@Composable
fun PistaTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = C.accent,
            background = C.bg,
            surface = C.panel,
            onBackground = C.txt,
            onSurface = C.txt,
            onPrimary = Color.White,
        ),
        content = content,
    )
}

fun fmtTime(sec: Double): String {
    val s = if (sec < 0) 0 else sec.toInt()
    return "%d:%02d".format(s / 60, s % 60)
}

fun fmtBpm(b: Double): String = if (b <= 0) "—" else "%.1f".format(b)

@Composable
fun Pill(
    text: String,
    modifier: Modifier = Modifier,
    on: Boolean = false,
    color: Color = C.accent,
    enabled: Boolean = true,
    fontSize: Int = 13,
    onLong: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val click by rememberUpdatedState(onClick)
    val long by rememberUpdatedState(onLong)
    val en by rememberUpdatedState(enabled)
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier
            .clip(shape)
            .background(if (on) color else C.btn)
            .border(1.dp, if (on) color else C.line, shape)
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { if (en) click() },
                    onLongPress = { if (en) long?.invoke() },
                )
            }
            .padding(horizontal = 8.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = if (on) Color.Black else if (enabled) C.txt else C.dim,
            fontSize = fontSize.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

/**
 * Knob: arraste para cima/baixo; toque duplo volta ao padrão.
 * [bipolar] desenha o arco a partir do meio (EQ, filtro, faixas).
 */
@Composable
fun Knob(
    value: Float,
    onChange: (Float) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    min: Float = 0f,
    max: Float = 1f,
    default: Float = 0.5f,
    bipolar: Boolean = true,
    size: Dp = 46.dp,
    color: Color = C.accent,
) {
    val cur by rememberUpdatedState(value)
    val ch by rememberUpdatedState(onChange)
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(
            Modifier
                .size(size)
                .pointerInput(Unit) {
                    var acc = 0f
                    detectVerticalDragGestures(onDragStart = { acc = cur }) { c, dy ->
                        c.consume()
                        acc = (acc - dy / 260f * (max - min)).coerceIn(min, max)
                        ch(acc)
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(onDoubleTap = { ch(default) })
                },
        ) {
            val r = this.size.minDimension / 2
            val c = Offset(this.size.width / 2, this.size.height / 2)
            drawCircle(Color(0xFF0F1013), r, c)
            drawCircle(Color(0xFF3A3D45), r * 0.72f, c)
            val norm = ((value - min) / (max - min)).coerceIn(0f, 1f)
            val sw = r * 0.16f
            val arcR = r - sw / 2
            val tl = Offset(c.x - arcR, c.y - arcR)
            val sz = Size(arcR * 2, arcR * 2)
            drawArc(C.line, 135f, 270f, false, tl, sz, style = Stroke(sw))
            if (bipolar) {
                val start = 270f
                val sweep = (norm - 0.5f) * 270f
                drawArc(color, if (sweep >= 0) start else start + sweep, kotlin.math.abs(sweep), false, tl, sz, style = Stroke(sw))
            } else {
                drawArc(color, 135f, norm * 270f, false, tl, sz, style = Stroke(sw))
            }
            val ang = Math.toRadians((135f + norm * 270f).toDouble())
            val p1 = Offset(c.x + (r * 0.25f) * cos(ang).toFloat(), c.y + (r * 0.25f) * sin(ang).toFloat())
            val p2 = Offset(c.x + (r * 0.66f) * cos(ang).toFloat(), c.y + (r * 0.66f) * sin(ang).toFloat())
            drawLine(Color.White, p1, p2, r * 0.12f, StrokeCap.Round)
        }
        Text(label, color = C.dim, fontSize = 10.sp, maxLines = 1)
    }
}

@Composable
fun VFader(value: Float, onChange: (Float) -> Unit, modifier: Modifier = Modifier, color: Color = C.accent) {
    val cur by rememberUpdatedState(value)
    val ch by rememberUpdatedState(onChange)
    Canvas(
        modifier
            .pointerInput(Unit) {
                var acc = 0f
                detectVerticalDragGestures(onDragStart = { acc = cur }) { c, dy ->
                    c.consume()
                    acc = (acc - dy / (size.height * 0.85f)).coerceIn(0f, 1f)
                    ch(acc)
                }
            }
            .pointerInput(Unit) { detectTapGestures(onDoubleTap = { ch(1f) }) },
    ) {
        val pad = 14.dp.toPx()
        val cx = size.width / 2
        val h = size.height - 2 * pad
        for (i in 0..10) {
            val y = pad + h * i / 10f
            drawLine(C.line, Offset(cx - 14.dp.toPx(), y), Offset(cx + 14.dp.toPx(), y), 1f)
        }
        drawLine(Color.Black, Offset(cx, pad), Offset(cx, pad + h), 5.dp.toPx(), StrokeCap.Round)
        val y = pad + h * (1 - value)
        drawLine(color.copy(alpha = 0.6f), Offset(cx, y), Offset(cx, pad + h), 3.dp.toPx(), StrokeCap.Round)
        drawRoundRect(Color(0xFF5B5F68), Offset(cx - 20.dp.toPx(), y - 10.dp.toPx()), Size(40.dp.toPx(), 20.dp.toPx()), CornerRadius(5.dp.toPx()))
        drawLine(Color.White, Offset(cx - 16.dp.toPx(), y), Offset(cx + 16.dp.toPx(), y), 2.dp.toPx())
    }
}

@Composable
fun HFader(value: Float, onChange: (Float) -> Unit, modifier: Modifier = Modifier, default: Float = 0.5f) {
    val cur by rememberUpdatedState(value)
    val ch by rememberUpdatedState(onChange)
    Canvas(
        modifier
            .pointerInput(Unit) {
                var acc = 0f
                detectHorizontalDragGestures(onDragStart = { acc = cur }) { c, dx ->
                    c.consume()
                    acc = (acc + dx / (size.width * 0.85f)).coerceIn(0f, 1f)
                    ch(acc)
                }
            }
            .pointerInput(Unit) { detectTapGestures(onDoubleTap = { ch(default) }) },
    ) {
        val pad = 16.dp.toPx()
        val cy = size.height / 2
        val w = size.width - 2 * pad
        for (i in 0..4) {
            val x = pad + w * i / 4f
            drawLine(C.line, Offset(x, cy - 12.dp.toPx()), Offset(x, cy + 12.dp.toPx()), 1f)
        }
        drawLine(Color.Black, Offset(pad, cy), Offset(pad + w, cy), 5.dp.toPx(), StrokeCap.Round)
        val x = pad + w * value
        drawRoundRect(Color(0xFF5B5F68), Offset(x - 11.dp.toPx(), cy - 24.dp.toPx()), Size(22.dp.toPx(), 48.dp.toPx()), CornerRadius(5.dp.toPx()))
        drawLine(Color.White, Offset(x, cy - 20.dp.toPx()), Offset(x, cy + 20.dp.toPx()), 2.dp.toPx())
    }
}

@Composable
fun PlayButton(d: Deck, modifier: Modifier = Modifier) {
    Tick.frame.longValue
    val playing = d.playing
    val loaded = d.loaded != null
    Canvas(
        modifier
            .size(62.dp)
            .pointerInput(d) { detectTapGestures(onTap = { Pista.engine.togglePlay(d) }) },
    ) {
        val r = size.minDimension / 2
        drawCircle(C.btn, r)
        drawCircle(if (playing) C.green else if (loaded) C.green.copy(alpha = 0.55f) else C.line, r - 2.dp.toPx(), style = Stroke(3.dp.toPx()))
        val c = center
        if (playing) {
            val w = r * 0.16f
            val h = r * 0.7f
            drawRect(Color.White, Offset(c.x - w * 2.0f, c.y - h / 2), Size(w * 1.2f, h))
            drawRect(Color.White, Offset(c.x + w * 0.8f, c.y - h / 2), Size(w * 1.2f, h))
        } else {
            val p = Path().apply {
                moveTo(c.x - r * 0.22f, c.y - r * 0.34f)
                lineTo(c.x + r * 0.36f, c.y)
                lineTo(c.x - r * 0.22f, c.y + r * 0.34f)
                close()
            }
            drawPath(p, if (loaded) Color.White else C.dim)
        }
    }
}
