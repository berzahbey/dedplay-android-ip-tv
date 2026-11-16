package com.iptv.tv.ui

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.iptv.tv.AppViewModel
import com.iptv.tv.FAV_GROUP
import com.iptv.tv.Item
import com.iptv.tv.Screen
import kotlinx.coroutines.delay

/**
 * Coklu ekran: 2'li (yan yana) veya 4'lu (2x2) kanal izleme.
 * Ses sadece secili (cerceveli) karodan gelir. Her karo saglayiciya ayri baglanti acar.
 */
@Composable
fun MultiScreen(vm: AppViewModel) {
    val tiles = remember { mutableStateListOf<Item?>().apply { addAll(vm.multiInitial()) } }
    val players = remember { List(4) { vm.newTilePlayer() } }
    val urls = remember { arrayOfNulls<String>(4) }
    val failed = remember { mutableStateListOf(false, false, false, false) }
    val loadingTile = remember { mutableStateListOf(false, false, false, false) }
    var layout by remember { mutableIntStateOf(vm.multiLayout) }
    var focus by remember { mutableIntStateOf(0) }
    var picker by remember { mutableStateOf(false) }
    var pickIdx by remember { mutableIntStateOf(0) }
    var pickGroup by remember { mutableStateOf(vm.liveGroup) }
    val fr = remember { FocusRequester() }

    // Oynatici dinleyicileri: hata olursa 2 sn sonra yeniden dene
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

    // Karolardaki kanallari baslat / durdur
    LaunchedEffect(tiles.toList(), layout) {
        for (i in 0 until 4) {
            val p = players[i]
            val item = if (i < layout) tiles[i] else null
            val url = item?.let { vm.liveUrlOf(it) }
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
    // Hata veren karoyu yeniden dene
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
    // Arka plana gecince karolari durdur, donunce yeniden baslat
    LaunchedEffect(vm.appVisible) {
        if (!vm.appVisible) {
            for (i in 0 until 4) { players[i].stop() }
        } else {
            for (i in 0 until 4) {
                if (urls[i] != null) { players[i].prepare(); players[i].playWhenReady = true }
            }
        }
    }
    // Ses sadece secili karodan
    LaunchedEffect(focus, layout) {
        players.forEachIndexed { i, p -> p.volume = if (i == focus) 1f else 0f }
    }

    val pickGroups = vm.liveGroupsWithFav()
    val pickList = vm.itemsIn("live", pickGroup)
    // Secici satirlari: [0] duzen degistir, [1] grup degistir, sonra kanallar
    val pickRows = listOf(
        ListRow("⊞ Düzen: " + (if (layout == 2) "2'li" else "4'lü") + "  (değiştirmek için OK)"),
        ListRow("Grup: $pickGroup  (değiştirmek için OK)")
    ) + pickList.mapIndexed { i, c ->
        ListRow(c.name, sub = (i + 1).toString(), logo = c.logo, badge = if (c.id == tiles.getOrNull(focus)?.id) "▶" else "")
    }
    val pSel = pickIdx.coerceIn(0, (pickRows.size - 1).coerceAtLeast(0))

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
        val curId = tiles.getOrNull(focus)?.id
        val i = pickList.indexOfFirst { it.id == curId }
        pickIdx = if (i >= 0) i + 2 else 2
        picker = true
    }

    BackHandler { if (picker) picker = false else vm.openScreen(Screen.LIVE) }

    /** Secicide i. satir: [0] duzen, [1] grup, sonrasi kanal. */
    fun pickAt(i: Int) {
        pickIdx = i
        when (i) {
            0 -> {
                layout = if (layout == 2) 4 else 2
                vm.multiLayout = layout
                if (focus >= layout) focus = 0
            }
            1 -> {
                val gi = pickGroups.indexOf(pickGroup)
                pickGroup = pickGroups.getOrNull((gi + 1) % pickGroups.size.coerceAtLeast(1)) ?: FAV_GROUP
                pickIdx = 1
            }
            else -> pickList.getOrNull(i - 2)?.let {
                tiles[focus] = it
                picker = false
            }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .tvKeys(
                onUp = { if (picker) pickIdx = (pSel - 1).coerceAtLeast(0) else moveFocus(0, -1); true },
                onDown = {
                    if (picker) pickIdx = (pSel + 1).coerceAtMost((pickRows.size - 1).coerceAtLeast(0))
                    else moveFocus(0, 1)
                    true
                },
                onLeft = { if (picker) picker = false else moveFocus(-1, 0); true },
                onRight = { if (!picker) moveFocus(1, 0); true },
                onOk = {
                    if (!picker) openPicker() else pickAt(pSel)
                },
                // Uzun OK: secili karoyu tam ekran ac
                onLongOk = { if (!picker) vm.fullscreenFromMulti(tiles.getOrNull(focus)) },
                onMenu = { if (picker) picker = false else openPicker(); true },
                onChannelUp = { true },
                onChannelDown = { true }
            )
            .focusRequester(fr)
            .focusable()
    ) {
        val rows = if (layout == 2) listOf(listOf(0, 1)) else listOf(listOf(0, 1), listOf(2, 3))
        Column(Modifier.fillMaxSize()) {
            rows.forEach { r ->
                Row(Modifier.fillMaxWidth().weight(1f)) {
                    r.forEach { i ->
                        Tile(
                            player = players[i],
                            item = tiles.getOrNull(i),
                            focused = i == focus,
                            loading = loadingTile[i],
                            failed = failed[i],
                            resizeMode = vm.resizeMode,
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                            // Dokun: karoyu sec (secili karoya dokununca kanal secici), basili tut: tam ekran
                            onTap = { if (focus == i) openPicker() else focus = i },
                            onLongPress = { vm.fullscreenFromMulti(tiles.getOrNull(i)) }
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
                        .width(panelWidth(560))
                        .background(PanelBg)
                        .swallowTouches()
                        .padding(12.dp)
                ) {
                    PanelHeader("KARO ${focus + 1} İÇİN KANAL")
                    TvList(pickRows, pSel, Modifier.weight(1f), onClick = { i -> pickAt(i) })
                    Text(
                        "OK: Seç · ◀ / Geri: Kapat", color = Color(0xFF94A3B8), fontSize = 13.sp,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
            }
        } else {
            Text(
                "Oklar / dokun: karo seç · OK / tekrar dokun: kanal değiştir · Uzun OK / basılı tut: tam ekran · Geri: çık",
                color = Color(0xFFE2E8F0), fontSize = 13.sp,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 10.dp)
                    .background(Color(0xAA000000), RoundedCornerShape(6.dp))
                    .padding(horizontal = 12.dp, vertical = 4.dp)
            )
        }
    }
}

@Composable
private fun Tile(
    player: ExoPlayer,
    item: Item?,
    focused: Boolean,
    loading: Boolean,
    failed: Boolean,
    resizeMode: Int,
    modifier: Modifier,
    onTap: () -> Unit = {},
    onLongPress: () -> Unit = {}
) {
    Box(
        modifier
            .padding(2.dp)
            .touchGestures(onTap = onTap, onLongPress = onLongPress)
            .then(if (focused) Modifier.border(3.dp, Accent) else Modifier)
            .background(Color.Black)
    ) {
        if (item != null) {
            PlayerSurface(player, resizeMode)
            if (loading || failed) {
                CircularProgressIndicator(Modifier.align(Alignment.Center), color = Accent)
            }
        } else {
            Text(
                "Boş karo\nOK: kanal seç", color = Color(0xFF94A3B8), fontSize = 16.sp,
                modifier = Modifier.align(Alignment.Center)
            )
        }
        Text(
            (if (focused) "🔊 " else "") + (item?.name ?: ""),
            color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(6.dp)
                .background(Color(0xAA000000), RoundedCornerShape(4.dp))
                .padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}
