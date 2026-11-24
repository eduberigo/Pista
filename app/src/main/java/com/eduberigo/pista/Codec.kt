package com.eduberigo.pista

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.ShortBuffer
import java.nio.channels.FileChannel

/** Bitrate de cada faixa separada (6 por música). */
const val STEM_BITRATE = 112_000

object Codec {
    /** Mapeia um arquivo PCM 16 bits estéreo intercalado (little-endian) sem ocupar a heap. */
    fun map(file: File): ShortBuffer {
        RandomAccessFile(file, "r").use { raf ->
            val ch = raf.channel
            val mb = ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size())
            mb.order(ByteOrder.LITTLE_ENDIAN)
            return mb.asShortBuffer()
        }
    }

    /** Decodifica qualquer áudio do aparelho para PCM 16 bits estéreo 44,1 kHz. Devolve o número de quadros. */
    fun decode(src: String, dst: File, cancel: () -> Boolean = { false }): Int {
        val ex = MediaExtractor()
        ex.setDataSource(src)
        var ti = -1
        for (i in 0 until ex.trackCount) {
            val m = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (m.startsWith("audio/")) { ti = i; break }
        }
        if (ti < 0) { ex.release(); throw IOException("Arquivo sem áudio") }
        ex.selectTrack(ti)
        val fmt = ex.getTrackFormat(ti)
        val mime = fmt.getString(MediaFormat.KEY_MIME)!!
        var chans = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var isFloat = false
        val codec = MediaCodec.createDecoderByType(mime)
        val os = BufferedOutputStream(FileOutputStream(dst), 1 shl 16)
        val sink = PcmSink(os)
        sink.setRate(fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE))
        try {
            codec.configure(fmt, null, null, 0)
            codec.start()
            val info = MediaCodec.BufferInfo()
            var inEos = false
            var outEos = false
            while (!outEos) {
                if (cancel()) throw InterruptedException()
                if (!inEos) {
                    val ii = codec.dequeueInputBuffer(5000)
                    if (ii >= 0) {
                        val ib = codec.getInputBuffer(ii)!!
                        val n = ex.readSampleData(ib, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inEos = true
                        } else {
                            codec.queueInputBuffer(ii, 0, n, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                val oi = codec.dequeueOutputBuffer(info, 5000)
                if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val of = codec.outputFormat
                    sink.setRate(of.getInteger(MediaFormat.KEY_SAMPLE_RATE))
                    chans = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    isFloat = of.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                        of.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                } else if (oi >= 0) {
                    val ob = codec.getOutputBuffer(oi)
                    if (ob != null && info.size > 0) {
                        ob.position(info.offset)
                        ob.limit(info.offset + info.size)
                        val b = ob.slice().order(ByteOrder.nativeOrder())
                        if (isFloat) {
                            val fb = b.asFloatBuffer()
                            val n = fb.remaining() / chans
                            for (i in 0 until n) {
                                val l = fb.get(i * chans)
                                val r = if (chans > 1) fb.get(i * chans + 1) else l
                                sink.push(l, r)
                            }
                        } else {
                            val sb = b.asShortBuffer()
                            val n = sb.remaining() / chans
                            for (i in 0 until n) {
                                val l = sb.get(i * chans) / 32768f
                                val r = if (chans > 1) sb.get(i * chans + 1) / 32768f else l
                                sink.push(l, r)
                            }
                        }
                    }
                    codec.releaseOutputBuffer(oi, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outEos = true
                }
            }
            sink.flush()
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
            ex.release()
            os.close()
        }
        return sink.frames
    }
}

/** Grava PCM 16 bits estéreo a 44,1 kHz, reamostrando (linear) se a fonte tiver outra taxa. */
class PcmSink(private val os: OutputStream) {
    private var inRate = SR
    private var step = 1.0
    private var t = 0.0
    private var pl = 0f
    private var pr = 0f
    private var has = false
    var frames = 0
        private set
    private val buf = ByteArray(1 shl 16)
    private var bp = 0

    fun setRate(r: Int) {
        if (r <= 0) return
        inRate = r
        step = r.toDouble() / SR
    }

    fun push(l: Float, r: Float) {
        if (inRate == SR) { write(l, r); return }
        if (!has) { pl = l; pr = r; has = true; t = 0.0; return }
        while (t < 1.0) {
            val f = t.toFloat()
            write(pl + (l - pl) * f, pr + (r - pr) * f)
            t += step
        }
        t -= 1.0
        pl = l; pr = r
    }

    private fun write(l: Float, r: Float) {
        put(l); put(r)
        frames++
        if (bp >= buf.size - 4) flush()
    }

    private fun put(v: Float) {
        val s = (v.coerceIn(-1f, 1f) * 32767f).toInt()
        buf[bp++] = s.toByte()
        buf[bp++] = (s shr 8).toByte()
    }

    fun flush() {
        if (bp > 0) os.write(buf, 0, bp)
        bp = 0
    }
}

/** Codifica PCM estéreo 44,1 kHz em AAC dentro de .m4a. */
class AacWriter(file: File, bitrate: Int) {
    private val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
    private val muxer = MediaMuxer(file.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private var track = -1
    private var started = false
    private val info = MediaCodec.BufferInfo()
    private var framesIn = 0L

    init {
        val f = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SR, 2)
        f.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        f.setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
        f.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        codec.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
    }

    private fun s(v: Float): Short = (v.coerceIn(-1f, 1f) * 32767f).toInt().toShort()

    fun write(l: FloatArray, r: FloatArray, off: Int, n: Int) {
        var i = 0
        while (i < n) {
            val ii = codec.dequeueInputBuffer(10000)
            if (ii < 0) { drain(false); continue }
            val ib = codec.getInputBuffer(ii)!!
            ib.clear()
            ib.order(ByteOrder.LITTLE_ENDIAN)
            val cap = minOf(ib.capacity() / 4, n - i)
            for (k in 0 until cap) {
                ib.putShort(s(l[off + i + k]))
                ib.putShort(s(r[off + i + k]))
            }
            val pts = framesIn * 1_000_000L / SR
            codec.queueInputBuffer(ii, 0, cap * 4, pts, 0)
            framesIn += cap
            i += cap
            drain(false)
        }
    }

    fun close() {
        try {
            var queued = false
            var guard = 0
            while (!queued && guard++ < 1000) {
                val ii = codec.dequeueInputBuffer(10000)
                if (ii >= 0) {
                    codec.queueInputBuffer(ii, 0, 0, framesIn * 1_000_000L / SR, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    queued = true
                } else drain(false)
            }
            drain(true)
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
            runCatching { if (started) muxer.stop() }
            runCatching { muxer.release() }
        }
    }

    private fun drain(eos: Boolean) {
        var guard = 0
        while (guard++ < 5000) {
            val oi = codec.dequeueOutputBuffer(info, if (eos) 10000 else 0)
            if (oi == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!eos) return else continue
            }
            if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                track = muxer.addTrack(codec.outputFormat)
                muxer.start()
                started = true
                continue
            }
            if (oi < 0) continue
            val ob = codec.getOutputBuffer(oi)
            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
            if (ob != null && info.size > 0 && started) {
                ob.position(info.offset)
                ob.limit(info.offset + info.size)
                muxer.writeSampleData(track, ob, info)
            }
            codec.releaseOutputBuffer(oi, false)
            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
        }
    }
}

/** Mede o atraso (em quadros) das faixas separadas em relação ao original (o codificador AAC adiciona alguns ms). */
object Align {
    fun offset(orig: ShortBuffer, frames: Int, stems: Array<ShortBuffer>): Int {
        val len = SR * 3
        val maxLag = 8192
        val center = frames / 3
        if (frames < center + len + maxLag + 8) return 0
        fun s(i: Int): Float {
            var v = 0f
            for (b in stems) if (i * 2 + 1 < b.limit()) v += b.get(i * 2) + b.get(i * 2 + 1)
            return v
        }
        fun o(i: Int): Float = (orig.get(i * 2) + orig.get(i * 2 + 1)).toFloat()
        val a = FloatArray(len / 4) { o(center + it * 4) }
        val bs = FloatArray((len + maxLag) / 4 + 1) { s(center + it * 4) }
        var best = 0
        var bv = -1e30
        for (lag in 0 until maxLag / 4) {
            var c = 0.0
            for (j in a.indices) c += a[j] * bs[j + lag]
            if (c > bv) { bv = c; best = lag }
        }
        var fb = best * 4
        var fv = -1e30
        for (lag in maxOf(0, best * 4 - 4)..(best * 4 + 4)) {
            var c = 0.0
            var j = 0
            while (j < len) {
                val i = center + j
                c += o(i).toDouble() * s(i + lag)
                j += 2
            }
            if (c > fv) { fv = c; fb = lag }
        }
        return fb
    }
}
