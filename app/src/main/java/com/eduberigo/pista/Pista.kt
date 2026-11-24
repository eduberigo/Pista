package com.eduberigo.pista

import android.app.Application
import android.content.SharedPreferences
import android.os.Environment
import android.os.Handler
import android.os.Looper
import java.io.File

class PistaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Pista.init(this)
    }
}

object Pista {
    lateinit var app: Application
        private set
    lateinit var prefs: SharedPreferences
        private set
    lateinit var library: Library
        private set
    lateinit var engine: Engine
        private set
    val main = Handler(Looper.getMainLooper())
    private var started = false

    val baseDir: File get() = File(Environment.getExternalStorageDirectory(), "Pista")

    fun init(a: Application) {
        app = a
        prefs = a.getSharedPreferences("pista", 0)
        library = Library()
        engine = Engine()
        engine.loadPrefs()
    }

    /** Chamado quando a permissão de arquivos já foi dada. */
    fun ready() {
        if (started) return
        started = true
        baseDir.mkdirs()
        library.load()
        engine.start()
    }
}

/** Passos compartilhados entre a fila de processamento e o carregamento nos decks. */
object Work {
    fun analyze(t: Track, pcm: java.nio.ShortBuffer, frames: Int, cancel: () -> Boolean) {
        val a = Analyzer.analyze(pcm, frames, cancel)
        val lib = Pista.library
        lib.dataDir(t).mkdirs()
        lib.waveFile(t).writeBytes(a.wave)
        t.bpm = a.bpm
        t.anchor = a.anchor
        t.autoCue = a.autoCue
        if (!t.keyFromStems) t.key = a.key
        t.gain = a.gain
        if (t.durationMs <= 0) t.durationMs = frames * 1000L / SR
        t.analyzed = true
        lib.save()
        lib.bump()
    }
}
