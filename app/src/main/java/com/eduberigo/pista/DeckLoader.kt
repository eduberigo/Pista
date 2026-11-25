package com.eduberigo.pista

import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import java.io.File
import java.nio.ShortBuffer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicIntegerArray

/** Carrega músicas nos decks: decodifica para cache, analisa se preciso, e acrescenta as faixas separadas. */
object DeckLoader {
    private val exec = Executors.newFixedThreadPool(2)
    private val gen = AtomicIntegerArray(2)

    private fun post(d: Deck, f: DeckUi.() -> Unit) { Pista.main.post { d.ui.f() } }

    fun load(ctx: Context, d: Deck, t: Track) {
        val g = gen.incrementAndGet(d.index)
        d.playing = false
        Pista.main.post {
            d.ui.track = t
            d.ui.status = "Carregando…"
            d.ui.wave = null
            d.ui.cover = null
            d.ui.stemsReady = false
            d.resetStems()
        }
        exec.execute {
            val stale = { gen.get(d.index) != g }
            try {
                val old = d.loaded
                val f = File(ctx.cacheDir, "deck${d.index}_${System.nanoTime()}_o.pcm")
                val frames = Codec.decode(t.path, f, stale)
                val pcm = Codec.map(f)
                if (!t.analyzed) {
                    post(d) { status = "Analisando BPM e tom…" }
                    Work.analyze(t, pcm, frames, stale)
                }
                if (stale()) { f.delete(); return@execute }
                val wave = Wave.load(Pista.library.waveFile(t))
                val cover = runCatching {
                    val m = MediaMetadataRetriever()
                    m.setDataSource(t.path)
                    val b = m.embeddedPicture
                    m.release()
                    b?.let {
                        val o = BitmapFactory.Options().apply { inSampleSize = 4 }
                        BitmapFactory.decodeByteArray(it, 0, it.size, o)
                    }
                }.getOrNull()
                d.attach(Loaded(t, frames, pcm, null, 0, listOf(f)))
                old?.files?.forEach { it.delete() }
                t.plays++
                t.lastPlayed = System.currentTimeMillis()
                Pista.library.save()
                post(d) { status = ""; this.wave = wave; this.cover = cover; edits++ }
                if (t.stems && Pista.library.stemsDir(t).isDirectory) {
                    loadStemsNow(ctx, d, t, g)
                } else if (Processor.autoSeparate) {
                    Processor.request(ctx, t)
                }
            } catch (e: InterruptedException) {
                // outra música foi carregada no lugar
            } catch (e: Throwable) {
                if (!stale()) post(d) { status = "Erro: ${e.message ?: e.javaClass.simpleName}" }
            }
        }
    }

    fun loadStems(ctx: Context, d: Deck, t: Track) {
        val g = gen.get(d.index)
        exec.execute {
            try { loadStemsNow(ctx, d, t, g) } catch (e: Throwable) {
                post(d) { status = "Erro nas faixas: ${e.message}" }
            }
        }
    }

    private fun loadStemsNow(ctx: Context, d: Deck, t: Track, g: Int) {
        val stale = { gen.get(d.index) != g }
        post(d) { status = "Carregando faixas separadas…" }
        val dir = Pista.library.stemsDir(t)
        val pool = Executors.newFixedThreadPool(3)
        val futures = (0 until 6).map { k ->
            pool.submit<Pair<File, ShortBuffer>> {
                val f = File(ctx.cacheDir, "deck${d.index}_${System.nanoTime()}_s$k.pcm")
                Codec.decode(File(dir, Separator.STEMS[k] + ".m4a").path, f, stale)
                f to Codec.map(f)
            }
        }
        val files = ArrayList<File>()
        val bufs = ArrayList<ShortBuffer>()
        try {
            for (fu in futures) { val (f, b) = fu.get(); files.add(f); bufs.add(b) }
        } finally {
            pool.shutdown()
        }
        val cur = d.loaded
        if (stale() || cur == null || cur.track.id != t.id) { files.forEach { it.delete() }; return }
        val arr = bufs.toTypedArray()
        val off = Align.offset(cur.orig, cur.frames, arr)
        if (d.addStems(arr, off, files)) post(d) { status = ""; stemsReady = true }
        else files.forEach { it.delete() }
    }
}
