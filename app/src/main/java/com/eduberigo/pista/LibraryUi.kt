package com.eduberigo.pista

import android.os.Environment
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File

private val SORTS = listOf("Título", "Artista", "BPM", "Tom", "Recentes", "Mais tocadas")


/** Referência para combinar: BPM efetivo e tom (já com mudança de tom do deck). */
private class Ref(val label: String, val track: Track, val bpm: Double, val key: Int)

private fun deckRef(i: Int): Ref? {
    val d = Pista.engine.decks[i]
    val t = d.track ?: return null
    val bpm = if (d.effBpm > 0) d.effBpm else t.bpm
    return Ref("Deck ${i + 1}", t, bpm, Keys.shifted(t.key, d.keyShift))
}

private class Row2(val t: Track, val info: String?, val good: Boolean)

@Composable
fun LibraryScreen(deck: Int, onClose: () -> Unit, onSettings: () -> Unit) {
    val ctx = LocalContext.current
    val lib = Pista.library
    val p = Pista.prefs
    val ver = lib.version.intValue
    val scan = lib.scanStatus.value
    var query by remember { mutableStateOf("") }
    var sort by remember { mutableIntStateOf(p.getInt("sort", 0)) }
    var target by remember { mutableIntStateOf(deck) }
    var menu by remember { mutableStateOf<Track?>(null) }
    var confirm by remember { mutableStateOf<Track?>(null) }
    // modo COMBINAR
    var match by remember { mutableStateOf(p.getBoolean("match", false)) }
    var refSrc by remember { mutableIntStateOf(1 - deck) } // 0/1 = deck, 2 = música escolhida
    var refTrack by remember { mutableStateOf<Track?>(null) }
    var tol by remember { mutableIntStateOf(p.getInt("bpmTol", 6)) } // %, 0 = qualquer
    var halfDouble by remember { mutableStateOf(p.getBoolean("halfDouble", true)) }
    var keyMask by remember { mutableIntStateOf(p.getInt("keyMask", Match.ALL)) } // 0 = qualquer tom

    val ref: Ref? = when (refSrc) {
        2 -> refTrack?.let { Ref("Escolhida", it, it.bpm, it.key) }
        else -> deckRef(refSrc)
    }

    val all = remember(ver) { lib.snapshot() }
    val list = remember(ver, query, sort, target, match, ref?.track?.id, ref?.bpm, ref?.key, tol, halfDouble, keyMask) {
        val q = query.trim().lowercase()
        var l = all.asSequence()
        if (q.isNotEmpty()) l = l.filter {
            it.title.lowercase().contains(q) || it.artist.lowercase().contains(q) ||
                it.album.lowercase().contains(q) || it.genre.lowercase().contains(q) ||
                File(it.path).name.lowercase().contains(q)
        }
        if (match && ref != null) {
            l.filter { it.id != ref.track.id }
                .mapNotNull { t ->
                    val bd = Match.bpmDiff(ref.bpm, t.bpm, halfDouble)
                    if (tol > 0 && (bd == null || kotlin.math.abs(bd) > tol)) return@mapNotNull null
                    val rel = Match.relation(ref.key, t.key)
                    if (keyMask != 0 && (rel == null || rel.kind and keyMask == 0)) return@mapNotNull null
                    val cost = kotlin.math.abs(bd ?: 50.0) + (rel?.cost ?: 4.0) * 2
                    val info = listOfNotNull(
                        bd?.let { "BPM %+.1f%%".format(it) },
                        rel?.label ?: if (t.key < 0) "tom desconhecido" else "outro tom",
                    ).joinToString(" · ")
                    Triple(t, cost, info)
                }
                .sortedBy { it.second }
                .map { Row2(it.first, it.third, true) }
                .toList()
        } else {
            val s = when (sort) {
                1 -> l.sortedWith(compareBy({ it.artist.lowercase() }, { it.name.lowercase() }))
                2 -> l.sortedWith(compareBy({ if (it.bpm > 0) it.bpm else 999.0 }, { it.name.lowercase() }))
                3 -> l.sortedWith(compareBy({ if (it.key >= 0) Keys.camelotNum(it.key) * 2 + (if (it.key < 12) 1 else 0) else 99 }, { it.bpm }))
                4 -> l.sortedByDescending { it.addedAt }
                5 -> l.sortedWith(compareByDescending<Track> { it.plays }.thenByDescending { it.lastPlayed })
                else -> l.sortedBy { it.name.lowercase() }
            }
            s.map { Row2(it, null, false) }.toList()
        }
    }

    fun load(t: Track) {
        val d = Pista.engine.decks[target]
        if (d.playing) { confirm = t; return }
        DeckLoader.load(ctx, d, t)
        onClose()
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().background(C.panel2).padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Pill("‹ Voltar", fontSize = 12) { onClose() }
            Text("Carregar no deck", color = C.dim, fontSize = 12.sp, modifier = Modifier.weight(1f))
            Pill("1", on = target == 0, color = C.deck1) { target = 0 }
            Pill("2", on = target == 1, color = C.deck2) { target = 1 }
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Buscar título, artista, álbum, gênero") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        )
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Pill("COMBINAR TOM + BPM", on = match, color = C.green, fontSize = 11) {
                match = !match; p.edit().putBoolean("match", match).apply()
            }
            if (!match) SORTS.forEachIndexed { i, s ->
                Pill(s, on = sort == i, fontSize = 11) { sort = i; p.edit().putInt("sort", i).apply() }
            }
        }
        if (match) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)
                    .clip(RoundedCornerShape(10.dp)).background(C.panel).padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Referência", color = C.dim, fontSize = 11.sp, modifier = Modifier.width(72.dp))
                    Pill("Deck 1", on = refSrc == 0, color = C.deck1, fontSize = 11) { refSrc = 0 }
                    Pill("Deck 2", on = refSrc == 1, color = C.deck2, fontSize = 11) { refSrc = 1 }
                    Pill("Escolhida", on = refSrc == 2, enabled = refTrack != null, fontSize = 11) { refSrc = 2 }
                }
                Text(
                    if (ref == null) "Sem referência: carregue uma música no deck ou segure uma música da lista e escolha \"usar como referência\"."
                    else "${ref.track.name} · ${fmtBpm(ref.bpm)} BPM · " +
                        (if (ref.key >= 0) "${Keys.name(ref.key)} (${Keys.camelot(ref.key)})" else "tom desconhecido"),
                    color = if (ref == null) C.yellow else C.txt, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("BPM", color = C.dim, fontSize = 11.sp, modifier = Modifier.width(40.dp))
                    for (v in listOf(2, 4, 6, 10, 0)) {
                        Pill(if (v == 0) "qualquer" else "±$v%", on = tol == v, fontSize = 11) {
                            tol = v; p.edit().putInt("bpmTol", v).apply()
                        }
                    }
                    Pill("½ e 2×", on = halfDouble, fontSize = 11) {
                        halfDouble = !halfDouble; p.edit().putBoolean("halfDouble", halfDouble).apply()
                    }
                }
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("TOM", color = C.dim, fontSize = 11.sp, modifier = Modifier.width(40.dp))
                    val opts = listOf(Match.SAME to "mesmo", Match.REL to "relativa", Match.FIFTH to "4ª/5ª", Match.CHROM to "±1 semitom")
                    for ((bit, name) in opts) {
                        Pill(name, on = keyMask and bit != 0, fontSize = 11) {
                            keyMask = keyMask xor bit; p.edit().putInt("keyMask", keyMask).apply()
                        }
                    }
                    Pill("qualquer", on = keyMask == 0, fontSize = 11) {
                        keyMask = if (keyMask == 0) Match.ALL else 0; p.edit().putInt("keyMask", keyMask).apply()
                    }
                }
            }
        }
        val analyzed = all.count { it.analyzed }
        val stems = all.count { it.stems }
        Text(
            if (scan.isNotEmpty()) scan
            else if (match) "${list.size} combinações, das mais próximas para as mais distantes"
            else "${all.size} músicas · $analyzed analisadas · $stems separadas · mostrando ${list.size}",
            color = C.dim, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
        if (lib.root.isEmpty()) {
            Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Escolha a pasta onde estão suas músicas.", color = C.txt)
                Spacer(Modifier.height(12.dp))
                Pill("ABRIR AJUSTES") { onSettings() }
            }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            items(list, key = { it.t.id }) { r ->
                TrackRow(r.t, if (match) ref?.key else null, r.info, onTap = { load(r.t) }, onLong = { menu = r.t })
                HorizontalDivider(color = C.line, thickness = 0.5.dp)
            }
        }
    }

    menu?.let { t ->
        AlertDialog(
            onDismissRequest = { menu = null },
            title = { Text(t.name, maxLines = 2) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(t.artist, color = C.dim)
                    if (t.error != null) Text("Último erro: ${t.error}", color = C.red, fontSize = 12.sp)
                    Pill("Carregar no deck 1", Modifier.fillMaxWidth()) { menu = null; target = 0; load(t) }
                    Pill("Carregar no deck 2", Modifier.fillMaxWidth()) { menu = null; target = 1; load(t) }
                    Pill("Usar como referência para combinar", Modifier.fillMaxWidth()) {
                        menu = null; refTrack = t; refSrc = 2; match = true; p.edit().putBoolean("match", true).apply()
                    }
                    if (!t.stems) Pill("Separar faixas agora", Modifier.fillMaxWidth()) { menu = null; Processor.request(ctx, t) }
                    Pill("Refazer análise (BPM, tom, grade)", Modifier.fillMaxWidth()) {
                        menu = null
                        t.analyzed = false
                        t.keyFromStems = false
                        lib.save(); lib.bump()
                        Processor.request(ctx, t)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { menu = null }) { Text("Fechar") } },
        )
    }

    confirm?.let { t ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Deck ${target + 1} está tocando") },
            text = { Text("Trocar a música agora vai parar o deck. Continuar?") },
            confirmButton = {
                TextButton(onClick = {
                    confirm = null
                    DeckLoader.load(ctx, Pista.engine.decks[target], t)
                    onClose()
                }) { Text("Trocar") }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun TrackRow(t: Track, refKey: Int?, info: String?, onTap: () -> Unit, onLong: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .pointerInput(t.id) { detectTapGestures(onTap = { onTap() }, onLongPress = { onLong() }) }
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(t.name, color = C.txt, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOf(t.artist, fmtTime(t.durationMs / 1000.0)).filter { it.isNotBlank() }.joinToString(" · "),
                color = C.dim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (info != null) Text(info, color = C.green, fontSize = 12.sp, maxLines = 1)
        }
        Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(start = 8.dp)) {
            Text(if (t.bpm > 0) "%.1f".format(t.bpm) else "—", color = C.txt, fontSize = 14.sp)
            if (t.key >= 0) {
                val good = refKey == null || refKey < 0 || Match.relation(refKey, t.key) != null
                Box(
                    Modifier.clip(RoundedCornerShape(4.dp)).background(Color(Keys.color(t.key)).copy(alpha = if (good) 1f else 0.45f))
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                ) { Text(Keys.camelot(t.key), color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
            }
        }
        Spacer(Modifier.width(8.dp))
        val (sym, col) = when {
            t.error != null && !t.stems -> "!" to C.red
            t.stems -> "6F" to C.green
            t.analyzed -> "A" to C.dim
            else -> "·" to C.dim
        }
        Text(sym, color = col, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(22.dp))
    }
}

// ---------------- AJUSTES ----------------

@Composable
fun SettingsScreen(onClose: () -> Unit, onPickFolder: () -> Unit) {
    val ctx = LocalContext.current
    val lib = Pista.library
    val e = Pista.engine
    val ver = lib.version.intValue
    val all = remember(ver) { lib.snapshot() }
    var autoSep by remember { mutableStateOf(Processor.autoSeparate) }
    var charge by remember { mutableStateOf(Processor.chargingOnly) }
    var whilePlay by remember { mutableStateOf(Processor.whilePlaying) }
    var outMode by remember { mutableIntStateOf(e.outMode) }
    var curve by remember { mutableIntStateOf(e.curve) }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().background(C.panel2).padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Pill("‹ Voltar", fontSize = 12) { onClose() }
            Text("  Ajustes", color = C.txt, fontSize = 16.sp)
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Head("BIBLIOTECA")
            Text(if (lib.root.isEmpty()) "Nenhuma pasta escolhida" else lib.root, color = C.txt, fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("ESCOLHER PASTA", Modifier.weight(1f), fontSize = 11) { onPickFolder() }
                Pill("ATUALIZAR", Modifier.weight(1f), fontSize = 11, enabled = lib.root.isNotEmpty()) { lib.rescan() }
            }
            val sc = lib.scanStatus.value
            Text(
                if (sc.isNotEmpty()) sc else "${all.size} músicas · ${all.count { it.analyzed }} analisadas · ${all.count { it.stems }} com faixas separadas",
                color = C.dim, fontSize = 12.sp,
            )

            Head("PROCESSAMENTO")
            val active = Processor.active.value
            Text(Processor.status.value, color = C.txt, fontSize = 14.sp)
            val det = Processor.detail.value
            if (det.isNotEmpty()) Text(det, color = C.dim, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (active) {
                val p = Processor.progress.floatValue
                LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill(if (active) "PARAR" else "PROCESSAR BIBLIOTECA", Modifier.weight(1f), on = active, color = C.red, fontSize = 11) {
                    if (active) Processor.stop() else Processor.start(ctx)
                }
            }
            Toggle("Separar faixas de toda a biblioteca", "Desligado: só analisa BPM/tom; separa apenas o que você pedir ou carregar.", autoSep) {
                autoSep = it; Processor.autoSeparate = it
            }
            Toggle("Separar só com o carregador ligado", "A separação esquenta e gasta bateria. Pedidos manuais furam a fila.", charge) {
                charge = it; Processor.chargingOnly = it
            }
            Toggle("Continuar separando enquanto toca", "Pode causar falhas no áudio durante a mixagem.", whilePlay) {
                whilePlay = it; Processor.whilePlaying = it
            }
            Text(
                if (Separator.modelReady()) "Modelo de separação: instalado (HT-Demucs 6 faixas)"
                else "Modelo de separação: será baixado na primeira separação (136 MB, uma vez)",
                color = C.dim, fontSize = 12.sp,
            )

            Head("SAÍDA DE ÁUDIO")
            val outs = listOf("Estéreo", "Dividida (cabo Y)", "USB 4 canais")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                outs.forEachIndexed { i, s ->
                    Pill(s, Modifier.weight(1f), on = outMode == i, fontSize = 10) { outMode = i; e.changeOutMode(i) }
                }
            }
            Text(
                when (outMode) {
                    1 -> "Mix em mono no lado esquerdo (caixa) e fone no direito. Use um cabo divisor P2 estéreo → 2× mono. Ative FONE no deck que quer pré-escutar."
                    2 -> "Interface USB com 4 saídas: canais 1-2 = mix, 3-4 = fone."
                    else -> "Saída normal do celular, Bluetooth ou fone."
                },
                color = C.dim, fontSize = 12.sp,
            )
            val os = e.outStatus.value
            if (os.isNotEmpty()) Text(os, color = C.yellow, fontSize = 12.sp)

            Head("CROSSFADER")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Pill("Suave", Modifier.weight(1f), on = curve == 0, fontSize = 11) { curve = 0; e.curve = 0; Pista.prefs.edit().putInt("curve", 0).apply() }
                Pill("Corte rápido", Modifier.weight(1f), on = curve == 1, fontSize = 11) { curve = 1; e.curve = 1; Pista.prefs.edit().putInt("curve", 1).apply() }
            }

            Head("SOBRE")
            Text(
                "Pista — separação de faixas no aparelho com HT-Demucs 6s (Meta, licença MIT). " +
                    "Arquivos do app: ${Pista.baseDir.path}",
                color = C.dim, fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun Head(s: String) {
    Text(s, color = C.accent, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun Toggle(title: String, sub: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text(title, color = C.txt, fontSize = 14.sp)
            Text(sub, color = C.dim, fontSize = 11.sp)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
fun FolderPicker(onDone: () -> Unit) {
    val start = Pista.library.root.ifEmpty { Environment.getExternalStorageDirectory().path }
    var dir by remember { mutableStateOf(File(start).takeIf { it.isDirectory } ?: Environment.getExternalStorageDirectory()) }
    val subs = remember(dir) {
        (dir.listFiles() ?: emptyArray()).filter { it.isDirectory && !it.name.startsWith(".") }.sortedBy { it.name.lowercase() }
    }
    val audioHere = remember(dir) {
        (dir.listFiles() ?: emptyArray()).count { it.isFile && it.extension.lowercase() in Library.EXT }
    }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().background(C.panel2).padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Pill("‹ Voltar", fontSize = 12) { onDone() }
            Pill("↑ Acima", fontSize = 12, enabled = dir.parentFile?.canRead() == true) {
                dir.parentFile?.let { dir = it }
            }
        }
        Text(dir.path, color = C.txt, fontSize = 13.sp, modifier = Modifier.padding(10.dp))
        Text("$audioHere músicas nesta pasta (as subpastas também entram)", color = C.dim, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 10.dp))
        Pill("USAR ESTA PASTA", Modifier.fillMaxWidth().padding(10.dp), on = true) {
            Pista.library.setRoot(dir.path)
            onDone()
        }
        LazyColumn(Modifier.weight(1f)) {
            items(subs, key = { it.path }) { f ->
                Text(
                    "📁  " + f.name,
                    color = C.txt, fontSize = 15.sp,
                    modifier = Modifier.fillMaxWidth().clickable { dir = f }.padding(horizontal = 14.dp, vertical = 12.dp),
                )
                HorizontalDivider(color = C.line, thickness = 0.5.dp)
            }
        }
    }
}
