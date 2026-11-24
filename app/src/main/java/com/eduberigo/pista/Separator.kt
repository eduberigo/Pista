package com.eduberigo.pista

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.ShortBuffer
import kotlin.math.max
import kotlin.math.min

/**
 * Separação em 6 faixas com HT-Demucs 6s (Meta, licença MIT), exportado em ONNX.
 * Roda inteiro no aparelho: blocos de 7,8 s com 25% de sobreposição.
 */
object Separator {
    const val SEG = 343980
    const val OVERLAP = SEG / 4
    const val STRIDE = SEG - OVERLAP

    /** Ordem usada no app. */
    val STEMS = arrayOf("vocals", "drums", "bass", "guitar", "piano", "other")
    val LABELS = arrayOf("VOZ", "BATERIA", "BAIXO", "GUITARRA", "PIANO", "OUTROS")
    /** Índice de cada faixa do app na saída do modelo (drums, bass, other, vocals, guitar, piano). */
    private val MODEL_IDX = intArrayOf(3, 0, 1, 4, 5, 2)

    private const val MODEL_URL =
        "https://huggingface.co/StemSplitio/htdemucs-6s-onnx/resolve/main/htdemucs_6s_fp16weights.onnx"
    private const val MODEL_MIN_BYTES = 100_000_000L

    val modelFile: File get() = File(Pista.baseDir, "modelo/htdemucs_6s_fp16weights.onnx")

    fun modelReady(): Boolean = modelFile.exists() && modelFile.length() > MODEL_MIN_BYTES

    private var env: OrtEnvironment? = null
    private var session: OrtSession? = null

    fun ensureModel(progress: (Float) -> Unit, cancel: () -> Boolean) {
        if (modelReady()) return
        val f = modelFile
        f.parentFile?.mkdirs()
        val part = File(f.path + ".part")
        var url = MODEL_URL
        var have = if (part.exists()) part.length() else 0L
        var c: HttpURLConnection? = null
        for (hop in 0 until 6) {
            c = URL(url).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 20000
            c.readTimeout = 60000
            if (have > 0) c.setRequestProperty("Range", "bytes=$have-")
            val code = c.responseCode
            if (code in 300..399) {
                val loc = c.getHeaderField("Location") ?: throw IOException("Redirecionamento sem destino")
                url = URL(URL(url), loc).toString()
                c.disconnect()
                continue
            }
            break
        }
        val conn = c ?: throw IOException("Sem conexão")
        val code = conn.responseCode
        if (code == 200) have = 0
        else if (code != 206) throw IOException("Download do modelo falhou (HTTP $code)")
        val total = have + max(0L, conn.contentLengthLong)
        conn.inputStream.use { ins ->
            FileOutputStream(part, have > 0).use { os ->
                val buf = ByteArray(1 shl 16)
                var got = have
                var last = 0L
                while (true) {
                    if (cancel()) throw InterruptedException()
                    val n = ins.read(buf)
                    if (n < 0) break
                    os.write(buf, 0, n)
                    got += n
                    if (got - last > 1_000_000) {
                        last = got
                        if (total > 0) progress(got.toFloat() / total)
                    }
                }
            }
        }
        conn.disconnect()
        if (part.length() < MODEL_MIN_BYTES) throw IOException("Modelo incompleto")
        if (!part.renameTo(f)) throw IOException("Não consegui salvar o modelo")
    }

    @Synchronized
    private fun session(): Pair<OrtEnvironment, OrtSession> {
        val s = session
        val e = env
        if (s != null && e != null) return e to s
        val ne = OrtEnvironment.getEnvironment()
        val o = OrtSession.SessionOptions()
        o.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        o.setIntraOpNumThreads(min(6, Runtime.getRuntime().availableProcessors()))
        val ns = ne.createSession(modelFile.path, o)
        env = ne
        session = ns
        return ne to ns
    }

    @Synchronized
    fun release() {
        runCatching { session?.close() }
        session = null
    }

    /**
     * Separa [frames] quadros de [pcm] e grava 6 arquivos .m4a em [outDir].
     * [hold] é chamado antes de cada bloco e pode dormir (pausa); [cancel] interrompe.
     */
    fun separate(
        pcm: ShortBuffer,
        frames: Int,
        outDir: File,
        chroma: Chroma?,
        progress: (Float) -> Unit,
        hold: () -> Unit,
        cancel: () -> Boolean,
    ) {
        val (e, s) = session()
        val tmp = File(outDir.parentFile, outDir.name + ".tmp")
        tmp.deleteRecursively()
        tmp.mkdirs()
        val writers = Array(6) { AacWriter(File(tmp, STEMS[it] + ".m4a"), STEM_BITRATE) }
        var ok = false
        try {
            val win = FloatArray(SEG) { i ->
                when {
                    i < OVERLAP -> (i + 1f) / (OVERLAP + 1f)
                    i >= SEG - OVERLAP -> (SEG - i).toFloat() / (OVERLAP + 1f)
                    else -> 1f
                }
            }
            val accL = Array(6) { FloatArray(OVERLAP) }
            val accR = Array(6) { FloatArray(OVERLAP) }
            val accW = FloatArray(OVERLAP)
            val outL = Array(6) { FloatArray(STRIDE) }
            val outR = Array(6) { FloatArray(STRIDE) }
            val input = ByteBuffer.allocateDirect(2 * SEG * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            val chunks = max(1, (frames + STRIDE - 1) / STRIDE)
            var dsAcc = 0f
            var dsN = 0
            for (c in 0 until chunks) {
                hold()
                if (cancel()) throw InterruptedException()
                val start = c * STRIDE
                val len = min(SEG, frames - start)
                input.clear()
                for (i in 0 until SEG) input.put(if (i < len) pcm.get((start + i) * 2) / 32768f else 0f)
                for (i in 0 until SEG) input.put(if (i < len) pcm.get((start + i) * 2 + 1) / 32768f else 0f)
                input.rewind()
                val finalN = min(STRIDE, frames - start)
                OnnxTensor.createTensor(e, input, longArrayOf(1, 2, SEG.toLong())).use { t ->
                    s.run(mapOf("mix" to t)).use { res ->
                        val ob = (res.get(0) as OnnxTensor).floatBuffer
                        for (k in 0 until 6) {
                            val m = MODEL_IDX[k]
                            val bl = (m * 2) * SEG
                            val br = (m * 2 + 1) * SEG
                            val oL = outL[k]; val oR = outR[k]; val aL = accL[k]; val aR = accR[k]
                            for (i in 0 until finalN) {
                                var l = ob.get(bl + i) * win[i]
                                var r = ob.get(br + i) * win[i]
                                var w = win[i]
                                if (i < OVERLAP && c > 0) { l += aL[i]; r += aR[i]; w += accW[i] }
                                val inv = 1f / max(w, 1e-8f)
                                oL[i] = l * inv
                                oR[i] = r * inv
                            }
                            for (i in 0 until OVERLAP) {
                                val j = STRIDE + i
                                aL[i] = if (j < len) ob.get(bl + j) * win[j] else 0f
                                aR[i] = if (j < len) ob.get(br + j) * win[j] else 0f
                            }
                        }
                        for (i in 0 until OVERLAP) {
                            val j = STRIDE + i
                            accW[i] = if (j < len) win[j] else 0f
                        }
                    }
                }
                for (k in 0 until 6) writers[k].write(outL[k], outR[k], 0, finalN)
                if (chroma != null) {
                    // tudo menos a bateria, em mono a 11025 Hz, para detectar o tom
                    for (i in 0 until finalN) {
                        var v = 0f
                        for (k in 0 until 6) if (k != 1) v += outL[k][i] + outR[k][i]
                        dsAcc += v
                        if (++dsN == 4) { chroma.push(dsAcc / 8f); dsAcc = 0f; dsN = 0 }
                    }
                }
                progress((c + 1f) / chunks)
            }
            for (w in writers) w.close()
            ok = true
        } finally {
            if (!ok) {
                for (w in writers) runCatching { w.close() }
                tmp.deleteRecursively()
            }
        }
        outDir.deleteRecursively()
        if (!tmp.renameTo(outDir)) throw IOException("Não consegui salvar as faixas")
    }
}
