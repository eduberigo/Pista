package com.eduberigo.pista

import android.media.MediaMetadataRetriever
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import org.json.JSONArray
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors

class Library {
    private val tracks = ArrayList<Track>()
    private val byId = HashMap<String, Track>()
    /** Muda a cada alteração para a interface redesenhar. */
    val version = mutableIntStateOf(0)
    val scanStatus = mutableStateOf("")
    @Volatile private var loaded = false
    private val io = Executors.newSingleThreadExecutor()
    private val lock = Any()
    private var saveQueued = false

    val root: String get() = Pista.prefs.getString("root", "") ?: ""

    fun dataDir(t: Track) = File(Pista.baseDir, "dados/${t.id}")
    fun stemsDir(t: Track) = File(dataDir(t), "faixas")
    fun waveFile(t: Track) = File(dataDir(t), "onda.bin")
    private fun indexFile() = File(Pista.baseDir, "biblioteca.json")

    /** Só as músicas que existem agora na pasta escolhida. */
    fun snapshot(): List<Track> = synchronized(lock) { tracks.filter { !it.missing } }
    fun get(id: String): Track? = synchronized(lock) { byId[id] }
    fun size(): Int = synchronized(lock) { tracks.size }

    fun bump() { Pista.main.post { version.intValue++ } }
    private fun status(s: String) { Pista.main.post { scanStatus.value = s } }

    fun load() {
        if (loaded) return
        loaded = true
        io.execute {
            val f = indexFile()
            if (f.exists()) runCatching {
                val a = JSONArray(f.readText())
                synchronized(lock) {
                    for (i in 0 until a.length()) {
                        val t = Track.fromJson(a.getJSONObject(i))
                        tracks.add(t); byId[t.id] = t
                    }
                }
            }
            bump()
            if (root.isNotEmpty()) scanNow(File(root))
        }
    }

    fun setRoot(path: String) {
        Pista.prefs.edit().putString("root", path).apply()
        rescan()
    }

    fun rescan() {
        val r = root
        if (r.isEmpty()) return
        io.execute { scanNow(File(r)) }
    }

    fun save() {
        synchronized(lock) {
            if (saveQueued) return
            saveQueued = true
        }
        io.execute {
            Thread.sleep(300)
            synchronized(lock) { saveQueued = false }
            writeIndex()
        }
    }

    fun saveNow() { writeIndex() }

    /** Relê o biblioteca.json do disco (depois de restaurar um backup). */
    fun reloadIndex() {
        val f = indexFile()
        val a = runCatching { JSONArray(f.readText()) }.getOrNull() ?: return
        synchronized(lock) {
            tracks.clear(); byId.clear()
            for (i in 0 until a.length()) {
                val t = Track.fromJson(a.getJSONObject(i))
                t.missing = !File(t.path).exists()
                tracks.add(t); byId[t.id] = t
            }
        }
        bump()
    }

    private fun writeIndex() {
        val a = JSONArray()
        for (t in synchronized(lock) { ArrayList(tracks) }) a.put(t.toJson())
        val f = indexFile()
        f.parentFile?.mkdirs()
        val tmp = File(f.path + ".tmp")
        tmp.writeText(a.toString())
        tmp.renameTo(f)
    }

    private fun idFor(f: File): String {
        val md = MessageDigest.getInstance("SHA-1")
        val d = md.digest("${f.name}|${f.length()}".toByteArray())
        return d.take(8).joinToString("") { "%02x".format(it) }
    }

    private fun scanNow(dir: File) {
        if (!dir.isDirectory) { status("Pasta não encontrada: ${dir.path}"); return }
        status("Procurando músicas…")
        val files = dir.walkTopDown()
            .onEnter { !it.name.startsWith(".") && it.name != "Android" && it != Pista.baseDir }
            .filter { it.isFile && it.extension.lowercase() in EXT }
            .toList()
        val seen = HashSet<String>()
        val mmr = MediaMetadataRetriever()
        var n = 0
        for (f in files) {
            val id = idFor(f)
            seen.add(id)
            val ex = get(id)
            n++
            if (ex != null) {
                if (ex.path != f.path) ex.path = f.path
                ex.missing = false
                continue
            }
            val t = Track(id, f.path)
            t.size = f.length()
            t.addedAt = f.lastModified()
            runCatching {
                mmr.setDataSource(f.path)
                t.title = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE) ?: ""
                t.artist = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: ""
                t.album = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM) ?: ""
                t.genre = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE) ?: ""
                t.durationMs = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            }
            if (t.title.isBlank()) t.title = f.nameWithoutExtension
            synchronized(lock) { tracks.add(t); byId[id] = t }
            if (n % 25 == 0) { status("Lendo músicas… $n de ${files.size}"); bump() }
        }
        runCatching { mmr.release() }
        synchronized(lock) {
            // não apaga: guarda análise, cues e faixas separadas para quando a música reaparecer
            for (t in tracks) t.missing = t.id !in seen
        }
        writeIndex()
        status("")
        bump()
    }

    companion object {
        val EXT = setOf("m4a", "aac", "mp3", "flac", "wav", "ogg", "opus", "mp4")
    }
}
