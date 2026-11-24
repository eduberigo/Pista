package com.eduberigo.pista

import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Forma de onda pronta para desenhar: amplitude 0..1 e cor ARGB por ponto (147 pontos/s). */
class Wave(val amp: FloatArray, val col: IntArray) {
    val bins: Int get() = amp.size

    private var ovW = -1
    private var ovAmp = FloatArray(0)
    private var ovCol = IntArray(0)

    /** Resumo em [w] colunas para a visão geral. */
    @Synchronized
    fun overview(w: Int): Pair<FloatArray, IntArray> {
        if (w != ovW) {
            val a = FloatArray(w)
            val c = IntArray(w)
            for (x in 0 until w) {
                val b0 = (x.toLong() * bins / w).toInt()
                val b1 = max(b0 + 1, ((x + 1).toLong() * bins / w).toInt())
                var m = 0f
                var mi = b0
                for (b in b0 until min(b1, bins)) if (amp[b] > m) { m = amp[b]; mi = b }
                a[x] = m
                c[x] = if (mi < bins) col[mi] else 0
            }
            ovW = w; ovAmp = a; ovCol = c
        }
        return ovAmp to ovCol
    }

    companion object {
        fun load(f: File): Wave? {
            if (!f.exists()) return null
            val b = f.readBytes()
            return from(b)
        }

        fun from(b: ByteArray): Wave {
            val n = b.size / 4
            val amp = FloatArray(n)
            val col = IntArray(n)
            for (i in 0 until n) {
                val t = (b[i * 4].toInt() and 0xFF) / 255f
                val lo = (b[i * 4 + 1].toInt() and 0xFF) / 255f
                val mi = (b[i * 4 + 2].toInt() and 0xFF) / 255f
                val hi = (b[i * 4 + 3].toInt() and 0xFF) / 255f
                amp[i] = t
                val s = lo + mi + hi + 1e-6f
                // grave = azul, médio = laranja, agudo = branco
                var r = (lo * 0.20f + mi * 1.00f + hi * 0.95f) / s
                var g = (lo * 0.45f + mi * 0.60f + hi * 0.95f) / s
                var bl = (lo * 1.00f + mi * 0.18f + hi * 0.95f) / s
                val bright = 0.55f + 0.45f * sqrt(t)
                r = min(1f, r * bright); g = min(1f, g * bright); bl = min(1f, bl * bright)
                col[i] = (0xFF shl 24) or ((r * 255).toInt() shl 16) or ((g * 255).toInt() shl 8) or (bl * 255).toInt()
            }
            return Wave(amp, col)
        }
    }
}
