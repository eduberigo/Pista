package com.eduberigo.pista

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import java.io.File
import java.util.concurrent.LinkedBlockingDeque

/** Fila de processamento: análise de toda a biblioteca e separação em faixas. */
object Processor {
    val status = mutableStateOf("Parado")
    val detail = mutableStateOf("")
    val progress = mutableFloatStateOf(0f)
    val active = mutableStateOf(false)
    @Volatile var currentId: String? = null
        private set
    @Volatile var currentProgress = 0f
        private set

    private val priority = LinkedBlockingDeque<String>()
    private val failed = HashSet<String>()
    @Volatile private var stopFlag = false

    var autoSeparate: Boolean
        get() = Pista.prefs.getBoolean("autoSep", true)
        set(v) { Pista.prefs.edit().putBoolean("autoSep", v).apply() }
    var chargingOnly: Boolean
        get() = Pista.prefs.getBoolean("chargeOnly", false)
        set(v) { Pista.prefs.edit().putBoolean("chargeOnly", v).apply() }
    var whilePlaying: Boolean
        get() = Pista.prefs.getBoolean("sepPlaying", false)
        set(v) { Pista.prefs.edit().putBoolean("sepPlaying", v).apply() }

    fun start(ctx: Context) {
        stopFlag = false
        if (active.value || ProcessingService.running) return
        ctx.startForegroundService(Intent(ctx, ProcessingService::class.java))
    }

    fun stop() { stopFlag = true }

    fun request(ctx: Context, t: Track) {
        priority.remove(t.id)
        priority.addFirst(t.id)
        synchronized(failed) { failed.remove(t.id) }
        start(ctx)
    }

    fun isQueued(id: String) = priority.contains(id)

    private fun ui(s: String, d: String, p: Float) {
        currentProgress = p
        Pista.main.post { status.value = s; detail.value = d; progress.floatValue = p }
    }

    private fun charging(ctx: Context): Boolean {
        val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return true
        val st = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        return st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL
    }

    private fun waitReason(ctx: Context, priorityJob: Boolean): String? {
        if (!whilePlaying && Pista.engine.isPlaying) return "Pausado enquanto um deck toca"
        if (chargingOnly && !priorityJob && !charging(ctx)) return "Aguardando o carregador"
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.currentThermalStatus >= PowerManager.THERMAL_STATUS_SEVERE) return "Celular quente, esfriando"
        return null
    }

    /** Roda na thread do serviço até a fila acabar ou [stop]. */
    fun run(ctx: Context) {
        Pista.main.post { active.value = true }
        try {
            if (autoSeparate && !Separator.modelReady()) {
                try {
                    ui("Baixando o modelo de separação", "136 MB, uma única vez", 0f)
                    Separator.ensureModel({ p -> ui("Baixando o modelo de separação", "136 MB, uma única vez", p) }) { stopFlag }
                } catch (e: InterruptedException) {
                } catch (e: Throwable) {
                    ui("Falha ao baixar o modelo", e.message ?: "erro de rede", 0f)
                    Thread.sleep(4000)
                }
            }
            while (!stopFlag) {
                val job = next() ?: break
                val (t, sep, prio) = job
                if (sep) {
                    val w = waitReason(ctx, prio)
                    if (w != null) {
                        ui("Em espera", w, 0f)
                        Thread.sleep(3000)
                        continue
                    }
                }
                currentId = t.id
                try {
                    process(ctx, t, sep, prio)
                } catch (e: InterruptedException) {
                    // parado pelo usuário
                } catch (e: Throwable) {
                    synchronized(failed) { failed.add(t.id) }
                    priority.remove(t.id)
                    t.error = e.message ?: e.toString()
                    Pista.library.save()
                    Pista.library.bump()
                }
                currentId = null
            }
        } finally {
            Separator.release()
            currentId = null
            Pista.main.post {
                active.value = false
                status.value = if (stopFlag) "Parado" else "Tudo processado"
                detail.value = ""
                progress.floatValue = 0f
            }
        }
    }

    private fun isFailed(id: String) = synchronized(failed) { id in failed }

    private fun next(): Triple<Track, Boolean, Boolean>? {
        while (true) {
            val id = priority.peekFirst() ?: break
            val t = Pista.library.get(id)
            if (t == null || (t.analyzed && t.stems)) { priority.remove(id); continue }
            return Triple(t, !t.stems, true)
        }
        val all = Pista.library.snapshot()
        all.firstOrNull { !it.analyzed && !isFailed(it.id) }?.let { return Triple(it, false, false) }
        if (autoSeparate) all.firstOrNull { !it.stems && !isFailed(it.id) }?.let { return Triple(it, true, false) }
        return null
    }

    private fun process(ctx: Context, t: Track, sep: Boolean, prio: Boolean) {
        val title = t.name
        ui("Lendo", title, 0f)
        val tmp = File(ctx.cacheDir, "proc.pcm")
        try {
            val frames = Codec.decode(t.path, tmp) { stopFlag }
            val pcm = Codec.map(tmp)
            if (!t.analyzed) {
                val all = Pista.library.snapshot()
                val left = all.count { !it.analyzed }
                ui("Analisando BPM e tom — faltam $left", title, (all.size - left).toFloat() / maxOf(1, all.size))
                Work.analyze(t, pcm, frames, { stopFlag })
            }
            if (sep && !t.stems) {
                if (!Separator.modelReady()) {
                    ui("Baixando o modelo de separação", "136 MB, uma única vez", 0f)
                    Separator.ensureModel({ p -> ui("Baixando o modelo de separação", "136 MB, uma única vez", p) }) { stopFlag }
                }
                ui("Separando faixas", title, 0f)
                val chroma = Chroma()
                Separator.separate(
                    pcm, frames, Pista.library.stemsDir(t), chroma,
                    progress = { p -> ui("Separando faixas", title, p) },
                    hold = {
                        while (!stopFlag) {
                            val w = waitReason(ctx, prio) ?: break
                            ui("Em espera", w, currentProgress)
                            Thread.sleep(2000)
                        }
                    },
                    cancel = { stopFlag },
                )
                t.stems = true
                val k = chroma.key()
                if (k >= 0) { t.keyAuto = k; t.keyFromStems = true; if (!t.keyManual) t.key = k }
                t.error = null
                Pista.library.save()
                Pista.library.bump()
                for (d in Pista.engine.decks) {
                    if (d.loaded?.track?.id == t.id && d.loaded?.stems == null) DeckLoader.loadStems(ctx, d, t)
                }
            }
            priority.remove(t.id)
        } finally {
            tmp.delete()
        }
    }
}
