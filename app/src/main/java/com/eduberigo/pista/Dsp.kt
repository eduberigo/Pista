package com.eduberigo.pista

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.pow

/** Taxa interna de todo o áudio do app. */
const val SR = 44100

/** FFT complexa radix-2, in-place. */
class FFT(val n: Int) {
    private val levels = 31 - Integer.numberOfLeadingZeros(n)
    private val cosT = DoubleArray(n / 2) { cos(2 * PI * it / n) }
    private val sinT = DoubleArray(n / 2) { sin(2 * PI * it / n) }
    private val rev = IntArray(n) { Integer.reverse(it) ushr (32 - levels) }

    fun transform(re: DoubleArray, im: DoubleArray) {
        for (i in 0 until n) {
            val j = rev[i]
            if (j > i) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var size = 2
        while (size <= n) {
            val half = size / 2
            val step = n / size
            var i = 0
            while (i < n) {
                var k = 0
                for (j in i until i + half) {
                    val l = j + half
                    val tre = re[l] * cosT[k] + im[l] * sinT[k]
                    val tim = -re[l] * sinT[k] + im[l] * cosT[k]
                    re[l] = re[j] - tre
                    im[l] = im[j] - tim
                    re[j] += tre
                    im[j] += tim
                    k += step
                }
                i += size
            }
            size *= 2
        }
    }
}

/** Biquad estéreo (forma direta transposta II), fórmulas RBJ. */
class Biquad {
    private var b0 = 1.0; private var b1 = 0.0; private var b2 = 0.0
    private var a1 = 0.0; private var a2 = 0.0
    private var l1 = 0.0; private var l2 = 0.0; private var r1 = 0.0; private var r2 = 0.0

    fun lowpass(fc: Double, q: Double, fs: Double) {
        val w = 2 * PI * fc.coerceIn(10.0, fs * 0.45) / fs
        val c = cos(w); val al = sin(w) / (2 * q); val a0 = 1 + al
        b0 = (1 - c) / 2 / a0; b1 = (1 - c) / a0; b2 = b0
        a1 = -2 * c / a0; a2 = (1 - al) / a0
    }

    fun highpass(fc: Double, q: Double, fs: Double) {
        val w = 2 * PI * fc.coerceIn(10.0, fs * 0.45) / fs
        val c = cos(w); val al = sin(w) / (2 * q); val a0 = 1 + al
        b0 = (1 + c) / 2 / a0; b1 = -(1 + c) / a0; b2 = b0
        a1 = -2 * c / a0; a2 = (1 - al) / a0
    }

    fun reset() { l1 = 0.0; l2 = 0.0; r1 = 0.0; r2 = 0.0 }

    fun l(x: Float): Float {
        val y = b0 * x + l1
        l1 = b1 * x - a1 * y + l2
        l2 = b2 * x - a2 * y
        return y.toFloat()
    }

    fun r(x: Float): Float {
        val y = b0 * x + r1
        r1 = b1 * x - a1 * y + r2
        r2 = b2 * x - a2 * y
        return y.toFloat()
    }
}

object Dsp {
    fun hermite(xm1: Float, x0: Float, x1: Float, x2: Float, t: Float): Float {
        val c = (x1 - xm1) * 0.5f
        val v = x0 - x1
        val w = c + v
        val a = w + v + (x2 - x0) * 0.5f
        val bNeg = w + a
        return (((a * t) - bNeg) * t + c) * t + x0
    }

    fun db(db: Float): Float = 10f.pow(db / 20f)
}
