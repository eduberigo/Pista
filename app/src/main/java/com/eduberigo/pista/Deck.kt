package com.eduberigo.pista

import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.nio.ShortBuffer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sqrt

/** Música carregada num deck: original + (quando houver) 6 faixas separadas, tudo mapeado do disco. */
class Loaded(
    val track: Track,
    val frames: Int,
    val orig: ShortBuffer,
    val stems: Array<ShortBuffer>?,
    val stemOff: Int,
    val files: List<File>,
) {
    val stemFrames: Int = stems?.minOf { it.limit() / 2 } ?: 0
}

/** Estado observável pela interface (alterado só na thread principal). */
class DeckUi {
    var track by mutableStateOf<Track?>(null)
    var status by mutableStateOf("")
    var wave by mutableStateOf<Wave?>(null)
    var cover by mutableStateOf<Bitmap?>(null)
    var stemsReady by mutableStateOf(false)
    var muteMask by mutableIntStateOf(0)
    var tapInfo by mutableStateOf("")
    var edits by mutableIntStateOf(0)
}

class Deck(val index: Int) {
    val ui = DeckUi()

    @Volatile var loaded: Loaded? = null
    @Volatile var playing = false
    /** Posição audível em quadros de 44,1 kHz. */
    @Volatile var pos = 0.0
    @Volatile var pitch = 0.0
    @Volatile var range = 0.08
    @Volatile var bend = 0.0
    @Volatile var bendTime = 0L
    @Volatile var keylock = true
    @Volatile var keyShift = 0
    @Volatile var sync = false
    @Volatile var vol = 1f
    @Volatile var trim = 0.5f
    @Volatile var eqHi = 0.5f
    @Volatile var eqMid = 0.5f
    @Volatile var eqLow = 0.5f
    @Volatile var filter = 0f
    @Volatile var cueHead = false
    @Volatile var loopOn = false
    @Volatile var loopStart = 0.0
    @Volatile var loopEnd = 0.0
    @Volatile var loopBeats = 4.0
    /** Razão de andamento efetiva (1 = original). Escrita pelo motor. */
    @Volatile var rate = 1.0
    @Volatile var peak = 0f
    /** Posição dos knobs das faixas (0..1, centro 0.5 = volume normal). */
    val stemKnob = FloatArray(6) { 0.5f }
    /** Knobs de grupo: 0 = voz, 1 = bateria, 2 = harmonia (baixo, guitarra, piano, outros). */
    val groupKnob = FloatArray(3) { 0.5f }
    /** Duração do corte dos botões VOZ/BAT/HARM em batidas (0 = seco). */
    @Volatile var killBeats = Pista.prefs.getFloat("killBeats", 4f)
    private val stemMute = BooleanArray(6)
    @Volatile private var seekReq = Double.NaN
    /** Entrada armada: começa quando o outro deck chegar nesta posição (quadros dele). */
    @Volatile var armAt = Double.NaN

    /** Só na thread de áudio: começa a tocar já desta posição, sem rampa de busca. */
    fun startNow(p: Double) {
        val l = loaded ?: return
        pos = p.coerceIn(0.0, (l.frames - 4).toDouble())
        seekReq = Double.NaN
        fadeSeek = false
        resetW()
        playing = true
    }

    val track: Track? get() = loaded?.track
    val bpm: Double get() = loaded?.track?.bpm ?: 0.0
    val effBpm: Double get() = bpm * rate
    val periodFrames: Double get() = (loaded?.track?.period ?: 0.5) * SR
    val anchorFrames: Double get() = (loaded?.track?.anchor ?: 0.0) * SR
    val seconds: Double get() = pos / SR

    // ---------- comandos (thread da interface) ----------

    fun seek(p: Double) {
        val l = loaded ?: return
        seekReq = p.coerceIn(0.0, (l.frames - 4).toDouble())
    }

    fun seekBy(d: Double) = seek((if (seekReq.isNaN()) pos else seekReq) + d)

    fun nudge(amount: Double) { bend = amount; bendTime = System.nanoTime() }

    fun quantized(p: Double): Double {
        val l = loaded ?: return p
        if (l.track.bpm <= 0 || !Pista.engine.quantize) return p
        val per = periodFrames
        val a = anchorFrames
        return a + round((p - a) / per) * per
    }

    fun beatIndexAt(p: Double): Double = (p - anchorFrames) / periodFrames

    fun setCue() {
        val t = track ?: return
        t.cue = quantized(pos) / SR
        saveEdit()
    }

    fun gotoCue() {
        val t = track ?: return
        seek(t.startCue * SR)
    }

    fun hotCue(i: Int) {
        val t = track ?: return
        if (t.hotcues[i] < 0) {
            t.hotcues[i] = quantized(pos) / SR
            saveEdit()
        } else {
            seek(t.hotcues[i] * SR)
        }
    }

    fun clearHotCue(i: Int) {
        val t = track ?: return
        t.hotcues[i] = -1.0
        saveEdit()
    }

    fun toggleLoop() {
        if (loaded == null) return
        if (loopOn) { loopOn = false; return }
        val s = quantized(pos)
        loopStart = s
        loopEnd = s + loopBeats * periodFrames
        loopOn = true
    }

    fun changeLoopBeats(b: Double) {
        loopBeats = b.coerceIn(1.0 / 32, 128.0)
        if (loopOn) loopEnd = loopStart + loopBeats * periodFrames
    }

    /** Próximo tamanho de laço na escala (inclui 3, 6, 12, 24, 48 batidas). */
    fun loopStep(dir: Int) {
        val cur = loopBeats
        val next = if (dir > 0) LOOP_STEPS.firstOrNull { it > cur + 1e-6 } ?: LOOP_STEPS.last()
        else LOOP_STEPS.lastOrNull { it < cur - 1e-6 } ?: LOOP_STEPS.first()
        changeLoopBeats(next)
    }

    /** Laço de [beats] batidas: liga a partir da batida atual, ou só muda o tamanho se já estiver ligado. */
    fun setLoop(beats: Double) {
        if (loaded == null) return
        if (loopOn && abs(loopBeats - beats) < 1e-6) { loopOn = false; return }
        changeLoopBeats(beats)
        if (!loopOn) {
            val s = quantized(pos)
            loopStart = s
            loopEnd = s + loopBeats * periodFrames
            loopOn = true
        }
    }

    @Volatile var loopInPending = -1.0

    fun loopIn() {
        if (loaded == null) return
        loopInPending = quantized(pos)
        loopOn = false
    }

    fun loopOut() {
        val s = loopInPending
        if (loaded == null || s < 0) return
        val e = quantized(pos)
        if (e <= s + 100) return
        loopStart = s
        loopEnd = e
        loopBeats = (e - s) / periodFrames
        loopOn = true
        loopInPending = -1.0
    }

    fun beatJump(beats: Double) {
        val d = beats * periodFrames
        if (loopOn) { loopStart += d; loopEnd += d }
        seekBy(d)
    }

    /**
     * Grade: o "1" do compasso passa a ser a batida mais próxima da agulha.
     * Com [exact], usa a posição exata (para quando a grade inteira está deslocada).
     */
    fun downbeatHere(exact: Boolean = false) {
        val t = track ?: return
        val p = pos / SR
        t.anchor = if (exact || t.bpm <= 0) p else t.anchor + round((p - t.anchor) / t.period) * t.period
        t.anchorManual = true
        saveEdit()
    }

    /** Troca o BPM mantendo a batida perto da agulha no mesmo lugar (e o "1" no mesmo tempo do compasso). */
    fun setBpmPivot(newBpm: Double) {
        val t = track ?: return
        val nb = round(newBpm * 100) / 100
        if (nb <= 0) return
        if (t.bpm > 0) {
            val oldPer = t.period
            val k = round((pos / SR - t.anchor) / oldPer)
            val beat = t.anchor + k * oldPer
            t.anchor = beat - k * (60.0 / nb)
        }
        t.bpm = nb
        saveEdit()
    }

    private val taps = ArrayList<Long>()
    private val refineTask = Runnable { refineBpm() }

    /** Tap tempo: a partir da 4ª batida já ajusta o BPM; 1,5 s depois da última, afina pela análise do áudio. */
    fun tap() {
        val now = System.nanoTime()
        if (taps.isNotEmpty() && now - taps.last() > 2_000_000_000L) taps.clear()
        taps.add(now)
        if (taps.size > 16) taps.removeAt(0)
        val n = taps.size
        if (n < 4) { ui.tapInfo = "TAP: continue batendo ($n)"; return }
        val mi = (n - 1) / 2.0
        val mt = taps.average()
        var num = 0.0
        var den = 0.0
        for (i in 0 until n) { num += (i - mi) * (taps[i] - mt); den += (i - mi) * (i - mi) }
        val bpm = 60e9 / (num / den)
        if (bpm !in 40.0..240.0) { ui.tapInfo = "TAP: ritmo irregular, recomece"; taps.clear(); return }
        setBpmPivot(bpm)
        ui.tapInfo = "TAP: %.1f BPM (afinando ao parar)".format(bpm)
        Pista.main.removeCallbacks(refineTask)
        Pista.main.postDelayed(refineTask, 1500)
    }

    /** Afina o BPM e a grade com a análise do áudio, procurando só perto do BPM atual. */
    fun refineBpm() {
        val l = loaded ?: return
        val t = l.track
        if (t.bpm <= 0) return
        val hint = t.bpm
        ui.tapInfo = "Afinando BPM…"
        Thread {
            try {
                Work.analyze(t, l.orig, l.frames, { loaded !== l }, hint)
                Pista.main.post { ui.tapInfo = "BPM afinado: %.2f".format(t.bpm); ui.edits++ }
            } catch (e: Throwable) {
                Pista.main.post { ui.tapInfo = "" }
            }
        }.start()
    }

    /** Andamento de volta ao original: tira o SYNC e zera o pitch. */
    fun resetTempo() {
        sync = false
        pitch = 0.0
        bend = 0.0
    }

    /** BPM e grade de volta ao que a análise achou (desfaz tap, ×2, ajustes de grade). */
    fun restoreGrid(ctx: android.content.Context): Boolean {
        val t = track ?: return false
        if (t.bpmAuto > 0) {
            t.bpm = t.bpmAuto
            t.anchor = t.anchorAuto
            t.anchorManual = false
            saveEdit()
            ui.tapInfo = "BPM da análise: %.2f".format(t.bpm)
            return true
        }
        // músicas analisadas antes desta versão: refaz a análise
        val l = loaded ?: return false
        ui.tapInfo = "Refazendo a análise…"
        Thread {
            try {
                t.anchorManual = false
                Work.analyze(t, l.orig, l.frames, { loaded !== l })
                Pista.main.post { ui.tapInfo = "BPM da análise: %.2f".format(t.bpm); ui.edits++ }
            } catch (e: Throwable) {
                Pista.main.post { ui.tapInfo = "" }
            }
        }.start()
        return true
    }

    /** Corrige o tom da música (k = 0..23); -1 volta ao tom detectado pelo app. */
    fun setKey(k: Int) {
        val t = track ?: return
        Keys.setTrackKey(t, k)
        saveEdit()
    }

    fun resetCue() {
        val t = track ?: return
        t.cue = -1.0
        saveEdit()
    }

    /** Início onde a bateria entra (precisa das faixas separadas), encaixado no "1" do compasso. */
    fun startAtDrums(): Boolean {
        val l = loaded ?: return false
        val st = l.stems ?: return false
        val dr = st[1]
        val win = SR / 10
        val nw = l.stemFrames / win
        if (nw < 10) return false
        val e = DoubleArray(nw)
        for (w in 0 until nw) {
            var s = 0.0
            var i = w * win
            val end = i + win
            while (i < end) { val v = dr.get(i * 2) + dr.get(i * 2 + 1); s += v.toDouble() * v; i += 4 }
            e[w] = s
        }
        val mean = e.average()
        if (mean <= 0) return false
        val thr = mean * 0.15
        var first = -1
        for (w in 0 until nw - 4) {
            if (e[w] > thr && e[w + 1] > thr * 0.5 && e[w + 2] > thr * 0.5 && e[w + 3] > thr * 0.5) { first = w; break }
        }
        if (first < 0) return false
        val t = l.track
        val sec = (first * win - l.stemOff).toDouble() / SR
        t.cue = if (t.bpm > 0) {
            val bar = 4 * t.period
            t.anchor + ceil((sec - 0.08 - t.anchor) / bar) * bar
        } else sec
        if (t.cue < 0) t.cue = 0.0
        saveEdit()
        return true
    }

    /** Grade: move o "1" em batidas inteiras (ex.: +1 = o próximo tempo vira o 1). */
    fun shiftDownbeat(beats: Int) {
        val t = track ?: return
        t.anchor += beats * t.period
        saveEdit()
    }

    fun nudgeGrid(ms: Double) {
        val t = track ?: return
        t.anchor += ms / 1000.0
        saveEdit()
    }

    fun scaleBpm(f: Double) {
        val t = track ?: return
        if (t.bpm <= 0) return
        t.bpm = round(t.bpm * f * 100) / 100
        saveEdit()
    }

    fun fineBpm(d: Double) {
        val t = track ?: return
        if (t.bpm <= 0) return
        setBpmPivot(t.bpm + d)
    }

    private fun saveEdit() {
        Pista.library.save()
        Pista.main.post { ui.edits++ }
    }

    fun setStemKnob(k: Int, v: Float) { stemKnob[k] = v }

    /** Grupos: 0 = voz, 1 = bateria, 2 = harmonia (baixo, guitarra, piano, outros). */
    fun toggleGroup(g: Int) {
        val members = when (g) { 0 -> intArrayOf(0); 1 -> intArrayOf(1); else -> intArrayOf(2, 3, 4, 5) }
        val allMuted = members.all { stemMute[it] }
        for (m in members) stemMute[m] = !allMuted
        var mask = 0
        for (k in 0 until 6) if (stemMute[k]) mask = mask or (1 shl k)
        ui.muteMask = mask
    }

    fun groupMuted(mask: Int, g: Int): Boolean = when (g) {
        0 -> mask and 1 != 0
        1 -> mask and 2 != 0
        else -> mask and 0b111100 == 0b111100
    }

    fun resetStems() {
        for (k in 0 until 6) { stemKnob[k] = 0.5f; stemMute[k] = false }
        for (g in 0 until 3) groupKnob[g] = 0.5f
        ui.muteMask = 0
    }

    // ---------- carregamento ----------

    fun attach(l: Loaded) {
        playing = false
        loopOn = false
        loaded = l
        pos = l.track.startCue * SR
        seekReq = pos
    }

    fun addStems(stems: Array<ShortBuffer>, off: Int, files: List<File>): Boolean {
        val c = loaded ?: return false
        loaded = Loaded(c.track, c.frames, c.orig, stems, off, c.files + files)
        return true
    }

    // ---------- áudio (thread do motor) ----------

    private var amp = 0f
    private var fadeSeek = false
    private var curMix = 0f
    private val curG = FloatArray(6) { 1f }
    private val newG = FloatArray(6)
    private val smG = FloatArray(6) { 1f }
    private val muteLvl = FloatArray(6) { 1f }
    private var sGl = 1f; private var sGm = 1f; private var sGh = 1f; private var sTrim = 1f
    private val bL = FloatArray(MAXB)
    private val bR = FloatArray(MAXB)

    // EQ de 3 bandas com cruzamentos Linkwitz-Riley (24 dB/oitava) em 250 Hz e 3,5 kHz
    private val lo1 = Biquad(); private val lo2 = Biquad()
    private val up1 = Biquad(); private val up2 = Biquad()
    private val md1 = Biquad(); private val md2 = Biquad()
    private val hi1 = Biquad(); private val hi2 = Biquad()
    private val flt = Biquad()
    private var eqRate = 0
    private var lastFilter = 0f
    private var filterOn = false
    private var sFilter = 0f

    // WSOLA (andamento sem mudar o tom)
    private val win = FloatArray(GN) { (0.5 - 0.5 * cos(2 * PI * it / GN)).toFloat() }
    private val grL = FloatArray(GN); private val grR = FloatArray(GN)
    private val qL = FloatArray(GH); private val qR = FloatArray(GH)
    private val tailL = FloatArray(GH); private val tailR = FloatArray(GH)
    private var qPos = GH
    private var prevStart = 0.0
    private var hopPos = 0.0
    private var hopAdv = 1.0
    private var nextHop = 0.0
    private var wReset = true
    private var wMode = false
    private val ref = FloatArray(128)

    private fun resetW() {
        wReset = true
        qPos = GH
        tailL.fill(0f); tailR.fill(0f)
        nextHop = pos
    }

    private fun setupEq(rate: Int) {
        eqRate = rate
        val fs = rate.toDouble()
        lo1.lowpass(250.0, 0.7071, fs); lo2.lowpass(250.0, 0.7071, fs)
        up1.highpass(250.0, 0.7071, fs); up2.highpass(250.0, 0.7071, fs)
        md1.lowpass(3500.0, 0.7071, fs); md2.lowpass(3500.0, 0.7071, fs)
        hi1.highpass(3500.0, 0.7071, fs); hi2.highpass(3500.0, 0.7071, fs)
    }

    private fun eqGain(k: Float): Float =
        if (k <= 0.5f) { val x = k / 0.5f; x * x } else 1f + (k - 0.5f) / 0.5f

    private fun stemGain(k: Float): Float =
        if (k <= 0.5f) k / 0.5f else 1f + (k - 0.5f) / 0.5f * 0.6f

    fun render(oL: FloatArray, oR: FloatArray, n: Int, outRate: Int) {
        val l = loaded
        if (l == null) { zero(oL, oR, n); peak = 0f; amp = 0f; return }
        if (outRate != eqRate) setupEq(outRate)

        val sr = seekReq
        if (!sr.isNaN()) {
            if (amp > 0f && !fadeSeek) {
                fadeSeek = true
            } else {
                pos = sr
                seekReq = Double.NaN
                fadeSeek = false
                resetW()
            }
        }
        val target = if (playing && !fadeSeek) 1f else 0f
        if (amp == 0f && target == 0f) { zero(oL, oR, n); peak = 0f; return }

        // faixas: muda suavemente (~120 ms) em vez de cortar seco
        val blockSec = n.toFloat() / outRate
        val aSt = 1f - kotlin.math.exp(-blockSec / 0.12f)
        var any = false
        for (k in 0 until 6) {
            val grp = if (k < 2) k else 2
            // botões VOZ/BAT/HARM: some/volta numa rampa do tamanho escolhido (seco, 1 tempo, 1 ou 2 compassos)
            val fadeSec = if (killBeats <= 0f) 0.0 else killBeats * (loaded?.track?.period ?: 0.5) / rate
            val mTgt = if (stemMute[k]) 0f else 1f
            muteLvl[k] = if (fadeSec <= 0.0) mTgt else {
                val step = (blockSec / fadeSec).toFloat()
                if (muteLvl[k] < mTgt) minOf(mTgt, muteLvl[k] + step) else maxOf(mTgt, muteLvl[k] - step)
            }
            val tgt = muteLvl[k] * stemGain(stemKnob[k]) * stemGain(groupKnob[grp])
            var g = smG[k] + (tgt - smG[k]) * aSt
            if (abs(tgt - g) < 0.002f) g = tgt
            smG[k] = g
            newG[k] = g
            if (g != 1f) any = true
        }
        val mixT = if (l.stems != null && any) 1f else 0f
        val srcRatio = SR.toDouble() / outRate
        val adv = srcRatio * rate
        val pf = 2.0.pow(keyShift / 12.0)
        val useW = keylock || keyShift != 0
        if (useW != wMode) { wMode = useW; resetW() }
        if (useW) {
            val gs = (if (keylock) srcRatio else adv) * pf
            renderW(l, n, gs, adv, mixT)
        } else {
            renderV(l, n, adv, mixT)
        }
        System.arraycopy(newG, 0, curG, 0, 6)
        curMix = mixT

        // filtro (valor suavizado para não dar degraus)
        sFilter += (filter - sFilter) * (1f - kotlin.math.exp(-blockSec / 0.05f))
        if (abs(filter - sFilter) < 0.002f) sFilter = filter
        val f = sFilter
        if (f != lastFilter || !filterOn) {
            val on = abs(f) > 0.03f
            if (on) {
                val fs = outRate.toDouble()
                if (f < 0) flt.lowpass(20000.0 * (60.0 / 20000.0).pow(min(1.0, -f.toDouble())), 1.0, fs)
                else flt.highpass(20.0 * (10000.0 / 20.0).pow(f.toDouble()), 1.0, fs)
                if (!filterOn) flt.reset()
            }
            filterOn = on
            lastFilter = f
        }

        // EQ e ganho: seguem o knob com ~40 ms de suavização, rampa dentro do bloco
        val aEq = 1f - kotlin.math.exp(-blockSec / 0.04f)
        val pGl = sGl; val pGm = sGm; val pGh = sGh; val pTr = sTrim
        sGl += (eqGain(eqLow) - sGl) * aEq
        sGm += (eqGain(eqMid) - sGm) * aEq
        sGh += (eqGain(eqHi) - sGh) * aEq
        sTrim += (l.track.gain * Dsp.db((trim - 0.5f) * 24f) - sTrim) * aEq
        val a0 = amp
        var pk = 0f
        for (i in 0 until n) {
            val fr = (i + 1f) / n
            val ramp = a0 + (target - a0) * fr
            val g = pTr + (sTrim - pTr) * fr
            val gl = pGl + (sGl - pGl) * fr
            val gm = pGm + (sGm - pGm) * fr
            val gh = pGh + (sGh - pGh) * fr
            val xl = bL[i] * g
            val xr = bR[i] * g
            // bandas separadas de verdade (a versão anterior deixava o grave vazar na banda média)
            val lowL = lo2.l(lo1.l(xl)); val lowR = lo2.r(lo1.r(xr))
            val upL = up2.l(up1.l(xl)); val upR = up2.r(up1.r(xr))
            val midL = md2.l(md1.l(upL)); val midR = md2.r(md1.r(upR))
            val hiL = hi2.l(hi1.l(upL)); val hiR = hi2.r(hi1.r(upR))
            var yl = lowL * gl + midL * gm + hiL * gh
            var yr = lowR * gl + midR * gm + hiR * gh
            if (filterOn) { yl = flt.l(yl); yr = flt.r(yr) }
            yl *= ramp; yr *= ramp
            oL[i] = yl; oR[i] = yr
            val a = max(abs(yl), abs(yr))
            if (a > pk) pk = a
        }
        peak = pk
        amp = target
        if (pos >= l.frames - 4) { playing = false; pos = (l.frames - 4).toDouble() }
    }

    private fun zero(a: FloatArray, b: FloatArray, n: Int) {
        java.util.Arrays.fill(a, 0, n, 0f); java.util.Arrays.fill(b, 0, n, 0f)
    }

    private fun renderV(l: Loaded, n: Int, adv: Double, mixT: Float) {
        var j = 0
        while (j < n) {
            var cnt = n - j
            val lo = loopOn; val le = loopEnd; val ls = loopStart
            if (lo && le > ls && pos < le && adv > 0) {
                val k = ceil((le - pos) / adv).toInt()
                if (k in 1 until cnt) cnt = k
            }
            val a0 = j.toFloat() / n
            val a1 = (j + cnt).toFloat() / n
            readSpan(l, pos, adv, cnt, bL, bR, j, curG, newG, curMix, mixT, a0, a1)
            pos += cnt * adv
            if (lo && le > ls && pos >= le) pos = ls + (pos - le) % (le - ls)
            j += cnt
        }
    }

    private fun renderW(l: Loaded, n: Int, gs: Double, adv: Double, mixT: Float) {
        var j = 0
        while (j < n) {
            if (qPos >= GH) makeGrain(l, gs, adv, mixT)
            val c = min(n - j, GH - qPos)
            System.arraycopy(qL, qPos, bL, j, c)
            System.arraycopy(qR, qPos, bR, j, c)
            qPos += c
            j += c
        }
        pos = hopPos + qPos * hopAdv
    }

    private fun makeGrain(l: Loaded, gs: Double, adv: Double, mixT: Float) {
        val ideal = nextHop
        val start = if (wReset) ideal else ideal + search(l, prevStart + GH * gs, ideal, gs)
        readSpan(l, start, gs, GN, grL, grR, 0, newG, newG, mixT, mixT, 0f, 1f)
        for (i in 0 until GH) {
            qL[i] = tailL[i] + grL[i] * win[i]
            qR[i] = tailR[i] + grR[i] * win[i]
            tailL[i] = grL[GH + i] * win[GH + i]
            tailR[i] = grR[GH + i] * win[GH + i]
        }
        prevStart = start
        wReset = false
        qPos = 0
        hopPos = ideal
        hopAdv = adv
        var nh = ideal + GH * adv
        val lo = loopOn; val le = loopEnd; val ls = loopStart
        // volta ao início do laço no salto mais próximo do fim (erro máximo de meio salto, ~10 ms)
        if (lo && le > ls && nh >= le - GH * adv * 0.5) {
            nh = if (nh >= le) ls + (nh - le) % (le - ls) else ls - (le - nh)
        }
        nextHop = nh
    }

    /** Procura o deslocamento (±512 amostras) que melhor continua o grão anterior. */
    private fun search(l: Loaded, natural: Double, ideal: Double, gs: Double): Double {
        val span = 4 * gs
        for (j in 0 until 128) ref[j] = mono(l, natural + j * span)
        var best = 0.0
        var bs = -1e30
        var d = -512.0
        while (d <= 512.0) {
            val s = score(l, ideal + d, span)
            if (s > bs) { bs = s; best = d }
            d += 8.0
        }
        val c = best
        d = c - 7.0
        while (d <= c + 7.0) {
            val s = score(l, ideal + d, span)
            if (s > bs) { bs = s; best = d }
            d += 1.0
        }
        return best
    }

    private fun score(l: Loaded, start: Double, span: Double): Double {
        var xy = 0.0
        var yy = 1e-9
        for (j in 0 until 128) {
            val y = mono(l, start + j * span)
            xy += ref[j] * y
            yy += y * y
        }
        return xy / sqrt(yy)
    }

    private fun mono(l: Loaded, p: Double): Float {
        val i = p.toInt()
        if (i < 0 || i >= l.frames) return 0f
        return (l.orig.get(i * 2) + l.orig.get(i * 2 + 1)).toFloat()
    }

    private fun readSpan(
        l: Loaded, start: Double, step: Double, n: Int,
        dL: FloatArray, dR: FloatArray, off: Int,
        gA: FloatArray, gB: FloatArray, mA: Float, mB: Float, a0: Float, a1: Float,
    ) {
        val fr = l.frames
        val st = l.stems
        val useStems = st != null && (mA > 0f || mB > 0f)
        val so = l.stemOff
        val sf = l.stemFrames
        val ob = l.orig
        val inv = 1f / 32768f
        for (j in 0 until n) {
            val p = start + j * step
            val i = floor(p).toInt()
            if (i < 1 || i + 2 >= fr) { dL[off + j] = 0f; dR[off + j] = 0f; continue }
            val t = (p - i).toFloat()
            var sl = herm(ob, i, 0, t)
            var sr = herm(ob, i, 1, t)
            if (useStems) {
                val a = a0 + (a1 - a0) * j / n
                val m = mA + (mB - mA) * a
                var xl = 0f
                var xr = 0f
                val si = i + so
                if (si >= 1 && si + 2 < sf) {
                    for (k in 0 until 6) {
                        val g = gA[k] + (gB[k] - gA[k]) * a
                        if (g != 0f) {
                            val b = st!![k]
                            xl += g * herm(b, si, 0, t)
                            xr += g * herm(b, si, 1, t)
                        }
                    }
                }
                sl = sl * (1 - m) + xl * m
                sr = sr * (1 - m) + xr * m
            }
            dL[off + j] = sl * inv
            dR[off + j] = sr * inv
        }
    }

    private fun herm(b: ShortBuffer, i: Int, c: Int, t: Float): Float {
        val k = i * 2 + c
        return Dsp.hermite(b.get(k - 2).toFloat(), b.get(k).toFloat(), b.get(k + 2).toFloat(), b.get(k + 4).toFloat(), t)
    }

    companion object {
        const val MAXB = 1024
        val LOOP_STEPS = doubleArrayOf(1.0 / 8, 1.0 / 4, 1.0 / 2, 1.0, 2.0, 3.0, 4.0, 6.0, 8.0, 12.0, 16.0, 24.0, 32.0, 48.0, 64.0)
        const val GN = 2048
        const val GH = 1024
    }
}
