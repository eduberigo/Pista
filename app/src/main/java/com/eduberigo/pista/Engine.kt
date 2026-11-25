package com.eduberigo.pista

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Process
import androidx.compose.runtime.mutableStateOf
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.tanh

/**
 * Mixer: dois decks, crossfader, master e fone.
 * Saídas: 0 = estéreo; 1 = dividida (mix no lado esquerdo, fone no direito, com cabo divisor);
 * 2 = interface USB de 4 canais (1-2 mix, 3-4 fone).
 */
class Engine {
    val decks = arrayOf(Deck(0), Deck(1))
    @Volatile var xfade = 0.5f
    @Volatile var master = 0.85f
    @Volatile var cueMix = 0.0f
    @Volatile var quantize = true
    @Volatile var curve = 0
    @Volatile var outMode = 0
    @Volatile var leader = 0
    @Volatile var peakL = 0f
    @Volatile var peakR = 0f
    @Volatile var latencyMs = 0
    val outStatus = mutableStateOf("")

    @Volatile private var running = false
    private var sx = 0.5f
    private var sv0 = 1f
    private var sv1 = 1f
    @Volatile private var restart = false

    val isPlaying: Boolean get() = decks[0].playing || decks[1].playing

    fun loadPrefs() {
        val p = Pista.prefs
        outMode = p.getInt("outMode", 0)
        curve = p.getInt("curve", 0)
        quantize = p.getBoolean("quantize", true)
        val r = p.getFloat("range", 0.08f).toDouble()
        for (d in decks) d.range = r
    }

    fun changeOutMode(m: Int) {
        outMode = m
        Pista.prefs.edit().putInt("outMode", m).apply()
        restart = true
    }

    fun start() {
        if (running) return
        running = true
        Thread({ loop() }, "pista-audio").start()
    }

    // ---------- comandos ----------

    fun togglePlay(d: Deck) {
        if (d.loaded == null) return
        if (d.playing) { d.playing = false; return }
        val o = decks[1 - d.index]
        if (d.sync && o.loaded != null && o.playing && leader != d.index) align(d, bar = true)
        d.playing = true
    }

    fun toggleSync(d: Deck) {
        if (d.sync) {
            d.pitch = (d.rate - 1.0).coerceIn(-0.5, 0.5)
            d.sync = false
            return
        }
        val o = decks[1 - d.index]
        if (o.loaded != null && o.bpm > 0) {
            leader = o.index
            d.sync = true
            if (o.playing && d.playing) align(d, bar = false)
            else if (o.playing) align(d, bar = true)
        } else {
            leader = d.index
            d.sync = true
        }
    }

    /** Quantas batidas do líder cabem numa batida deste deck (0,5, 1 ou 2…). */
    private fun syncRatio(d: Deck): Pair<Double, Double>? {
        val m = decks[leader]
        if (m === d) return null
        val db = d.bpm
        val mb = m.bpm
        if (db <= 0 || mb <= 0 || m.loaded == null) return null
        val mEff = mb * (1.0 + m.pitch)
        var r = mEff / db
        var mult = 1.0
        while (r > 1.5) { r /= 2; mult *= 2 }
        while (r < 0.75) { r *= 2; mult /= 2 }
        return r to mult
    }

    /** Encaixa a fase deste deck no líder: na batida, ou no compasso inteiro. */
    fun align(d: Deck, bar: Boolean) {
        val m = decks[1 - d.index]
        val dl = d.loaded ?: return
        val ml = m.loaded ?: return
        if (dl.track.bpm <= 0 || ml.track.bpm <= 0) return
        val dp = dl.track.period * SR
        val mp = ml.track.period * SR
        var mult = 1.0
        val r0 = (ml.track.bpm * m.rate) / (dl.track.bpm * d.rate)
        var r = r0
        while (r > 1.5) { r /= 2; mult *= 2 }
        while (r < 0.75) { r *= 2; mult /= 2 }
        val mBeats = (m.pos - ml.track.anchor * SR) / mp
        val target = mBeats / mult
        val dBeats = (d.pos - dl.track.anchor * SR) / dp
        val mod = if (bar) 4.0 else 1.0
        var diff = (target - dBeats) % mod
        if (diff < 0) diff += mod
        if (diff > mod / 2) diff -= mod
        d.seek(d.pos + diff * dp)
    }

    // ---------- áudio ----------

    private fun loop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        while (running) {
            try {
                runTrack()
            } catch (e: Throwable) {
                Pista.main.post { outStatus.value = "Erro de áudio: ${e.message}" }
                Thread.sleep(500)
            }
        }
    }

    private fun findUsb(am: AudioManager): AudioDeviceInfo? =
        am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull {
            (it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET) &&
                (it.channelCounts.isEmpty() || it.channelCounts.any { c -> c >= 4 })
        }

    private fun build(rate: Int, ch: Int): AudioTrack {
        val fmtB = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(rate)
        if (ch == 4) fmtB.setChannelIndexMask(0xF) else fmtB.setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
        val minBuf = AudioTrack.getMinBufferSize(
            rate,
            if (ch == 4) AudioFormat.CHANNEL_OUT_QUAD else AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_FLOAT,
        )
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setAudioFormat(fmtB.build())
            .setBufferSizeInBytes(max(minBuf * 2, BLOCK * ch * 4 * 4))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()
    }

    private fun runTrack() {
        restart = false
        val am = Pista.app.getSystemService(AudioManager::class.java)!!
        val rate = (am.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull() ?: 48000)
            .takeIf { it in 8000..192000 } ?: 48000
        var mode = outMode
        var ch = 2
        var at: AudioTrack? = null
        if (mode == 2) {
            val usb = findUsb(am)
            if (usb != null) {
                try {
                    val t = build(rate, 4)
                    t.setPreferredDevice(usb)
                    at = t
                    ch = 4
                    Pista.main.post { outStatus.value = "USB 4 canais: ${usb.productName}" }
                } catch (e: Exception) {
                    at = null
                }
            }
            if (at == null) {
                mode = 1
                Pista.main.post { outStatus.value = "Interface USB de 4 canais não encontrada; usando saída dividida" }
            }
        } else {
            Pista.main.post { outStatus.value = "" }
        }
        val track = at ?: build(rate, 2)
        // latência baixa: começa com um buffer pequeno e só aumenta se houver falhas (underrun)
        var bufFrames = BLOCK * 2
        runCatching { track.setBufferSizeInFrames(bufFrames) }
        var lastUnder = track.underrunCount
        var checkAt = System.nanoTime() + 500_000_000L
        track.play()
        val n = BLOCK
        val out = FloatArray(n * ch)
        val aL = FloatArray(n); val aR = FloatArray(n)
        val bL = FloatArray(n); val bR = FloatArray(n)
        var pv0 = 0f
        var pv1 = 0f
        var pm = master
        try {
            while (running && !restart) {
                val now = System.nanoTime()
                for (d in decks) {
                    if (d.bend != 0.0 && now - d.bendTime > 90_000_000L) d.bend = 0.0
                    var base = 1.0 + d.pitch
                    if (d.sync) {
                        val sr = syncRatio(d)
                        if (sr != null) { base = sr.first; d.pitch = base - 1.0 }
                    }
                    d.rate = base * (1.0 + d.bend)
                }
                decks[0].render(aL, aR, n, rate)
                decks[1].render(bL, bR, n, rate)
                val blockSec = n.toFloat() / rate
                // crossfader e volumes seguem o dedo com ~50 ms de suavização
                val ax = 1f - kotlin.math.exp(-blockSec / 0.05f)
                sx += (xfade - sx) * ax
                sv0 += (decks[0].vol - sv0) * ax
                sv1 += (decks[1].vol - sv1) * ax
                val x = sx
                val g0: Float
                val g1: Float
                when (curve) {
                    2 -> {
                        g0 = if (x < 0.94f) 1f else ((1f - x) / 0.06f).coerceIn(0f, 1f)
                        g1 = if (x > 0.06f) 1f else (x / 0.06f).coerceIn(0f, 1f)
                    }
                    1 -> {
                        g0 = if (x <= 0.5f) 1f else cos((x - 0.5f) * PI).toFloat()
                        g1 = if (x >= 0.5f) 1f else cos((0.5f - x) * PI).toFloat()
                    }
                    else -> {
                        // potência constante no curso inteiro: uma sobe enquanto a outra desce, sem buraco nem pico no meio
                        g0 = cos(x * PI / 2).toFloat()
                        g1 = kotlin.math.sin(x * PI / 2).toFloat()
                    }
                }
                val v0 = sv0 * g0
                val v1 = sv1 * g1
                val mv = master
                val c0 = decks[0].cueHead
                val c1 = decks[1].cueHead
                val cm = cueMix
                var pkL = 0f
                var pkR = 0f
                for (i in 0 until n) {
                    val t = (i + 1f) / n
                    val va = pv0 + (v0 - pv0) * t
                    val vb = pv1 + (v1 - pv1) * t
                    val mm = pm + (mv - pm) * t
                    val mL = clip((aL[i] * va + bL[i] * vb) * mm)
                    val mR = clip((aR[i] * va + bR[i] * vb) * mm)
                    if (abs(mL) > pkL) pkL = abs(mL)
                    if (abs(mR) > pkR) pkR = abs(mR)
                    var cL = 0f
                    var cR = 0f
                    if (c0) { cL += aL[i]; cR += aR[i] }
                    if (c1) { cL += bL[i]; cR += bR[i] }
                    if (!c0 && !c1) { cL = mL; cR = mR }
                    cL = cL * (1 - cm) + mL * cm
                    cR = cR * (1 - cm) + mR * cm
                    when {
                        ch == 4 -> {
                            out[i * 4] = mL; out[i * 4 + 1] = mR
                            out[i * 4 + 2] = clip(cL); out[i * 4 + 3] = clip(cR)
                        }
                        mode == 1 -> {
                            out[i * 2] = (mL + mR) * 0.5f
                            out[i * 2 + 1] = clip((cL + cR) * 0.5f)
                        }
                        else -> {
                            out[i * 2] = mL; out[i * 2 + 1] = mR
                        }
                    }
                }
                pv0 = v0; pv1 = v1; pm = mv
                peakL = pkL; peakR = pkR
                val w = track.write(out, 0, n * ch, AudioTrack.WRITE_BLOCKING)
                if (w < 0) break
                if (now > checkAt) {
                    checkAt = now + 500_000_000L
                    val u = track.underrunCount
                    if (u > lastUnder && bufFrames < track.bufferCapacityInFrames) {
                        bufFrames = minOf(track.bufferCapacityInFrames, bufFrames + BLOCK)
                        runCatching { track.setBufferSizeInFrames(bufFrames) }
                    }
                    lastUnder = u
                    latencyMs = bufFrames * 1000 / rate
                }
            }
        } finally {
            runCatching { track.stop() }
            track.release()
        }
    }

    private fun clip(x: Float): Float {
        val a = abs(x)
        if (a <= 0.9f) return x
        val y = 0.9f + 0.1f * tanh((a - 0.9f) / 0.1f)
        return if (x < 0) -y else y
    }

    companion object {
        const val BLOCK = 256
    }
}
