package com.eduberigo.pista

import java.nio.ShortBuffer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.sqrt

class Analysis(
    val bpm: Double,
    val anchor: Double,
    val autoCue: Double,
    val key: Int,
    val gain: Float,
    val wave: ByteArray,
)

/**
 * Análise de uma música: BPM (grade constante), downbeat, começo do som, tom, ganho e forma de onda.
 * Trabalha em mono a 11025 Hz.
 */
object Analyzer {
    private const val DS = 4
    const val SRA = SR / DS
    private const val N = 1024
    private const val HOP = 128
    private const val FPS = SRA.toDouble() / HOP
    const val WAVE_HOP = 75
    var DEBUG = false
    const val WAVE_RATE = SRA.toDouble() / WAVE_HOP

    fun analyze(pcm: ShortBuffer, frames: Int, cancel: () -> Boolean, hint: Double = 0.0): Analysis {
        val x = mono(pcm, frames)
        val wave = waveform(x)
        var maxAbs = 0f
        var sum = 0.0
        for (v in x) { val a = abs(v); if (a > maxAbs) maxAbs = a; sum += v * v }
        val rms = sqrt(sum / max(1, x.size)).toFloat()
        val gain = if (rms > 1e-5f) (0.2f / rms).coerceIn(0.3f, 2.5f) else 1f

        val keyChroma = Chroma()
        for (v in x) keyChroma.push(v)
        val key = keyChroma.key()

        val nf = if (x.size > N) (x.size - N) / HOP + 1 else 0
        if (nf < 400) return Analysis(0.0, 0.0, 0.0, key, gain, wave)

        val env = FloatArray(nf)
        val low = FloatArray(nf)
        val chroma = FloatArray(nf * 12)
        val fft = FFT(N)
        val re = DoubleArray(N)
        val im = DoubleArray(N)
        val win = DoubleArray(N) { 0.5 - 0.5 * cos(2 * PI * it / N) }
        val prev = DoubleArray(N / 2)
        val kMax = (5000.0 * N / SRA).toInt()
        val kLow = (150.0 * N / SRA).toInt()
        val pcOf = IntArray(N / 2) { k ->
            val f = k * SRA.toDouble() / N
            if (f < 60 || f > 2000) -1 else ((round(12 * ln(f / 440.0) / ln(2.0)).toInt() + 69) % 12 + 12) % 12
        }
        for (f in 0 until nf) {
            if (f % 2000 == 0 && cancel()) throw InterruptedException()
            val o = f * HOP
            for (i in 0 until N) { re[i] = x[o + i] * win[i]; im[i] = 0.0 }
            fft.transform(re, im)
            var flux = 0.0
            var lf = 0.0
            for (k in 1 until kMax) {
                val mag = sqrt(re[k] * re[k] + im[k] * im[k])
                val m = ln(1.0 + 100.0 * mag)
                val d = m - prev[k]
                prev[k] = m
                if (d > 0) { flux += d; if (k <= kLow) lf += d }
                val p = pcOf[k]
                if (p >= 0) chroma[f * 12 + p] += mag.toFloat()
            }
            if (f > 0) { env[f] = flux.toFloat(); low[f] = lf.toFloat() }
        }

        val e = detrend(env, 16)
        // com [hint] (tap tempo) só procura perto do valor batido
        val bpm0 = if (hint > 0) hint else tempo(e)
        val span = if (hint > 0) 2.0 else 0.6
        // refinamento fino de BPM e fase pela soma em pente
        var bestBpm = bpm0
        var bestPhase = 0.0
        var bestScore = -1e18
        var b = bpm0 - span
        while (b <= bpm0 + span) {
            val (ph, sc) = bestPhase(e, 60.0 * FPS / b)
            if (sc > bestScore) { bestScore = sc; bestBpm = b; bestPhase = ph }
            b += 0.02
        }
        b = bestBpm - 0.03
        val center = bestBpm
        while (b <= center + 0.03) {
            val (ph, sc) = bestPhase(e, 60.0 * FPS / b)
            if (sc > bestScore) { bestScore = sc; bestBpm = b; bestPhase = ph }
            b += 0.005
        }
        val rounded = round(bestBpm)
        if (abs(rounded - bestBpm) < 0.08) {
            val (ph, sc) = bestPhase(e, 60.0 * FPS / rounded)
            if (sc >= bestScore * 0.985) { bestBpm = rounded; bestPhase = ph }
        }
        val bpm = round(bestBpm * 100) / 100
        val period = 60.0 / bpm
        // quadro de fluxo -> tempo (aproximado); depois corrigido no domínio do tempo
        var t0 = (bestPhase * HOP + N / 2.0) / SRA
        t0 += refineOffset(x, t0, period)
        val firstBeat = t0 - floor(t0 / period) * period

        // downbeat: bumbo nos tempos 1 e 3 + mudança harmônica no 1
        val nBeats = ((x.size.toDouble() / SRA - firstBeat) / period).toInt()
        var anchor = firstBeat
        if (nBeats >= 16) {
            val lowAt = DoubleArray(nBeats)
            val ch = Array(nBeats) { DoubleArray(12) }
            for (k in 0 until nBeats) {
                val t = firstBeat + k * period
                val fc = ((t * SRA - N / 2.0) / HOP).toInt()
                var m = 0.0
                for (q in fc - 2..fc + 2) if (q in 0 until nf) m = max(m, low[q].toDouble())
                lowAt[k] = m
                val fa = fc.coerceIn(0, nf - 1)
                val fb = ((t + period) * SRA / HOP).toInt().coerceIn(0, nf)
                for (q in fa until fb) for (p in 0 until 12) ch[k][p] += chroma[q * 12 + p]
                // tira o ruído de banda larga (caixa, chimbal): fica só o que se destaca entre as notas
                val c = ch[k]
                val sum = c.sum()
                if (sum > 0) {
                    val mean = sum / 12
                    for (p in 0 until 12) c[p] = max(0.0, c[p] - mean) / sum
                }
            }
            val lowMean = max(1e-9, lowAt.average())
            val score = DoubleArray(4)
            val cnt = IntArray(4)
            for (k in 2 until nBeats - 2) {
                val a = DoubleArray(12); val c = DoubleArray(12)
                for (p in 0 until 12) {
                    a[p] = ch[k - 2][p] + ch[k - 1][p]
                    c[p] = ch[k][p] + ch[k + 1][p]
                }
                val nov = 1.0 - cosSim(a, c)
                score[k % 4] += lowAt[k] / lowMean + 3.0 * nov
                cnt[k % 4]++
            }
            if (DEBUG) println("downbeat scores=" + (0 until 4).map { "%.3f".format(score[it] / max(1, cnt[it])) } + " firstBeat=" + firstBeat)
            var bestC = 0
            for (c in 1 until 4) if (score[c] / max(1, cnt[c]) > score[bestC] / max(1, cnt[bestC])) bestC = c
            anchor = firstBeat + bestC * period
        }

        // começo da música: primeira batida da grade onde já há som
        val thr = max(0.005f, 0.03f * maxAbs)
        var startIdx = 0
        while (startIdx < x.size && abs(x[startIdx]) < thr) startIdx++
        val tStart = startIdx.toDouble() / SRA
        val k = ceil((tStart - 0.03 - anchor) / period)
        val autoCue = max(0.0, anchor + k * period)
        return Analysis(bpm, anchor, autoCue, key, gain, wave)
    }

    private fun mono(pcm: ShortBuffer, frames: Int): FloatArray {
        val n = frames / DS
        val out = FloatArray(n)
        val sc = 1f / (2f * DS * 32768f)
        for (i in 0 until n) {
            var s = 0f
            val base = i * DS * 2
            for (j in 0 until DS * 2) s += pcm.get(base + j)
            out[i] = s * sc
        }
        return out
    }

    /** 4 bytes por ponto (147 pontos/s): pico total, grave, médio, agudo. */
    private fun waveform(x: FloatArray): ByteArray {
        val bins = x.size / WAVE_HOP
        val aL = exp(-2 * PI * 200 / SRA).toFloat()
        val aM = exp(-2 * PI * 2000 / SRA).toFloat()
        var l1 = 0f
        var l2 = 0f
        val tot = FloatArray(bins); val lo = FloatArray(bins); val mi = FloatArray(bins); val hi = FloatArray(bins)
        var i = 0
        for (b in 0 until bins) {
            var pt = 0f; var pl = 0f; var pm = 0f; var ph = 0f
            for (j in 0 until WAVE_HOP) {
                val v = x[i++]
                l1 = v + aL * (l1 - v)
                l2 = v + aM * (l2 - v)
                val h = v - l2
                val m = l2 - l1
                pt = max(pt, abs(v)); pl = max(pl, abs(l1)); pm = max(pm, abs(m)); ph = max(ph, abs(h))
            }
            tot[b] = pt; lo[b] = pl; mi[b] = pm; hi[b] = ph
        }
        var mx = 1e-6f
        for (v in tot) if (v > mx) mx = v
        val out = ByteArray(bins * 4)
        fun q(v: Float) = (v / mx * 255f).toInt().coerceIn(0, 255).toByte()
        for (b in 0 until bins) {
            out[b * 4] = q(tot[b]); out[b * 4 + 1] = q(lo[b]); out[b * 4 + 2] = q(mi[b]); out[b * 4 + 3] = q(hi[b])
        }
        return out
    }

    private fun detrend(env: FloatArray, half: Int): FloatArray {
        val n = env.size
        val pre = DoubleArray(n + 1)
        for (i in 0 until n) pre[i + 1] = pre[i] + env[i]
        val out = FloatArray(n)
        for (i in 0 until n) {
            val a = max(0, i - half); val b = min(n, i + half + 1)
            val m = (pre[b] - pre[a]) / (b - a)
            out[i] = max(0.0, env[i] - m).toFloat()
        }
        return out
    }

    private fun tempo(e: FloatArray): Double {
        val n = e.size
        var mean = 0.0
        for (v in e) mean += v
        mean /= n
        val z = DoubleArray(n) { e[it] - mean }
        val maxLag = 320
        val acf = DoubleArray(maxLag + 2)
        for (lag in 1..maxLag + 1) {
            var s = 0.0
            for (i in 0 until n - lag) s += z[i] * z[i + lag]
            acf[lag] = s / (n - lag)
        }
        fun a(l: Double): Double {
            val i = l.toInt()
            if (i < 1 || i + 1 > maxLag + 1) return 0.0
            val fr = l - i
            return acf[i] * (1 - fr) + acf[i + 1] * fr
        }
        var best = 120.0
        var bs = -1e18
        var b = 70.0
        while (b <= 180.0) {
            val lag = 60.0 * FPS / b
            val s = a(lag) + 0.5 * a(2 * lag) + 0.25 * a(4 * lag)
            val lg = ln(b / 122.0) / ln(2.0)
            val w = exp(-0.5 * (lg / 0.5) * (lg / 0.5))
            val sc = s * (0.5 + 0.5 * w)
            if (sc > bs) { bs = sc; best = b }
            b += 0.1
        }
        return best
    }

    private fun comb(e: FloatArray, period: Double, phase: Double): Double {
        var s = 0.0
        var t = phase
        var n = 0
        val lim = e.size - 1
        while (t < lim) {
            val i = t.toInt()
            val fr = (t - i).toFloat()
            s += e[i] * (1 - fr) + e[i + 1] * fr
            t += period
            n++
        }
        return if (n > 0) s / n else 0.0
    }

    private fun bestPhase(e: FloatArray, period: Double): Pair<Double, Double> {
        var bp = 0.0
        var bs = -1e18
        var p = 0.0
        while (p < period) {
            val s = comb(e, period, p)
            if (s > bs) { bs = s; bp = p }
            p += 1.0
        }
        val c = bp
        var q = c - 1.0
        while (q <= c + 1.0) {
            val pp = if (q < 0) q + period else q
            val s = comb(e, period, pp)
            if (s > bs) { bs = s; bp = pp }
            q += 0.1
        }
        return bp to bs
    }

    /** Ajusta a grade ao ataque real das batidas (mediana dos desvios, ±35 ms). */
    private fun refineOffset(x: FloatArray, t0: Double, period: Double): Double {
        val hop = 32
        val w = 64
        val ne = (x.size - w) / hop
        if (ne < 100) return 0.0
        val en = DoubleArray(ne)
        for (j in 0 until ne) {
            var s = 0.0
            val o = j * hop
            for (i in 0 until w) { val v = x[o + i]; s += v * v }
            en[j] = s
        }
        val d = DoubleArray(ne)
        for (j in 1 until ne) d[j] = max(0.0, en[j] - en[j - 1])
        val dm = d.average()
        val offs = ArrayList<Double>()
        val dur = x.size.toDouble() / SRA
        var t = t0 - floor(t0 / period) * period
        val radius = (0.035 * SRA / hop).toInt()
        while (t < dur) {
            if (t > dur * 0.1 && t < dur * 0.9) {
                val jc = ((t * SRA - (w - hop / 2)) / hop).toInt()
                var bj = -1
                var bv = 0.0
                for (j in jc - radius..jc + radius) if (j in 1 until ne && d[j] > bv) { bv = d[j]; bj = j }
                if (bj >= 0 && bv > 3 * dm) {
                    val tb = (bj * hop + (w - hop / 2)).toDouble() / SRA
                    offs.add(tb - t)
                }
            }
            t += period
        }
        if (offs.size < 16) return 0.0
        offs.sort()
        return offs[offs.size / 2]
    }

    private fun cosSim(a: DoubleArray, b: DoubleArray): Double {
        var xy = 0.0; var xx = 0.0; var yy = 0.0
        for (i in a.indices) { xy += a[i] * b[i]; xx += a[i] * a[i]; yy += b[i] * b[i] }
        return if (xx <= 0 || yy <= 0) 1.0 else xy / sqrt(xx * yy)
    }
}
