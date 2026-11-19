package com.iptv.tv.ui

import android.content.Context
import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.iptv.tv.AppViewModel
import com.iptv.tv.FAV_GROUP
import com.iptv.tv.Item
import com.iptv.tv.MultiChannel
import com.iptv.tv.Screen
import kotlinx.coroutines.delay

/**
 * Coklu ekran: 2'li (yan yana) veya 4'lu (2x2) kanal izleme.
 * Ses sadece secili (cerceveli) karodan gelir. Her karo saglayiciya ayri baglanti acar.
 *
 * Cihaz türüne göre optimize edilmiş arayüz:
 *   TV:      büyük yazılar, geniş panel, tam ekran karolar
 *   Tablet:  orta boy yazılar, orta panel
 *   Telefon: küçük yazılar, geniş panel (%92)
 */
enum class PickMode { TOP, GROUPS, SOURCES, CHANNELS, SETTINGS }

@Composable
fun MultiScreen(vm: AppViewModel) {
    val tiles = vm.multiTiles
    val players = remember { List(4) { vm.newTilePlayer() } }
    val urls = remember { arrayOfNulls<String>(4) }
    val failed = remember { mutableStateListOf(false, false, false, false) }
    val loadingTile = remember { mutableStateListOf(false, false, false, false) }
    var layout by remember { mutableIntStateOf(vm.multiLayout) }
    var focus by remember { mutableIntStateOf(0) }
    var picker by remember { mutableStateOf(false) }
    var activity by remember { mutableIntStateOf(0) }
    var active by remember { mutableStateOf(true) }
    var showMenu by remember { mutableStateOf(false) }
    val device = rememberDeviceType()
    val isTv = device == DeviceType.TV

    LaunchedEffect(activity) { active = true; delay(4000); active = false }
    LaunchedEffect(Unit) { vm.multiEnsureInitial() }
    // Picker işlemsiz 10 sn sonra kapanır (her tuşta sayaç sıfırlanır)
    LaunchedEffect(activity, picker) {
        if (picker) {
            delay(10_000)
            if (picker) picker = false
        }
    }
    var pickIdx by remember { mutableIntStateOf(0) }
    var pickContent by remember { mutableStateOf("canli") }
    var pickMode by remember { mutableStateOf(PickMode.CHANNELS) }
    var pickGroupIdx by remember { mutableIntStateOf(0) }
    var pickChanIdx by remember { mutableIntStateOf(0) }
    var pickSourceIdx by remember { mutableIntStateOf(0) }
    var lastPickMode by remember { mutableStateOf(PickMode.GROUPS) }

    val settingRows = listOf(
        ListRow("Düzen: " + (if (layout == 2) "2'li" else "4'lü"), sub = "Karo sayısını değiştir"),
        ListRow("Kaynak: " + vm.sourceName(vm.multiPickSourceId), sub = "Farklı M3U/Xtream kaynağı seç"),
        ListRow("İçerik: " + pickContent, sub = "Canlı / Film / Dizi seç")
    )

    val fr = remember { FocusRequester() }
    RegisterKeyFocus(fr)

    DisposableEffect(Unit) {
        val listeners = players.mapIndexed { i, p ->
            object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    loadingTile[i] = state == Player.STATE_BUFFERING
                    if (state == Player.STATE_READY) failed[i] = false
                }
                override fun onPlayerError(error: PlaybackException) {
                    failed[i] = true
                }
            }.also { p.addListener(it) }
        }
        onDispose {
            players.forEachIndexed { i, p -> p.removeListener(listeners[i]); p.release() }
        }
    }
    LaunchedEffect(Unit) { runCatching { fr.requestFocus() } }

    LaunchedEffect(tiles.toList(), layout) {
        for (i in 0 until 4) {
            val p = players[i]
            val ch = if (i < layout) tiles[i] else null
            val url = ch?.let { vm.liveUrlOf(it.sourceId, it.item) }
            if (url == null) {
                if (urls[i] != null) { p.stop(); urls[i] = null }
            } else if (urls[i] != url) {
                urls[i] = url
                failed[i] = false
                p.setMediaItem(MediaItem.fromUri(url))
                p.prepare()
                p.playWhenReady = true
            }
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(2_000)
            for (i in 0 until 4) {
                if (failed[i] && urls[i] != null) {
                    failed[i] = false
                    players[i].prepare()
                    players[i].playWhenReady = true
                }
            }
        }
    }
    LaunchedEffect(vm.appVisible) {
        if (!vm.appVisible) {
            for (i in 0 until 4) { players[i].stop() }
        } else {
            for (i in 0 until 4) {
                if (urls[i] != null) { players[i].prepare(); players[i].playWhenReady = true }
            }
        }
    }
    LaunchedEffect(focus, layout) {
        players.forEachIndexed { i, p -> p.volume = if (i == focus) 1f else 0f }
    }

    val groups = if (picker) vm.multiPickGroups(pickContent) else emptyList()
    val groupNow = if (groups.isNotEmpty() && pickGroupIdx in groups.indices) groups[pickGroupIdx] else FAV_GROUP
    val pickList = if (picker && pickMode == PickMode.CHANNELS) vm.multiPickItems(pickContent, groupNow) else emptyList()
    val tileNow = vm.multiTiles.getOrNull(focus)
    val pickTopRows = listOf(
        ListRow("⊞ Düzen: " + (if (layout == 2) "2'li" else "4'lü") + "  (OK ile değiştir)"),
        ListRow("Kaynak: ${vm.sourceName(vm.multiPickSourceId)}  (OK ile değiştir)"),
        ListRow("İçerik: $pickContent  (OK ile değiştir)")
    )
    val pSel = pickIdx.coerceIn(0, (pickTopRows.size - 1).coerceAtLeast(0))

    fun moveFocus(dx: Int, dy: Int) {
        if (layout == 2) {
            focus = (focus + dx).coerceIn(0, 1)
        } else {
            val col = (focus % 2 + dx).coerceIn(0, 1)
            val row = (focus / 2 + dy).coerceIn(0, 1)
            focus = row * 2 + col
        }
    }

    fun openPicker() {
        val cur = tiles.getOrNull(focus)
        // Bos karoda veya kanali yoksa secilecek yok
        if (cur == null || cur.item == null || cur.item.id.isEmpty()) {
            vm.toast("Bu karoda kanal yok. Once kaynak secin.")
            return
        }
        try {
            vm.beginMultiPick(cur.sourceId ?: "")
            pickContent = when (cur.item.kind) {
                "movie" -> "film"
                "series" -> "dizi"
                else -> "canli"
            }
            // Ilk acishda secili kanal varsa kanallara, yoksa gruplara git
            pickMode = if (cur.item.id.isNotEmpty()) PickMode.CHANNELS else PickMode.GROUPS
            pickSourceIdx = vm.sources.indexOfFirst { it.id == vm.multiPickSourceId }.coerceAtLeast(0)
            // Son secilen grup/kanal hatirlenir, sadece gecerli aralikta tutulur
            val groups = vm.multiPickGroups(pickContent)
            pickGroupIdx = pickGroupIdx.coerceIn(0, (groups.size - 1).coerceAtLeast(0))
            val list = vm.multiPickItems(pickContent, groups.getOrNull(pickGroupIdx) ?: FAV_GROUP)
            // Mevcut kanali listede bul, yoksa ilk kanala sec
            pickChanIdx = list.indexOfFirst { it.id == cur.item.id }.coerceIn(0, (list.size - 1).coerceAtLeast(0))
            picker = true
        } catch (e: Exception) {
            vm.toast("Kaydirma hatasi: ${e.message}")
            picker = false
        }
    }

    BackHandler {
        if (picker) {
            when (pickMode) {
                PickMode.CHANNELS -> pickMode = PickMode.GROUPS
                PickMode.GROUPS -> pickMode = PickMode.TOP
                PickMode.SOURCES -> pickMode = PickMode.TOP
                PickMode.SETTINGS -> pickMode = PickMode.GROUPS
                PickMode.TOP -> picker = false
            }
        } else {
            vm.openScreen(Screen.LIVE)
        }
    }

    fun pickAt(i: Int) {
        pickIdx = i
        when (i) {
            0 -> {
                layout = if (layout == 2) 4 else 2
                vm.multiLayout = layout
                vm.saveMultiLayout(layout)
                if (focus >= layout) focus = 0
            }
            1 -> {
                pickMode = PickMode.SOURCES
                pickGroupIdx = 0
                pickChanIdx = 0
            }
            2 -> {
                pickContent = when (pickContent) {
                    "canli" -> "film"
                    "film" -> "dizi"
                    else -> "canli"
                }
                pickGroupIdx = 0
                pickChanIdx = 0
            }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onPreviewKeyEvent { activity++; false }
            // Dokunmatik: sağa/sola sürükle ile karolar arasında geçiş
            .touchGestures(
                onSwipeRight = {
                    if (layout == 2) focus = (focus + 1).coerceIn(0, 1)
                    else focus = (focus + 1) % 4
                },
                onSwipeLeft = {
                    if (layout == 2) focus = (focus - 1).coerceAtLeast(0)
                    else focus = (focus - 1 + 4) % 4
                }
            )
            // focusable MUST come before tvKeys (onKeyEvent requires focusable node)
            .then(Modifier.focusRequester(fr).focusable())
            // pointerInput kaldirildi: dokunmatik ciharlarda (telefon/tablet) carpan
            // olaylari engelleme; touchGestures (Tile) kendi eventi yakalasin.
            .tvKeys(
                onUp = {
                    if (picker) {
                        when (pickMode) {
                            PickMode.TOP -> pickIdx = (pSel - 1).coerceAtLeast(0)
                            PickMode.GROUPS -> pickGroupIdx = (pickGroupIdx - 1).coerceAtLeast(0)
                            PickMode.SOURCES -> pickSourceIdx = (pickSourceIdx - 1).coerceAtLeast(0)
                            PickMode.CHANNELS -> pickChanIdx = (pickChanIdx - 1).coerceAtLeast(0)
                            PickMode.SETTINGS -> pickIdx = (pSel - 1).coerceAtLeast(0)
                        }
                    } else {
                        moveFocus(0, -1)
                    }
                    true
                },
                onDown = {
                    if (picker) {
                        when (pickMode) {
                            PickMode.TOP -> pickIdx = (pSel + 1).coerceAtMost((pickTopRows.size - 1).coerceAtLeast(0))
                            PickMode.GROUPS -> pickGroupIdx = (pickGroupIdx + 1).coerceAtMost((groups.size - 1).coerceAtLeast(0))
                            PickMode.SOURCES -> pickSourceIdx = (pickSourceIdx + 1).coerceAtMost((vm.sources.size - 1).coerceAtLeast(0))
                            PickMode.CHANNELS -> pickChanIdx = (pickChanIdx + 1).coerceAtMost((pickList.size - 1).coerceAtLeast(0))
                            PickMode.SETTINGS -> pickIdx = (pSel + 1).coerceAtMost((settingRows.size - 1).coerceAtLeast(0))
                        }
                    } else {
                        moveFocus(0, 1)
                    }
                    true
                },
                onLeft = {
                    if (picker) {
                        when (pickMode) {
                            PickMode.TOP -> pickMode = PickMode.GROUPS
                            PickMode.GROUPS -> pickMode = PickMode.SETTINGS
                            PickMode.SOURCES -> pickMode = PickMode.TOP
                            PickMode.CHANNELS -> pickMode = PickMode.GROUPS
                            PickMode.SETTINGS -> pickMode = PickMode.GROUPS
                        }
                    } else {
                        moveFocus(-1, 0)
                    }
                    true
                },
                onRight = {
                    if (picker) {
                        when (pickMode) {
                            PickMode.SETTINGS -> pickMode = PickMode.GROUPS
                            PickMode.GROUPS -> { pickMode = PickMode.CHANNELS; lastPickMode = PickMode.CHANNELS }
                            PickMode.SOURCES -> { pickMode = PickMode.CHANNELS; lastPickMode = PickMode.CHANNELS }
                            PickMode.CHANNELS -> {}
                            PickMode.TOP -> {}
                        }
                    } else if (!picker) {
                        moveFocus(1, 0)
                    }
                    true
                },
                onOk = {
                    if (!picker) openPicker()
                    else when (pickMode) {
                        PickMode.TOP -> pickAt(pSel)
                        PickMode.GROUPS -> pickMode = PickMode.CHANNELS
                        PickMode.SOURCES -> {
                            vm.sources.getOrNull(pickSourceIdx)?.let { src ->
                                vm.beginMultiPick(src.id)
                                pickSourceIdx = vm.sources.indexOfFirst { it.id == src.id }.coerceAtLeast(0)
                                pickGroupIdx = 0
                                pickChanIdx = 0
                                pickMode = PickMode.CHANNELS
                            }
                        }
                        PickMode.CHANNELS -> {
                            pickList.getOrNull(pickChanIdx)?.let {
                                vm.setMultiTile(focus, MultiChannel(vm.multiPickSourceId, it))
                                picker = false
                                pickMode = PickMode.TOP
                                lastPickMode = PickMode.CHANNELS
                            }
                        }
                        PickMode.SETTINGS -> pickAt(pSel)
                    }
                },
                onLongOk = { if (!picker) vm.fullscreenFromMulti(tiles.getOrNull(focus)) },
                onMenu = { if (picker) picker = false else openPicker(); true },
                onChannelUp = { true },
                onChannelDown = { true }
            )
    ) {
        val rows = if (layout == 2) listOf(listOf(0, 1)) else listOf(listOf(0, 1), listOf(2, 3))
        Column(Modifier.fillMaxSize()) {
            rows.forEach { r ->
                Row(Modifier.fillMaxWidth().weight(1f)) {
                    r.forEach { i ->
                        Tile(
                            player = players[i],
                            item = tiles.getOrNull(i)?.item,
                            focused = i == focus,
                            showUi = active || picker,
                            loading = loadingTile[i],
                            failed = failed[i],
                            resizeMode = vm.resizeMode,
                            // TV'de 2'li kâro ise görüntüyü kâro içine maksimum boyutta yerleştir
                            modifier = if (isTv && layout == 2) Modifier.weight(1f).fillMaxHeight().padding(0.dp)
                            else Modifier.weight(1f).fillMaxHeight().padding(2.dp),
                            onTap = { if (focus == i) openPicker() else focus = i },
                            onLongPress = { vm.fullscreenFromMulti(tiles.getOrNull(i)) },
                            isTv = isTv,
                            layout = layout
                        )
                    }
                }
            }
        }

        if (picker) {
            Box(Modifier.fillMaxSize()) {
                Column(
                    Modifier
                        .align(Alignment.CenterStart)
                        .fillMaxHeight()
                        .width(multiPanelWidth(560))
                        .background(PanelBg)
                        .swallowTouches()
                        .padding(padSizeDp(12))
                ) {
                    PanelHeader("KARO ${focus + 1} İÇİN $pickContent")
                    if (vm.multiPickBusy()) {
                        Text(
                            "Kaynak listesi yükleniyor…", color = Color(0xFF94A3B8),
                            fontSize = txtSize(14f).sp,
                            modifier = Modifier.padding(bottom = padSizeDp(6))
                        )
                    }
                    when (pickMode) {
                        PickMode.TOP -> {
                            TvList(
                                pickTopRows, pSel,
                                Modifier.weight(1f),
                                rowHeight = rowHeightDp(44),
                                textSize = txtSize(16f),
                                subSize = txtSize(12f),
                                onClick = { i -> pickAt(i) }
                            )
                            Text(
                                "OK: Seç · Sol: Gruplar · Geri: Kapat",
                                color = Color(0xFF94A3B8), fontSize = txtSize(13f).sp,
                                modifier = Modifier.padding(top = padSizeDp(6))
                            )
                        }
                        PickMode.GROUPS -> {
                            val groupRows = groups.mapIndexed { i, g ->
                                ListRow(
                                    g, sub = "", logo = "",
                                    badge = if (groupNow == g) "•" else ""
                                )
                            }
                            TvList(
                                groupRows, pickGroupIdx,
                                Modifier.weight(1f),
                                rowHeight = rowHeightDp(44),
                                textSize = txtSize(16f),
                                subSize = txtSize(12f),
                                onClick = { i ->
                                    pickGroupIdx = i
                                    pickMode = PickMode.CHANNELS
                                    pickChanIdx = 0
                                }
                            )
                            Text(
                                "OK: Kanallara git · Sağ: Geri · Yukarı/Aşağı: Grup seç",
                                color = Color(0xFF94A3B8), fontSize = txtSize(13f).sp,
                                modifier = Modifier.padding(top = padSizeDp(6))
                            )
                        }
                        PickMode.SOURCES -> {
                            val sourceRows = vm.sources.mapIndexed { i, s ->
                                ListRow(
                                    s.name, sub = s.type.uppercase(),
                                    badge = if (s.id == vm.multiPickSourceId) "•" else ""
                                )
                            }
                            TvList(
                                sourceRows, pickSourceIdx,
                                Modifier.weight(1f),
                                rowHeight = rowHeightDp(44),
                                textSize = txtSize(16f),
                                subSize = txtSize(12f),
                                onClick = { i ->
                                    pickSourceIdx = i
                                    vm.sources.getOrNull(i)?.let { src ->
                                        vm.beginMultiPick(src.id)
                                        pickMode = PickMode.CHANNELS
                                    }
                                }
                            )
                            Text(
                                "OK: Seç ve kanallara git · Sol: Geri · Yukarı/Aşağı: Kaynak seç",
                                color = Color(0xFF94A3B8), fontSize = txtSize(13f).sp,
                                modifier = Modifier.padding(top = padSizeDp(6))
                            )
                        }
                        PickMode.CHANNELS -> {
                            val chanRows = pickList.mapIndexed { i, c ->
                                ListRow(
                                    c.name, sub = (i + 1).toString(), logo = c.logo,
                                    badge = if (tileNow != null && tileNow.sourceId == vm.multiPickSourceId && c.id == tileNow.item.id) "▶" else ""
                                )
                            }
                            TvList(
                                chanRows, pickChanIdx,
                                Modifier.weight(1f),
                                rowHeight = rowHeightDp(44),
                                textSize = txtSize(16f),
                                subSize = txtSize(12f),
                                onClick = { i ->
                                    pickChanIdx = i
                                    pickList.getOrNull(i)?.let {
                                        vm.setMultiTile(focus, MultiChannel(vm.multiPickSourceId, it))
                                        picker = false
                                        pickMode = PickMode.TOP
                                    }
                                }
                            )
                            Text(
                                "OK: Karo'ya ata · Sol: Gruplara geri · Yukarı/Aşağı: Kanal seç",
                                color = Color(0xFF94A3B8), fontSize = txtSize(13f).sp,
                                modifier = Modifier.padding(top = padSizeDp(6))
                            )
                        }
                        PickMode.SETTINGS -> {
                            TvList(
                                settingRows, pickIdx,
                                Modifier.weight(1f),
                                rowHeight = rowHeightDp(44),
                                textSize = txtSize(16f),
                                subSize = txtSize(12f),
                                onClick = { i -> pickAt(i) }
                            )
                            Text(
                                "OK: Seç · Sol: Geri · Yukarı/Aşağı: Seçim yap",
                                color = Color(0xFF94A3B8), fontSize = txtSize(13f).sp,
                                modifier = Modifier.padding(top = padSizeDp(6))
                            )
                        }
                    }
                }
            }
        } else if (showMenu) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.8f))) {
                Column(
                    Modifier
                        .align(Alignment.Center)
                        .width(if (isTv) 520.dp else 420.dp)
                        .background(PanelBg)
                        .padding(padSizeDp(16))
                ) {
                    PanelHeader("MENÜ")
                    TvButton("Canlı TV") { showMenu = false; vm.openScreen(Screen.LIVE) }
                    TvButton("Filmler") { showMenu = false; vm.openScreen(Screen.MOVIES) }
                    TvButton("Diziler") { showMenu = false; vm.openScreen(Screen.SERIES) }
                    TvButton("Ayarlar") { showMenu = false; vm.openScreen(Screen.SETTINGS) }
                    Text("Geri: Kapat", color = Color(0xFF94A3B8), fontSize = txtSize(13f).sp,
                        modifier = Modifier.padding(top = padSizeDp(8)))
                }
            }
        } else if (active) {
            Row(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = padSizeDp(10)),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                BarChip(if (layout == 2) "⊞ 4'lü ekran" else "⊟ 2'li ekran") {
                    layout = if (layout == 2) 4 else 2
                    vm.multiLayout = layout
                    vm.saveMultiLayout(layout)
                    if (focus >= layout) focus = 0
                }
                BarChip("OK: Kanal değiştir") { openPicker() }
                BarChip("Uzun OK: Tam ekran") { vm.fullscreenFromMulti(tiles.getOrNull(focus)) }
                BarChip("Menü") { showMenu = true }
                BarChip("Geri: Çık") { vm.openScreen(Screen.LIVE) }
            }
        }
    }
}

@Composable
private fun Tile(
    player: ExoPlayer,
    item: Item?,
    focused: Boolean,
    showUi: Boolean,
    loading: Boolean,
    failed: Boolean,
    resizeMode: Int,
    modifier: Modifier,
    isTv: Boolean = false,
    layout: Int = 2,
    onTap: () -> Unit = {},
    onLongPress: () -> Unit = {}
) {

    Box(
        modifier
            .touchGestures(onTap = onTap, onLongPress = onLongPress)
            .then(if (focused && showUi) Modifier.border(4.dp, Accent) else Modifier)
            .background(Color.Black)
    ) {
        if (item != null) {
            PlayerSurface(player, resizeMode)
            if (loading || failed) {
                CircularProgressIndicator(Modifier.align(Alignment.Center), color = Accent)
            }
        } else {
            Text(
                "Boş kâro\nOK: kanal seç", color = Color(0xFF94A3B8),
                fontSize = txtSize(18f).sp,
                modifier = Modifier.align(Alignment.Center)
            )
        }
        if (showUi || item == null) {
            val name = item?.name ?: ""
            // TV'de 2'li kâroda alt metin daha kucuk, goruntuye daha fazla yer birak
            val tp: Dp
            val hp: Dp
            val vp: Dp
            val ts: Float
            if (isTv && layout == 2) {
                tp = padSizeDp(6)
                hp = padSizeDp(4)
                vp = padSizeDp(2)
                ts = txtSize(13f)
            } else {
                tp = padSizeDp(10)
                hp = padSizeDp(8)
                vp = padSizeDp(4)
                ts = txtSize(15f)
            }
            Text(
                (if (focused) "🔊 " else "") + name,
                color = Color.White, fontSize = ts.sp, fontWeight = FontWeight.Medium, maxLines = 2,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(tp)
                    .background(Color(0x99000000), RoundedCornerShape(6.dp))
                    .padding(horizontal = hp, vertical = vp)
            )
        }
    }
}

@Composable
private fun BarChip(text: String, onClick: () -> Unit) {
    Text(
        text, color = Color(0xFFE2E8F0), fontSize = txtSize(14f).sp,
        modifier = Modifier
            .background(Color(0xCC000000), RoundedCornerShape(8.dp))
            .pointerInput(Unit) { detectTapGestures { onClick() } }
            .padding(horizontal = padSizeDp(12), vertical = padSizeDp(6))
    )
}
