package com.eduberigo.pista

import android.content.Context
import android.os.storage.StorageManager
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import org.json.JSONObject
import java.io.File

/**
 * Backup = a pasta Pista inteira (biblioteca, análises, cues, grades e faixas separadas).
 * As músicas são reconhecidas por nome do arquivo + tamanho, não pela pasta: depois de restaurar,
 * basta apontar onde as músicas estão no aparelho novo que tudo é reaproveitado.
 */
object Backup {
    val running = mutableStateOf(false)
    val status = mutableStateOf("")
    val progress = mutableFloatStateOf(0f)
    val sizeInfo = mutableStateOf("")
    @Volatile private var stop = false

    private const val DIR_NAME = "Pista-backup"
    private val PREF_KEYS = listOf("autoSep", "chargeOnly", "sepPlaying", "outMode", "curve", "quantize", "range", "sort", "match", "bpmTol", "halfDouble", "keyMask", "folders")

    private fun post(s: String, p: Float) { Pista.main.post { status.value = s; progress.floatValue = p } }

    fun cancel() { stop = true }

    /** Pastas raiz de cada armazenamento (interno, cartão, pendrive/SSD USB). */
    fun volumes(ctx: Context): List<Pair<String, File>> {
        val sm = ctx.getSystemService(StorageManager::class.java) ?: return emptyList()
        return sm.storageVolumes.mapNotNull { v ->
            val d = v.directory ?: return@mapNotNull null
            (v.getDescription(ctx) ?: d.name) to d
        }
    }

    fun measure() {
        Thread {
            var all = 0L
            var stems = 0L
            Pista.baseDir.walkTopDown().filter { it.isFile }.forEach {
                all += it.length()
                if (it.parentFile?.name == "faixas") stems += it.length()
            }
            val gb = { b: Long -> "%.1f GB".format(b / 1e9) }
            Pista.main.post { sizeInfo.value = "Completo: ${gb(all)} · sem faixas separadas: ${gb(all - stems)}" }
        }.start()
    }

    private fun writePrefs() {
        val o = JSONObject()
        val all = Pista.prefs.all
        for (k in PREF_KEYS) all[k]?.let { o.put(k, it) }
        File(Pista.baseDir, "ajustes.json").writeText(o.toString())
    }

    private fun readPrefs() {
        val f = File(Pista.baseDir, "ajustes.json")
        if (!f.exists()) return
        val o = runCatching { JSONObject(f.readText()) }.getOrNull() ?: return
        val e = Pista.prefs.edit()
        for (k in o.keys()) {
            when (val v = o.get(k)) {
                is Boolean -> e.putBoolean(k, v)
                is Int -> e.putInt(k, v)
                is Double -> e.putFloat(k, v.toFloat())
                is String -> e.putString(k, v)
            }
        }
        e.apply()
        Pista.engine.loadPrefs()
    }

    /** Copia só o que mudou (arquivo novo ou de tamanho diferente). */
    private fun sync(src: File, dst: File, includeStems: Boolean, label: String) {
        val files = src.walkTopDown()
            .onEnter { includeStems || it.name != "faixas" }
            .filter { it.isFile && !it.name.endsWith(".part") && !it.name.endsWith(".tmp") }
            .toList()
        val total = files.sumOf { it.length() }.coerceAtLeast(1)
        var done = 0L
        var copied = 0
        for (f in files) {
            if (stop) throw InterruptedException()
            val rel = f.relativeTo(src).path
            val out = File(dst, rel)
            if (!(out.exists() && out.length() == f.length())) {
                out.parentFile?.mkdirs()
                val tmp = File(out.path + ".tmp")
                f.inputStream().use { i -> tmp.outputStream().use { o -> i.copyTo(o, 1 shl 20) } }
                out.delete()
                tmp.renameTo(out)
                copied++
            }
            done += f.length()
            post("$label: %.1f de %.1f GB".format(done / 1e9, total / 1e9), done.toFloat() / total)
        }
        post("$label concluído: $copied arquivos copiados, ${files.size - copied} já estavam lá", 1f)
    }

    fun backupTo(dest: File, includeStems: Boolean) {
        if (running.value) return
        stop = false
        running.value = true
        Thread {
            try {
                Pista.library.saveNow()
                writePrefs()
                val target = if (dest.name == DIR_NAME) dest else File(dest, DIR_NAME)
                sync(Pista.baseDir, target, includeStems, "Backup")
            } catch (e: InterruptedException) {
                post("Backup interrompido (o que já foi copiado fica)", 0f)
            } catch (e: Throwable) {
                post("Erro no backup: ${e.message}", 0f)
            } finally {
                Pista.main.post { running.value = false }
            }
        }.start()
    }

    /** Aceita a própria pasta Pista-backup ou uma pasta Pista copiada à mão. */
    fun findBackup(dir: File): File? = when {
        File(dir, "biblioteca.json").exists() -> dir
        File(dir, "$DIR_NAME/biblioteca.json").exists() -> File(dir, DIR_NAME)
        File(dir, "Pista/biblioteca.json").exists() -> File(dir, "Pista")
        else -> null
    }

    fun restoreFrom(dir: File, onDone: () -> Unit) {
        if (running.value) return
        val src = findBackup(dir)
        if (src == null) { status.value = "Não achei um backup do Pista nessa pasta (falta biblioteca.json)"; return }
        stop = false
        running.value = true
        Thread {
            try {
                if (src.canonicalPath != Pista.baseDir.canonicalPath) sync(src, Pista.baseDir, true, "Restauração")
                readPrefs()
                Pista.library.reloadIndex()
                post("Restaurado. Agora escolha a pasta onde estão as músicas neste aparelho.", 1f)
                Pista.main.post(onDone)
            } catch (e: InterruptedException) {
                post("Restauração interrompida", 0f)
            } catch (e: Throwable) {
                post("Erro na restauração: ${e.message}", 0f)
            } finally {
                Pista.main.post { running.value = false }
            }
        }.start()
    }
}
