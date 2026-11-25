package com.eduberigo.pista

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class Track(val id: String, var path: String) {
    var title = ""
    var artist = ""
    var album = ""
    var genre = ""
    var durationMs = 0L
    var size = 0L
    var addedAt = 0L
    var analyzed = false
    var stems = false
    var bpm = 0.0
    /** Tempo (s) de um downbeat ("1" do compasso). A grade é anchor + k * período. */
    var anchor = 0.0
    /** O "1" foi marcado à mão: ao afinar o BPM, só encaixa na batida mais próxima. */
    var anchorManual = false
    /** Valores da última análise completa, para poder voltar a eles. */
    var bpmAuto = 0.0
    var anchorAuto = 0.0
    var autoCue = 0.0
    var cue = -1.0
    var key = -1
    var keyFromStems = false
    var gain = 1f
    var hotcues = DoubleArray(8) { -1.0 }
    var plays = 0
    var lastPlayed = 0L
    var error: String? = null
    /** Não está na pasta atual (fica guardada com tudo para quando voltar). */
    var missing = false

    val name: String get() = title.ifBlank { File(path).nameWithoutExtension }
    val period: Double get() = if (bpm > 0) 60.0 / bpm else 0.5
    val startCue: Double get() = if (cue >= 0) cue else autoCue

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("path", path)
        put("title", title); put("artist", artist); put("album", album); put("genre", genre)
        put("dur", durationMs); put("size", size); put("added", addedAt)
        put("an", analyzed); put("st", stems)
        put("bpm", fin(bpm)); put("anchor", fin(anchor)); put("am", anchorManual); put("bpmA", fin(bpmAuto)); put("ancA", fin(anchorAuto)); put("acue", fin(autoCue)); put("cue", fin(cue))
        put("key", key); put("kst", keyFromStems); put("gain", fin(gain.toDouble()))
        put("hot", JSONArray().apply { hotcues.forEach { put(fin(it)) } })
        put("plays", plays); put("last", lastPlayed)
        error?.let { put("err", it) }
        put("miss", missing)
    }

    private fun fin(v: Double) = if (v.isNaN() || v.isInfinite()) 0.0 else v

    companion object {
        fun fromJson(o: JSONObject): Track = Track(o.getString("id"), o.getString("path")).apply {
            title = o.optString("title")
            artist = o.optString("artist")
            album = o.optString("album")
            genre = o.optString("genre")
            durationMs = o.optLong("dur")
            size = o.optLong("size")
            addedAt = o.optLong("added")
            analyzed = o.optBoolean("an")
            stems = o.optBoolean("st")
            bpm = o.optDouble("bpm", 0.0)
            anchor = o.optDouble("anchor", 0.0)
            anchorManual = o.optBoolean("am")
            bpmAuto = o.optDouble("bpmA", 0.0)
            anchorAuto = o.optDouble("ancA", 0.0)
            autoCue = o.optDouble("acue", 0.0)
            cue = o.optDouble("cue", -1.0)
            key = o.optInt("key", -1)
            keyFromStems = o.optBoolean("kst")
            gain = o.optDouble("gain", 1.0).toFloat()
            o.optJSONArray("hot")?.let { a ->
                for (i in 0 until minOf(8, a.length())) hotcues[i] = a.optDouble(i, -1.0)
            }
            plays = o.optInt("plays")
            lastPlayed = o.optLong("last")
            error = if (o.has("err")) o.optString("err") else null
            missing = o.optBoolean("miss")
        }
    }
}
