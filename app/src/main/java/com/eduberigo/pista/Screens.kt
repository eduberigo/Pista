package com.eduberigo.pista

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.max

private val decks get() = Pista.engine.decks

@Composable
fun MainScreen(openLib: (Int) -> Unit, openSettings: () -> Unit) {
    var tab by remember { mutableIntStateOf(0) }
    var tempoDeck by remember { mutableIntStateOf(-1) }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().background(Color.Black).padding(top = 4.dp, bottom = 6.dp)) {
                DeckHeader(decks[0], true, Modifier.weight(1f)) { openLib(0) }
                DeckHeader(decks[1], false, Modifier.weight(1f)) { openLib(1) }
            }
            TabBar(tab, { tab = it }, openSettings)
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (tab) {
                    0 -> MixerTab()
                    1 -> StemsTab()
                    2 -> WavesTab()
                    else -> PadsTab()
                }
            }
            Transport { tempoDeck = it }
        }
        if (tempoDeck >= 0) TempoPanel(decks[tempoDeck]) { tempoDeck = -1 }
    }
}

@Composable
private fun DeckHeader(d: Deck, left: Boolean, modifier: Modifier, onOpen: () -> Unit) {
    val t = d.ui.track
    d.ui.edits
    Column(modifier.padding(horizontal = 6.dp)) {
        Row(
            Modifier.fillMaxWidth().clickable { onOpen() },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (left) Cover(d.ui.cover, d.index)
            Column(
                Modifier.weight(1f).padding(horizontal = 6.dp),
                horizontalAlignment = if (left) Alignment.Start else Alignment.End,
            ) {
                Text(
                    t?.artist?.ifBlank { null } ?: "Deck ${d.index + 1}",
                    color = C.dim, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    t?.name ?: "Toque para escolher",
                    color = C.txt, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                HeaderLive(d, left)
            }
            if (!left) Cover(d.ui.cover, d.index)
        }
        HeaderStatus(d)
        Overview(d, Modifier.padding(top = 4.dp).fillMaxWidth().height(36.dp))
    }
}

@Composable
private fun HeaderStatus(d: Deck) {
    val t = d.ui.track
    val st = d.ui.status
    val ready = d.ui.stemsReady
    val cur = Processor.currentId
    val p = Processor.progress.floatValue
    val s = Processor.status.value
    val det = Processor.detail.value
    Pista.library.version.intValue
    val text = when {
        st.isNotEmpty() -> st
        t == null || ready || t.stems -> ""
        cur == t.id && s == "Em espera" -> "Faixas em espera: $det"
        cur == t.id -> "$s ${(p * 100).toInt()}%"
        s.startsWith("Baixando") -> "Baixando o modelo ${(p * 100).toInt()}%"
        Processor.isQueued(t.id) -> "Faixas: na fila (${s.lowercase()})"
        else -> ""
    }
    if (text.isNotEmpty()) Text(text, color = C.yellow, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun Cover(b: Bitmap?, index: Int) {
    val img = remember(b) { b?.asImageBitmap() }
    Box(
        Modifier.size(50.dp).clip(RoundedCornerShape(6.dp)).background(C.panel2),
        contentAlignment = Alignment.Center,
    ) {
        if (img != null) Image(img, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Text("${index + 1}", color = C.deck(index), fontSize = 20.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun HeaderLive(d: Deck, left: Boolean) {
    Tick.frame.longValue
    val l = d.loaded
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (l == null) return@Row
        val rem = (l.frames - d.pos) / SR
        val k = Keys.shifted(l.track.key, d.keyShift)
        val time = @Composable { Text("-" + fmtTime(rem), color = C.txt, fontSize = 18.sp) }
        val key = @Composable {
            Text(
                if (k >= 0) "${Keys.name(k)} ${Keys.camelot(k)}" else "",
                color = Color(Keys.color(k)), fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        }
        if (left) { time(); key() } else { key(); time() }
    }
}

@Composable
private fun TabBar(tab: Int, onTab: (Int) -> Unit, openSettings: () -> Unit) {
    val names = listOf("MIXER", "FAIXAS", "ONDAS", "PADS")
    Row(
        Modifier.fillMaxWidth().background(C.panel2).padding(horizontal = 6.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        names.forEachIndexed { i, n ->
            Pill(n, Modifier.weight(1f), on = tab == i, fontSize = 12) { onTab(i) }
        }
        Pill("⚙", fontSize = 14) { openSettings() }
    }
}

@Composable
private fun DKnob(
    label: String,
    initial: Float,
    set: (Float) -> Unit,
    min: Float = 0f,
    max: Float = 1f,
    def: Float = 0.5f,
    bipolar: Boolean = true,
    size: Dp = 44.dp,
    color: Color = C.accent,
    key: Any? = null,
) {
    var v by remember(key) { mutableFloatStateOf(initial) }
    Knob(v, { v = it; set(it) }, label, min = min, max = max, default = def, bipolar = bipolar, size = size, color = color)
}

// ---------------- MIXER ----------------

@Composable
private fun MixerTab() {
    val e = Pista.engine
    Row(Modifier.fillMaxSize().padding(horizontal = 4.dp, vertical = 6.dp)) {
        MixColumn(decks[0], true, Modifier.weight(1f))
        Column(
            Modifier.width(70.dp).fillMaxHeight(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Meters(Modifier.weight(1f).width(30.dp).padding(vertical = 8.dp))
            DKnob("MASTER", e.master, { e.master = it }, def = 0.85f, bipolar = false, size = 36.dp)
            DKnob("FONE↔MIX", e.cueMix, { e.cueMix = it }, def = 0f, bipolar = false, size = 36.dp)
        }
        MixColumn(decks[1], false, Modifier.weight(1f))
    }
}

@Composable
private fun MixColumn(d: Deck, left: Boolean, modifier: Modifier) {
    var vol by remember { mutableFloatStateOf(d.vol) }
    var cue by remember { mutableStateOf(d.cueHead) }
    val col = C.deck(d.index)
    val fader: @Composable () -> Unit = {
        VFader(vol, { vol = it; d.vol = it }, Modifier.width(54.dp).fillMaxHeight(), col)
    }
    Row(modifier.fillMaxHeight()) {
        if (left) fader()
        Column(
            Modifier.weight(1f).fillMaxHeight(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceEvenly,
        ) {
            DKnob("FILTRO", d.filter, { d.filter = it }, -1f, 1f, 0f, color = col)
            DKnob("AGUDO", d.eqHi, { d.eqHi = it })
            DKnob("MÉDIO", d.eqMid, { d.eqMid = it })
            DKnob("GRAVE", d.eqLow, { d.eqLow = it })
            DKnob("GANHO", d.trim, { d.trim = it }, size = 36.dp)
            Pill("FONE", on = cue, color = C.yellow, fontSize = 11) { cue = !cue; d.cueHead = cue }
        }
        if (!left) fader()
    }
}

@Composable
private fun Meters(modifier: Modifier) {
    val hold = remember { FloatArray(2) }
    Canvas(modifier) {
        Tick.frame.longValue
        val segs = 26
        val gap = 2f
        val bw = (size.width - gap) / 2
        val sh = (size.height - gap * (segs - 1)) / segs
        for (ch in 0 until 2) {
            val d = decks[ch]
            val v = d.peak * d.vol
            hold[ch] = max(v, hold[ch] * 0.92f)
            val lit = (hold[ch].coerceIn(0f, 1.2f) / 1.0f * segs).toInt()
            for (s in 0 until segs) {
                val y = size.height - (s + 1) * (sh + gap) + gap
                val c = when {
                    s >= segs - 3 -> C.red
                    s >= segs - 8 -> C.yellow
                    else -> C.green
                }
                drawRect(if (s < lit) c else Color(0xFF2A2C31), Offset(ch * (bw + gap), y), Size(bw, sh))
            }
        }
    }
}

// ---------------- FAIXAS (separação) ----------------

@Composable
private fun StemsTab() {
    Row(Modifier.fillMaxSize().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StemColumn(decks[0], Modifier.weight(1f))
        StemColumn(decks[1], Modifier.weight(1f))
    }
}

@Composable
private fun StemColumn(d: Deck, modifier: Modifier) {
    val ready = d.ui.stemsReady
    val mask = d.ui.muteMask
    var resetKey by remember { mutableIntStateOf(0) }
    val col = C.deck(d.index)
    val key = resetKey to d.ui.track?.id
    Column(
        modifier.fillMaxHeight().clip(RoundedCornerShape(12.dp)).background(C.panel).padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("DECK ${d.index + 1}", color = col, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        // volume gradual de cada grupo (centro = normal, esquerda = some, direita = reforça)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            DKnob("VOZ", d.groupKnob[0], { d.groupKnob[0] = it }, key = key, color = col, size = 46.dp)
            DKnob("BATERIA", d.groupKnob[1], { d.groupKnob[1] = it }, key = key, color = col, size = 46.dp)
            DKnob("HARMONIA", d.groupKnob[2], { d.groupKnob[2] = it }, key = key, color = col, size = 46.dp)
        }
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Pill("VOZ", Modifier.weight(1f), on = d.groupMuted(mask, 0), color = C.red, fontSize = 10) { d.toggleGroup(0) }
            Pill("BAT", Modifier.weight(1f), on = d.groupMuted(mask, 1), color = C.red, fontSize = 10) { d.toggleGroup(1) }
            Pill("HARM", Modifier.weight(1f), on = d.groupMuted(mask, 2), color = C.red, fontSize = 10) { d.toggleGroup(2) }
        }
        Text("knob = gradual · botão aceso = cortado", color = C.dim, fontSize = 9.sp)
        if (ready) {
            Text("FAIXA POR FAIXA", color = C.dim, fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
            for (row in 0 until 3) {
                Row(
                    Modifier.fillMaxWidth().weight(1f),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    for (c in 0 until 2) {
                        val k = row * 2 + c
                        DKnob(Separator.LABELS[k], d.stemKnob[k], { d.setStemKnob(k, it) }, key = key, color = col, size = 40.dp)
                    }
                }
            }
            Pill("ZERAR", Modifier.fillMaxWidth(), fontSize = 11) { d.resetStems(); resetKey++ }
        } else {
            StemsPending(d, Modifier.weight(1f))
        }
    }
}

@Composable
private fun StemsPending(d: Deck, modifier: Modifier) {
    val ctx = LocalContext.current
    val t = d.ui.track
    Pista.library.version.intValue
    val cur = Processor.currentId
    val p = Processor.progress.floatValue
    val st = Processor.status.value
    val det = Processor.detail.value
    Column(
        modifier.fillMaxWidth().padding(top = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        when {
            t == null -> Text("Carregue uma música", color = C.dim, fontSize = 13.sp)
            t.stems -> Text("Carregando faixas…", color = C.dim, fontSize = 13.sp)
            cur == t.id -> {
                Text(st, color = C.txt, fontSize = 13.sp)
                if (st == "Em espera") Text(det, color = C.yellow, fontSize = 11.sp)
                LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
                Text("${(p * 100).toInt()}%", color = C.dim, fontSize = 12.sp)
            }
            else -> {
                Text(
                    if (Processor.isQueued(t.id)) "Na fila de separação" else "Faixas ainda não separadas",
                    color = C.dim, fontSize = 13.sp,
                )
                Pill("SEPARAR AGORA", Modifier.fillMaxWidth(), fontSize = 11) { Processor.request(ctx, t) }
                Text("Leva alguns minutos por música. Os knobs acima já ficam valendo e atuam assim que as faixas ficarem prontas.", color = C.dim, fontSize = 10.sp)
            }
        }
    }
}

// ---------------- ONDAS ----------------

@Composable
private fun WavesTab() {
    Row(Modifier.fillMaxSize()) {
        WaveColumn(decks[0], Modifier.weight(1f))
        Box(Modifier.width(2.dp).fillMaxHeight().background(C.bg))
        WaveColumn(decks[1], Modifier.weight(1f))
    }
}

@Composable
private fun WaveColumn(d: Deck, modifier: Modifier) {
    Column(modifier.fillMaxHeight()) {
        DeckWave(d, Modifier.weight(1f).fillMaxWidth())
        LoopBar(d)
    }
}

fun loopLabel(b: Double): String = when {
    b >= 1 && kotlin.math.abs(b - kotlin.math.round(b)) < 0.05 -> "${kotlin.math.round(b).toInt()}"
    b >= 1 -> "%.1f".format(b)
    else -> "1/${kotlin.math.round(1 / b).toInt()}"
}

@Composable
private fun LoopBar(d: Deck) {
    Tick.frame.longValue
    Row(
        Modifier.fillMaxWidth().background(C.panel2).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Pill("‹", Modifier.weight(1f)) { d.loopStep(-1) }
        Pill("⟳ " + loopLabel(d.loopBeats), Modifier.weight(1.6f), on = d.loopOn, color = C.green) { d.toggleLoop() }
        Pill("›", Modifier.weight(1f)) { d.loopStep(1) }
        Pill("1", Modifier.weight(1f), color = C.yellow, onLong = { d.downbeatHere(exact = true) }) { d.downbeatHere() }
    }
}

// ---------------- PADS ----------------

@Composable
private fun PadsTab() {
    Row(Modifier.fillMaxSize().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PadColumn(decks[0], Modifier.weight(1f))
        PadColumn(decks[1], Modifier.weight(1f))
    }
}

@Composable
private fun ColumnScope.Section(s: String) {
    Text(s, color = C.dim, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
}

@Composable
private fun ColumnScope.LoopPads(d: Deck) {
    Tick.frame.longValue
    Section("LOOP (em batidas · toque de novo para sair)")
    for (row in listOf(listOf(1.0, 2.0, 4.0, 8.0), listOf(12.0, 16.0, 32.0, 64.0))) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (b in row) {
                Pill(loopLabel(b), Modifier.weight(1f), on = d.loopOn && kotlin.math.abs(d.loopBeats - b) < 1e-6, color = C.green, fontSize = 11) { d.setLoop(b) }
            }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Pill("IN", Modifier.weight(1f), on = d.loopInPending >= 0, color = C.yellow, fontSize = 11) { d.loopIn() }
        Pill("OUT", Modifier.weight(1f), fontSize = 11) { d.loopOut() }
        Pill("SAIR", Modifier.weight(1f), enabled = d.loopOn, fontSize = 11) { d.loopOn = false }
    }
}

@Composable
private fun PadColumn(d: Deck, modifier: Modifier) {
    val ctx = LocalContext.current
    d.ui.edits
    val t = d.ui.track
    Column(
        modifier.fillMaxHeight().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Text("DECK ${d.index + 1}", color = C.deck(d.index), fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Section("HOT CUES (toque longo apaga)")
        for (r in 0 until 4) {
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                for (c in 0 until 2) {
                    val i = r * 2 + c
                    val set = (t?.hotcues?.get(i) ?: -1.0) >= 0
                    Pill(
                        "${i + 1}", Modifier.weight(1f).height(46.dp), on = set, color = C.hot[i],
                        onLong = { d.clearHotCue(i) }, instant = true,
                    ) { d.hotCue(i) }
                }
            }
        }
        Section("SALTO EM BATIDAS")
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Pill("-4", Modifier.weight(1f), fontSize = 11) { d.beatJump(-4.0) }
            Pill("-1", Modifier.weight(1f), fontSize = 11) { d.beatJump(-1.0) }
            Pill("+1", Modifier.weight(1f), fontSize = 11) { d.beatJump(1.0) }
            Pill("+4", Modifier.weight(1f), fontSize = 11) { d.beatJump(4.0) }
        }
        LoopPads(d)
        Section("BPM")
        Pill("TAP", Modifier.fillMaxWidth().height(52.dp), color = C.deck(d.index), fontSize = 16) { d.tap() }
        val tap = d.ui.tapInfo
        Text(
            tap.ifEmpty { "Bata no ritmo (4 ou mais toques). Ao parar, o app afina o BPM exato pelo áudio." },
            color = if (tap.isEmpty()) C.dim else C.green, fontSize = 10.sp,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Pill("÷2", Modifier.weight(1f), fontSize = 10) { d.scaleBpm(0.5) }
            Pill("×2", Modifier.weight(1f), fontSize = 10) { d.scaleBpm(2.0) }
            Pill("AFINAR", Modifier.weight(1.4f), fontSize = 10) { d.refineBpm() }
        }
        Pill("BPM DA ANÁLISE", Modifier.fillMaxWidth(), fontSize = 10) { d.restoreGrid(ctx) }
        Pill("ANDAMENTO ORIGINAL (tira o sync)", Modifier.fillMaxWidth(), fontSize = 10) { d.resetTempo() }
        Section("GRADE (downbeat = o 1)")
        Pill("MARCAR 1 AQUI", Modifier.fillMaxWidth(), fontSize = 11, onLong = { d.downbeatHere(exact = true) }) { d.downbeatHere() }
        Text("Toque: o 1 vai para a batida mais próxima. Segure: posição exata.", color = C.dim, fontSize = 9.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Pill("1 ◀ batida", Modifier.weight(1f), fontSize = 10) { d.shiftDownbeat(-1) }
            Pill("batida ▶ 1", Modifier.weight(1f), fontSize = 10) { d.shiftDownbeat(1) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Pill("◀ 10 ms", Modifier.weight(1f), fontSize = 10) { d.nudgeGrid(-10.0) }
            Pill("10 ms ▶", Modifier.weight(1f), fontSize = 10) { d.nudgeGrid(10.0) }
        }
        Section("INÍCIO DA MÚSICA (onde ela carrega)")
        Pill("INÍCIO AQUI", Modifier.fillMaxWidth(), fontSize = 11) { d.setCue() }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Pill("NA BATERIA", Modifier.weight(1f), enabled = d.ui.stemsReady, fontSize = 10) { d.startAtDrums() }
            Pill("AUTOMÁTICO", Modifier.weight(1f), fontSize = 10) { d.resetCue() }
        }
        Text(
            if (d.ui.stemsReady) "NA BATERIA pula a introdução: vai para o 1º compasso em que a bateria entra."
            else "NA BATERIA fica disponível depois da separação das faixas.",
            color = C.dim, fontSize = 9.sp,
        )
    }
}

// ---------------- TRANSPORTE ----------------

@Composable
private fun Transport(onTempo: (Int) -> Unit) {
    val e = Pista.engine
    Column(
        Modifier.fillMaxWidth().background(C.panel).padding(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SyncPill(decks[0], Modifier.width(74.dp))
            BpmBox(decks[0], Modifier.weight(1f), true) { onTempo(0) }
            QPill()
            BpmBox(decks[1], Modifier.weight(1f), false) { onTempo(1) }
            SyncPill(decks[1], Modifier.width(74.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Pill("SET", Modifier.weight(1f), fontSize = 12, instant = true) { decks[0].setCue() }
            Pill("CUE ↩", Modifier.weight(1f), fontSize = 12, instant = true) { decks[0].gotoCue() }
            Spacer(Modifier.width(10.dp))
            Pill("SET", Modifier.weight(1f), fontSize = 12, instant = true) { decks[1].setCue() }
            Pill("CUE ↩", Modifier.weight(1f), fontSize = 12, instant = true) { decks[1].gotoCue() }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            PlayButton(decks[0])
            var xf by remember { mutableFloatStateOf(e.xfade) }
            HFader(xf, { xf = it; e.xfade = it }, Modifier.weight(1f).height(60.dp).padding(horizontal = 8.dp))
            PlayButton(decks[1])
        }
    }
}

@Composable
private fun SyncPill(d: Deck, modifier: Modifier) {
    Tick.frame.longValue
    Pill("SYNC", modifier, on = d.sync, color = C.deck(d.index), fontSize = 12) { Pista.engine.toggleSync(d) }
}

@Composable
private fun QPill() {
    var q by remember { mutableStateOf(Pista.engine.quantize) }
    Pill("Q", on = q, color = C.green, fontSize = 12) {
        q = !q
        Pista.engine.quantize = q
        Pista.prefs.edit().putBoolean("quantize", q).apply()
    }
}

@Composable
private fun BpmBox(d: Deck, modifier: Modifier, left: Boolean, onClick: () -> Unit) {
    Tick.frame.longValue
    val pct = (d.rate - 1.0) * 100
    Column(
        modifier.clip(RoundedCornerShape(8.dp))
            .pointerInput(d) { detectTapGestures(onTap = { onClick() }, onLongPress = { d.resetTempo() }) }
            .padding(horizontal = 4.dp),
        horizontalAlignment = if (left) Alignment.Start else Alignment.End,
    ) {
        Text(fmtBpm(d.effBpm) + " ▾", color = C.txt, fontSize = 20.sp)
        Text(
            (if (abs(pct) < 0.05) "BPM" else "BPM  %+.1f%%".format(pct)) + if (d.keylock) "  🔒" else "",
            color = C.dim, fontSize = 10.sp,
        )
        // quem manda no andamento quando o outro lado está em SYNC
        if (Pista.engine.decks[1 - d.index].sync) {
            Text(
                "MASTER", color = Color.Black, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(C.deck(d.index)).padding(horizontal = 5.dp),
            )
        }
    }
}

// ---------------- PAINEL DE ANDAMENTO / TOM / GRADE ----------------

@Composable
private fun TempoPanel(d: Deck, onClose: () -> Unit) {
    val ctx = LocalContext.current
    var pitch by remember { mutableFloatStateOf(d.pitch.toFloat()) }
    var range by remember { mutableFloatStateOf(d.range.toFloat()) }
    var keylock by remember { mutableStateOf(d.keylock) }
    var shift by remember { mutableIntStateOf(d.keyShift) }
    Box(
        Modifier.fillMaxSize().background(Color(0xAA000000)).pointerInput(Unit) { detectTapGestures { onClose() } },
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                .background(C.panel)
                .pointerInput(Unit) { detectTapGestures { } }
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            TempoLive(d)
            Slider(
                value = pitch.coerceIn(-range, range),
                onValueChange = {
                    pitch = it
                    if (d.sync) d.sync = false
                    d.pitch = it.toDouble()
                },
                valueRange = -range..range,
                colors = SliderDefaults.colors(thumbColor = C.deck(d.index), activeTrackColor = C.deck(d.index)),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (r in listOf(0.08f, 0.16f, 0.5f)) {
                    Pill("±${(r * 100).toInt()}%", Modifier.weight(1f), on = range == r, fontSize = 11) {
                        range = r
                        d.range = r.toDouble()
                        Pista.prefs.edit().putFloat("range", r).apply()
                    }
                }
                Pill("ORIGINAL", Modifier.weight(1.2f), fontSize = 11) { pitch = 0f; d.resetTempo() }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Pill("◀◀ empurra", Modifier.weight(1f), fontSize = 11) { d.nudge(-0.04) }
                Pill("empurra ▶▶", Modifier.weight(1f), fontSize = 11) { d.nudge(0.04) }
            }
            Text("TOM", color = C.dim, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Pill("KEYLOCK", Modifier.weight(1.3f), on = keylock, color = C.green, fontSize = 11) {
                    keylock = !keylock; d.keylock = keylock
                }
                Pill("−", Modifier.weight(0.7f)) { shift = (shift - 1).coerceAtLeast(-6); d.keyShift = shift }
                Text("%+d".format(shift), color = C.txt, fontSize = 15.sp)
                Pill("+", Modifier.weight(0.7f)) { shift = (shift + 1).coerceAtMost(6); d.keyShift = shift }
                Pill("CASAR TOM", Modifier.weight(1.5f), fontSize = 11) {
                    val o = decks[1 - d.index]
                    val ok = o.track?.key ?: -1
                    val mk = d.track?.key ?: -1
                    val s = Keys.matchShift(mk, Keys.shifted(ok, o.keyShift))
                    if (s != null) { shift = s; d.keyShift = s }
                }
            }
            Text("BPM E GRADE", color = C.dim, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Pill("TAP", Modifier.weight(1.4f).height(48.dp), color = C.deck(d.index), fontSize = 15) { d.tap() }
                Pill("−0,01", Modifier.weight(1f), fontSize = 11) { d.fineBpm(-0.01) }
                Pill("+0,01", Modifier.weight(1f), fontSize = 11) { d.fineBpm(0.01) }
                Pill("1 AQUI", Modifier.weight(1f), fontSize = 11, onLong = { d.downbeatHere(exact = true) }) { d.downbeatHere() }
            }
            Pill("VOLTAR AO BPM E GRADE DA ANÁLISE", Modifier.fillMaxWidth(), fontSize = 11) { d.restoreGrid(ctx) }
            val tap = d.ui.tapInfo
            if (tap.isNotEmpty()) Text(tap, color = C.green, fontSize = 12.sp)
            Pill("FECHAR", Modifier.fillMaxWidth()) { onClose() }
        }
    }
}

@Composable
private fun TempoLive(d: Deck) {
    Tick.frame.longValue
    val t = d.track
    Row(verticalAlignment = Alignment.Bottom) {
        Text("DECK ${d.index + 1}  ", color = C.deck(d.index), fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Text(fmtBpm(d.effBpm), color = C.txt, fontSize = 30.sp)
        Text(
            "  original ${fmtBpm(d.bpm)}   %+.2f%%".format((d.rate - 1) * 100) + if (d.sync) "   SYNC" else "",
            color = C.dim, fontSize = 12.sp,
        )
    }
    if (t != null && t.key >= 0) {
        val k = Keys.shifted(t.key, d.keyShift)
        Text(
            "Tom: ${Keys.name(k)} (${Keys.camelot(k)})" + if (d.keyShift != 0) "   original ${Keys.name(t.key)}" else "",
            color = Color(Keys.color(k)), fontSize = 13.sp,
        )
    }
}
