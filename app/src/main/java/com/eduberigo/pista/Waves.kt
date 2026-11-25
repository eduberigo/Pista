package com.eduberigo.pista

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** Visão geral da música inteira (cabeçalho). Toque para pular. */
@Composable
fun Overview(d: Deck, modifier: Modifier = Modifier) {
    val wave = d.ui.wave
    val edits = d.ui.edits
    Canvas(
        modifier.pointerInput(d) {
            detectTapGestures { o ->
                val l = d.loaded ?: return@detectTapGestures
                d.seek((o.x / size.width * l.frames).toDouble())
            }
        },
    ) {
        Tick.frame.longValue
        edits.hashCode()
        drawRect(Color(0xFF0D0E11))
        val l = d.loaded ?: return@Canvas
        val w = size.width.toInt()
        if (wave != null && w > 0) {
            val (amp, col) = wave.overview(w)
            val mid = size.height / 2
            val played = (d.pos / l.frames * w).toInt()
            var x = 0
            while (x < w) {
                val a = max(1f, amp[x] * mid)
                drawLine(Color(col[x]).copy(alpha = if (x < played) 0.4f else 1f), Offset(x.toFloat(), mid - a), Offset(x.toFloat(), mid + a), 2f)
                x += 2
            }
        }
        val t = l.track
        val cx = (t.startCue * SR / l.frames * size.width).toFloat()
        drawLine(C.yellow, Offset(cx, 0f), Offset(cx, size.height), 2f)
        for (i in 0 until 8) {
            val h = t.hotcues[i]
            if (h >= 0) {
                val hx = (h * SR / l.frames * size.width).toFloat()
                drawLine(C.hot[i], Offset(hx, 0f), Offset(hx, size.height * 0.35f), 3f)
            }
        }
        val px = (d.pos / l.frames * size.width).toFloat()
        drawLine(C.red, Offset(px, 0f), Offset(px, size.height), 3f)
    }
}

/**
 * Onda vertical com zoom (o futuro vem de baixo, como no djay).
 * Arrastar: parado = posiciona; tocando = acelera/freia de leve para acertar a fase.
 */
@Composable
fun DeckWave(d: Deck, modifier: Modifier = Modifier) {
    val wave = d.ui.wave
    val edits = d.ui.edits
    var zoom by remember { mutableFloatStateOf(7f) }
    val tm = rememberTextMeasurer()
    val labelStyle = TextStyle(color = C.dim, fontSize = 11.sp)
    Box(modifier) {
        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(d) {
                    detectVerticalDragGestures(onDragEnd = { d.bend = 0.0 }) { c, dy ->
                        c.consume()
                        if (d.playing) {
                            d.nudge((-dy / 60.0).coerceIn(-0.25, 0.25))
                        } else {
                            val secPerPx = zoom / size.height
                            d.seekBy((-dy * secPerPx * SR).toDouble())
                        }
                    }
                }
                .pointerInput(d) {
                    detectTapGestures(onDoubleTap = { o ->
                        zoom = if (o.y < size.height / 2) max(2f, zoom / 1.5f) else min(30f, zoom * 1.5f)
                    })
                },
        ) {
            Tick.frame.longValue
            edits.hashCode()
            val h = size.height
            val w = size.width
            drawRect(Color(0xFF34373E))
            val l = d.loaded ?: return@Canvas
            val pos = d.pos / SR
            val secPerPx = zoom / h
            val cx = w / 2
            // laço
            if (d.loopOn) {
                val y0 = h / 2 + ((d.loopStart / SR - pos) / secPerPx).toFloat()
                val y1 = h / 2 + ((d.loopEnd / SR - pos) / secPerPx).toFloat()
                drawRect(C.green.copy(alpha = 0.18f), Offset(0f, y0), Size(w, y1 - y0))
            }
            // forma de onda
            if (wave != null) {
                val rate = Analyzer.WAVE_RATE
                var y = 0
                while (y < h) {
                    val t0 = pos + (y - h / 2) * secPerPx
                    val t1 = t0 + 2 * secPerPx
                    val b0 = (t0 * rate).toInt()
                    val b1 = max(b0 + 1, (t1 * rate).toInt())
                    var a = 0f
                    var col = 0
                    var b = max(0, b0)
                    while (b < min(b1, wave.bins)) {
                        if (wave.amp[b] >= a) { a = wave.amp[b]; col = wave.col[b] }
                        b++
                    }
                    if (a > 0f) {
                        val half = a * (cx - 6f)
                        drawLine(Color(col), Offset(cx - half, y.toFloat()), Offset(cx + half, y.toFloat()), 2f)
                    }
                    y += 2
                }
            }
            // grade de batidas
            val t = l.track
            if (t.bpm > 0) {
                val per = t.period
                val tTop = pos - h / 2 * secPerPx
                val tBot = pos + h / 2 * secPerPx
                var k = ceil((tTop - t.anchor) / per).toLong()
                val kEnd = floor((tBot - t.anchor) / per).toLong()
                while (k <= kEnd) {
                    val bt = t.anchor + k * per
                    val y = (h / 2 + (bt - pos) / secPerPx).toFloat()
                    val down = Math.floorMod(k, 4L) == 0L
                    if (down) {
                        drawLine(C.yellow.copy(alpha = 0.85f), Offset(0f, y), Offset(w, y), 2.5f)
                        val bar = Math.floorDiv(k, 4L) + 1
                        safeText(tm, "$bar", 4f, y + 2f, labelStyle)
                    } else {
                        drawLine(Color.White.copy(alpha = 0.22f), Offset(0f, y), Offset(w, y), 1f)
                    }
                    k++
                }
            }
            // cue e hot cues
            val cy = (h / 2 + (t.startCue - pos) / secPerPx).toFloat()
            drawLine(C.yellow, Offset(0f, cy), Offset(w * 0.18f, cy), 4f)
            for (i in 0 until 8) {
                val hc = t.hotcues[i]
                if (hc >= 0) {
                    val y = (h / 2 + (hc - pos) / secPerPx).toFloat()
                    if (y in 0f..h) {
                        drawLine(C.hot[i], Offset(w * 0.82f, y), Offset(w, y), 4f)
                        safeText(tm, "${i + 1}", w - 16f, y + 2f, TextStyle(color = C.hot[i], fontSize = 11.sp))
                    }
                }
            }
            // agulha
            drawLine(C.red, Offset(0f, h / 2), Offset(w, h / 2), 2.dp.toPx())
        }
    }
}

/** drawText quebra o app se o texto começar fora da área; aqui só desenha o que cabe, com tamanho fixo. */
private fun DrawScope.safeText(tm: TextMeasurer, text: String, x: Float, y: Float, style: TextStyle) {
    val w = 40f
    val h = 34f
    if (x < 0f || y < 0f || x + w > size.width || y + h > size.height) return
    drawText(tm, text, Offset(x, y), style, size = Size(w, h))
}
