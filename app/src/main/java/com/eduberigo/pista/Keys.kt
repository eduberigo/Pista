package com.eduberigo.pista

import kotlin.math.sqrt

/** Tom: 0..11 = maior (C..B), 12..23 = menor (Cm..Bm), -1 = desconhecido. */
object Keys {
    private val MAJ = arrayOf("C", "Db", "D", "Eb", "E", "F", "F#", "G", "Ab", "A", "Bb", "B")
    private val MIN = arrayOf("Cm", "C#m", "Dm", "Ebm", "Em", "Fm", "F#m", "Gm", "G#m", "Am", "Bbm", "Bm")

    fun name(k: Int): String = when {
        k < 0 -> ""
        k < 12 -> MAJ[k]
        else -> MIN[k - 12]
    }

    fun camelotNum(k: Int): Int {
        val pc = if (k < 12) k else (k - 12 + 3) % 12
        return ((pc * 7) % 12 + 7) % 12 + 1
    }

    fun camelot(k: Int): String = if (k < 0) "" else "${camelotNum(k)}${if (k < 12) "B" else "A"}"

    fun shifted(k: Int, semis: Int): Int = when {
        k < 0 -> k
        k < 12 -> ((k + semis) % 12 + 12) % 12
        else -> 12 + ((k - 12 + semis) % 12 + 12) % 12
    }

    fun compatible(a: Int, b: Int): Boolean {
        if (a < 0 || b < 0) return false
        val na = camelotNum(a); val nb = camelotNum(b)
        if (na == nb) return true
        if ((a < 12) != (b < 12)) return false
        val d = ((na - nb) % 12 + 12) % 12
        return d == 1 || d == 11
    }

    /** Menor deslocamento em semitons (-6..5) que leva o tom [from] a ficar compatível com [to]. */
    fun matchShift(from: Int, to: Int): Int? {
        if (from < 0 || to < 0) return null
        var best: Int? = null
        for (s in listOf(0, 1, -1, 2, -2, 3, -3, 4, -4, 5, -5, 6)) {
            val k = shifted(from, s)
            if (camelotNum(k) == camelotNum(to) && (k < 12) == (to < 12)) return s
            if (best == null && compatible(k, to)) best = s
        }
        return best
    }

    /** Cor para a roda Camelot (1..12). */
    fun color(k: Int): Long {
        if (k < 0) return 0xFF555555
        val cols = longArrayOf(
            0xFF3EE6C8, 0xFF6EF08A, 0xFFA5E85A, 0xFFE3D94E, 0xFFF7A35C, 0xFFF77B6B,
            0xFFF26A9C, 0xFFD06BE0, 0xFFA07CF5, 0xFF7C94F7, 0xFF5AB5F7, 0xFF43D7F0
        )
        return cols[camelotNum(k) - 1]
    }

    private val MAJP = doubleArrayOf(6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88)
    private val MINP = doubleArrayOf(6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17)

    fun detect(ch: DoubleArray): Int {
        if (ch.sum() <= 0.0) return -1
        var best = -1; var bs = -2.0
        for (t in 0 until 12) {
            val a = corr(ch, MAJP, t)
            if (a > bs) { bs = a; best = t }
            val b = corr(ch, MINP, t)
            if (b > bs) { bs = b; best = 12 + t }
        }
        return best
    }

    private fun corr(c: DoubleArray, p: DoubleArray, t: Int): Double {
        var mc = 0.0; var mp = 0.0
        for (i in 0 until 12) { mc += c[(t + i) % 12]; mp += p[i] }
        mc /= 12; mp /= 12
        var xy = 0.0; var xx = 0.0; var yy = 0.0
        for (i in 0 until 12) {
            val x = c[(t + i) % 12] - mc; val y = p[i] - mp
            xy += x * y; xx += x * x; yy += y * y
        }
        return if (xx <= 0 || yy <= 0) -1.0 else xy / sqrt(xx * yy)
    }
}

/** Acumula croma (12 classes de altura) de um sinal mono a 11025 Hz. */
class Chroma {
    private val n = 4096
    private val hop = 2048
    private val fft = FFT(n)
    private val win = DoubleArray(n) { 0.5 - 0.5 * kotlin.math.cos(2 * Math.PI * it / n) }
    private val buf = FloatArray(n)
    private var fill = 0
    private val re = DoubleArray(n)
    private val im = DoubleArray(n)
    val acc = DoubleArray(12)
    private val pcOf = IntArray(n / 2) { k ->
        val f = k * 11025.0 / n
        if (f < 60 || f > 2000) -1
        else ((Math.round(12 * kotlin.math.ln(f / 440.0) / kotlin.math.ln(2.0)).toInt() + 69) % 12 + 12) % 12
    }

    fun push(x: Float) {
        buf[fill++] = x
        if (fill == n) {
            frame()
            System.arraycopy(buf, hop, buf, 0, n - hop)
            fill = n - hop
        }
    }

    private fun frame() {
        for (i in 0 until n) { re[i] = buf[i] * win[i]; im[i] = 0.0 }
        fft.transform(re, im)
        for (k in 1 until n / 2) {
            val p = pcOf[k]
            if (p >= 0) acc[p] += sqrt(re[k] * re[k] + im[k] * im[k])
        }
    }

    fun key(): Int = Keys.detect(acc)
}

/** Combinação de músicas por tom e BPM em relação a uma referência. */
object Match {
    const val SAME = 1
    const val REL = 2
    const val FIFTH = 4
    const val CHROM = 8
    const val ALL = SAME or REL or FIFTH or CHROM

    class Rel(val kind: Int, val label: String, val cost: Double)

    /** Relação do tom [c] (candidata) com o tom [r] (referência), ou null se não for nenhuma das quatro. */
    fun relation(r: Int, c: Int): Rel? {
        if (r < 0 || c < 0) return null
        if (r == c) return Rel(SAME, "mesmo tom", 0.0)
        val rMin = r >= 12
        val cMin = c >= 12
        if (rMin != cMin) {
            return if (Keys.camelotNum(r) == Keys.camelotNum(c)) Rel(REL, "relativa", 1.0) else null
        }
        val d = ((c % 12 - r % 12) % 12 + 12) % 12
        return when (d) {
            7 -> Rel(FIFTH, "5ª acima", 1.5)
            5 -> Rel(FIFTH, "4ª acima", 1.5)
            1 -> Rel(CHROM, "+1 semitom", 2.0)
            11 -> Rel(CHROM, "−1 semitom", 2.0)
            else -> null
        }
    }

    /** Diferença de BPM em % (candidata em relação à referência); com [halfDouble] também compara com metade/dobro. */
    fun bpmDiff(ref: Double, c: Double, halfDouble: Boolean): Double? {
        if (ref <= 0 || c <= 0) return null
        var best = (c - ref) / ref * 100
        if (halfDouble) {
            for (m in doubleArrayOf(2.0, 0.5)) {
                val v = (c * m - ref) / ref * 100
                if (kotlin.math.abs(v) < kotlin.math.abs(best)) best = v
            }
        }
        return best
    }
}
