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

    /** Versão instalada, ex.: 0.1.12 (vem do número do build no GitHub). */
    val version: String by lazy {
        runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull() ?: "?"
    }

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
    fun analyze(t: Track, pcm: java.nio.ShortBuffer, frames: Int, cancel: () -> Boolean, hint: Double = 0.0) {
        val a = Analyzer.analyze(pcm, frames, cancel, hint)
        val lib = Pista.library
        lib.dataDir(t).mkdirs()
        lib.waveFile(t).writeBytes(a.wave)
        if (hint > 0 && t.anchorManual && a.bpm > 0) {
            // mantém o "1" que você marcou, encaixado na batida mais próxima da grade nova
            val per = 60.0 / a.bpm
            t.anchor = a.anchor + kotlin.math.round((t.anchor - a.anchor) / per) * per
        } else {
            t.anchor = a.anchor
            t.anchorManual = false
        }
        t.bpm = a.bpm
        if (hint <= 0) { t.bpmAuto = a.bpm; t.anchorAuto = a.anchor }
        t.autoCue = a.autoCue
        if (!t.keyFromStems) t.key = a.key
        t.gain = a.gain
        if (t.durationMs <= 0) t.durationMs = frames * 1000L / SR
        t.analyzed = true
        lib.save()
        lib.bump()
    }
}
