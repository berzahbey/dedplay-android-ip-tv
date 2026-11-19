package com.iptv.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.iptv.tv.AppViewModel
import com.iptv.tv.FAV_GROUP
import com.iptv.tv.ALL_GROUP
import com.iptv.tv.Panel
import com.iptv.tv.Recorder
import com.iptv.tv.Screen
import kotlinx.coroutines.delay

private val MENU_ITEMS = listOf("Canlı TV", "Filmler", "Diziler", "Ara", "Çoklu ekran", "Kayıtlar", "Ses · Altyazı · Kayıt", "Ayarlar")

@Composable
fun LiveScreen(vm: AppViewModel) {
    var panel by remember { mutableStateOf(Panel.NONE) }
    var menuIdx by remember { mutableIntStateOf(0) }
    var groupIdx by remember { mutableIntStateOf(0) }
    var chanIdx by remember { mutableIntStateOf(0) }
    var optIdx by remember { mutableIntStateOf(0) }
    var epgIdx by remember { mutableIntStateOf(0) }
    var showBanner by remember { mutableStateOf(false) }
    var numBuf by remember { mutableStateOf("") }
    val fr = remember { FocusRequester() }
    RegisterKeyFocus(fr)

    LaunchedEffect(vm.library) { vm.ensureLive() }
    LaunchedEffect(Unit) { runCatching { fr.requestFocus() } }
    LaunchedEffect(vm.currentChannel) {
        vm.loadEpg(vm.currentChannel)
        showBanner = true
        delay(5000)
        showBanner = false
    }
    // Rakamla kanal acma: son tustan 1,5 sn sonra kanali ac. Tek basina "0" = onceki kanal.
    LaunchedEffect(numBuf) {
        if (numBuf.isEmpty()) return@LaunchedEffect
        delay(1500)
        val n = numBuf.toIntOrNull()
        numBuf = ""
        when {
            n == null -> {}
            n == 0 -> vm.goPrevious()
            else -> vm.playNumber(n)
        }
    }

    val groups = vm.liveGroupsWithFav()
    val channels = vm.itemsIn("live", vm.liveGroup)
    val gSel = groupIdx.coerceIn(0, (groups.size - 1).coerceAtLeast(0))
    val cSel = chanIdx.coerceIn(0, (channels.size - 1).coerceAtLeast(0))
    val options = playerOptions(vm, live = true, onEpg = { epgIdx = 0; panel = Panel.EPG })
    val oSel = optIdx.coerceIn(0, (options.size - 1).coerceAtLeast(0))
    val nowMs = System.currentTimeMillis()
    val epgList = vm.epg[vm.currentChannel?.id ?: ""].orEmpty().filter { it.end > nowMs }
    val eSel = epgIdx.coerceIn(0, (epgList.size - 1).coerceAtLeast(0))

    // Kanal listesinde secili kanalin yayin akisini getir
    LaunchedEffect(cSel, panel, vm.liveGroup) {
        if (panel == Panel.CHANNELS) {
            delay(300)
            vm.loadEpg(channels.getOrNull(cSel))
        }
    }

    fun openGroups() {
        groupIdx = groups.indexOf(vm.liveGroup).coerceAtLeast(0)
        panel = Panel.GROUPS
    }

    fun openChannels() {
        chanIdx = channels.indexOfFirst { it.id == vm.currentChannel?.id }.coerceAtLeast(0)
        panel = Panel.CHANNELS
    }

    fun openOptions() {
        optIdx = 0
        panel = Panel.OPTIONS
    }

    fun selectGroup(index: Int = gSel) {
        val g = groups.getOrNull(index) ?: return
        vm.liveGroup = g
        val list = vm.itemsIn("live", g)
        chanIdx = list.indexOfFirst { it.id == vm.currentChannel?.id }.coerceAtLeast(0)
        panel = Panel.CHANNELS
    }

    fun menuAction(i: Int) {
        when (i) {
            0 -> panel = Panel.NONE
            1 -> vm.openScreen(Screen.MOVIES)
            2 -> vm.openScreen(Screen.SERIES)
            3 -> vm.openScreen(Screen.SEARCH)
            4 -> vm.openScreen(Screen.MULTI)
            5 -> vm.openScreen(Screen.RECORDINGS)
            6 -> openOptions()
            else -> vm.openScreen(Screen.SETTINGS)
        }
    }

    fun toggleFav(it: com.iptv.tv.Item) {
        val added = vm.toggleFavorite(it)
        vm.toast(if (added) "★ Favorilere eklendi: ${it.name}" else "Favorilerden çıkarıldı: ${it.name}")
    }

    fun optionAt(i: Int) {
        options.getOrNull(i)?.let {
            optIdx = i
            it.action()
            if (it.closes) panel = Panel.NONE
        }
    }

    fun epgAt(i: Int) {
        val ch = vm.currentChannel
        val e = epgList.getOrNull(i)
        if (ch != null && e != null) { epgIdx = i; vm.scheduleProgram(ch, e) }
    }

    // Dokunmatik arac cubugu (ekrana dokununca birkac saniye gorunur)
    val isTv = rememberIsTv()
    var touchBar by remember { mutableStateOf(false) }
    var lastBack by remember { mutableStateOf(0L) }
    // Dokunmatik cihaz: ekrana dokununca kanal listesi gelir; acilista kisa bir ipucu
    LaunchedEffect(Unit) { if (!isTv) vm.toast("Kanal listesi için ekrana dokunun") }
    // Dokunmatik cihaz: liste 5 sn hic dokunulmazsa kendiliginden kapanir
    var touchTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(touchBar) {
        if (touchBar) { delay(6000); touchBar = false }
    }

    // Geri tusu uygulamadan cikarmaz: panel aciksa kapatir, degilse uyarir. Cikis: Home tusu.
    BackHandler {
        if (panel != Panel.NONE) panel = Panel.NONE
        else if (vm.backExit == 1) vm.toast("Çıkmak için Home tuşuna basın (Ayarlar > Geri tuşu)")
        else {
            // 2,5 sn icinde iki kez Geri -> cikis (yanlislikla tek basis cikarmaz)
            val now = System.currentTimeMillis()
            if (now - lastBack < 2500) vm.exitRequested = true
            else { lastBack = now; vm.toast("Çıkmak için tekrar Geri'ye basın") }
        }
    }

    LaunchedEffect(panel, touchTick, chanIdx, groupIdx, menuIdx, optIdx, epgIdx) {
        if (isTv) return@LaunchedEffect
        when (panel) {
            // Soldaki listeler: 5 sn dokunulmazsa kapanir
            Panel.CHANNELS, Panel.GROUPS, Panel.MENU -> { delay(5000); panel = Panel.NONE }
            // Sagdaki secenekler / yayin akisi: okumaya zaman kalsin diye 8 sn
            Panel.OPTIONS, Panel.EPG -> { delay(8000); panel = Panel.NONE }
            else -> {}
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                // Her dokunma / kaydirma (listenin icindekiler dahil) sayaci sifirlar
                awaitPointerEventScope {
                    while (true) { awaitPointerEvent(PointerEventPass.Initial); touchTick++ }
                }
            }
            .tvKeys(
                onUp = {
                    when (panel) {
                        Panel.NONE -> vm.zap(1)
                        Panel.MENU -> menuIdx = (menuIdx - 1).coerceAtLeast(0)
                        Panel.GROUPS -> groupIdx = (gSel - 1).coerceAtLeast(0)
                        Panel.CHANNELS -> chanIdx = (cSel - 1).coerceAtLeast(0)
                        Panel.OPTIONS -> optIdx = (oSel - 1).coerceAtLeast(0)
                        Panel.EPG -> epgIdx = (eSel - 1).coerceAtLeast(0)
                    }
                    true
                },
                onDown = {
                    when (panel) {
                        Panel.NONE -> vm.zap(-1)
                        Panel.MENU -> menuIdx = (menuIdx + 1).coerceAtMost(MENU_ITEMS.size - 1)
                        Panel.GROUPS -> groupIdx = (gSel + 1).coerceAtMost((groups.size - 1).coerceAtLeast(0))
                        Panel.CHANNELS -> chanIdx = (cSel + 1).coerceAtMost((channels.size - 1).coerceAtLeast(0))
                        Panel.OPTIONS -> optIdx = (oSel + 1).coerceAtMost((options.size - 1).coerceAtLeast(0))
                        Panel.EPG -> epgIdx = (eSel + 1).coerceAtMost((epgList.size - 1).coerceAtLeast(0))
                    }
                    true
                },
                // Paneller ekranin solunda: sol tus derine iner (izleme -> kanallar -> gruplar -> menu),
                // sag tus ayni yoldan geri doner. Izlerken sag tus kanal seceneklerini (ses, altyazi...) acar.
                onLeft = {
                    when (panel) {
                        Panel.NONE -> openChannels()
                        Panel.CHANNELS -> openGroups()
                        Panel.GROUPS -> { menuIdx = 0; panel = Panel.MENU }
                        Panel.MENU -> {}
                        Panel.OPTIONS -> panel = Panel.NONE
                        Panel.EPG -> panel = Panel.OPTIONS
                    }
                    true
                },
                onRight = {
                    when (panel) {
                        Panel.NONE -> openOptions() // izlenen kanalin secenekleri (sag tarafta acilir)
                        Panel.CHANNELS -> panel = Panel.NONE
                        Panel.GROUPS -> openChannels()
                        Panel.MENU -> openGroups()
                        Panel.OPTIONS, Panel.EPG -> {}
                    }
                    true
                },
                onOk = {
                    when (panel) {
                        Panel.NONE -> openChannels()
                        Panel.MENU -> menuAction(menuIdx)
                        Panel.GROUPS -> selectGroup()
                        Panel.CHANNELS -> channels.getOrNull(cSel)?.let {
                            vm.playLive(it)
                            panel = Panel.NONE
                        }
                        Panel.OPTIONS -> optionAt(oSel)
                        Panel.EPG -> epgAt(eSel)
                    }
                },
                onLongOk = {
                    when (panel) {
                        Panel.CHANNELS -> channels.getOrNull(cSel)?.let {
                            val added = vm.toggleFavorite(it)
                            vm.toast(if (added) "★ Favorilere eklendi: ${it.name}" else "Favorilerden çıkarıldı: ${it.name}")
                        }
                        Panel.GROUPS -> {
                            val g = groups.getOrNull(gSel)
                            if (g == null || g == FAV_GROUP || g == ALL_GROUP) {
                                vm.toast("Bu grup gizlenemez")
                            } else {
                                vm.toggleHidden("live", g)
                                vm.toast("Gizlendi: $g  (geri açmak: Ayarlar > Grupları yönet)")
                            }
                        }
                        Panel.NONE -> vm.currentChannel?.let {
                            val added = vm.toggleFavorite(it)
                            vm.toast(if (added) "★ Favorilere eklendi: ${it.name}" else "Favorilerden çıkarıldı: ${it.name}")
                        }
                        Panel.MENU, Panel.OPTIONS, Panel.EPG -> {}
                    }
                },
                onChannelUp = { vm.zap(1); true },
                onChannelDown = { vm.zap(-1); true },
                onDigit = { d ->
                    if (panel == Panel.NONE || panel == Panel.CHANNELS) {
                        if (numBuf.length < 4) numBuf += d.toString()
                        true
                    } else false
                },
                onMenu = {
                    if (panel == Panel.OPTIONS || panel == Panel.EPG) panel = Panel.NONE else openOptions()
                    true
                },
                onLastChannel = { vm.goPrevious(); true },
                onPlayPause = { vm.togglePause(); true }
            )
            .touchGestures(
                onTap = {
                    if (panel != Panel.NONE) panel = Panel.NONE
                    else if (isTv) touchBar = !touchBar
                    else openChannels()
                },
                onLongPress = { if (panel == Panel.NONE) openOptions() },
                onSwipeUp = { if (panel == Panel.NONE) vm.zap(1) },
                onSwipeDown = { if (panel == Panel.NONE) vm.zap(-1) },
                // Saga kaydir: kanallar -> gruplar -> ayarlar. Sola kaydir: geri.
                onSwipeRight = {
                    touchBar = false
                    when (panel) {
                        Panel.NONE -> openChannels()
                        Panel.CHANNELS -> openGroups()
                        Panel.GROUPS, Panel.MENU -> { panel = Panel.NONE; vm.openScreen(Screen.SETTINGS) }
                        else -> panel = Panel.NONE
                    }
                },
                onSwipeLeft = {
                    touchBar = false
                    when (panel) {
                        Panel.NONE -> openOptions()
                        Panel.GROUPS -> openChannels()
                        else -> panel = Panel.NONE
                    }
                }
            )
            .then(Modifier.focusRequester(fr).focusable())
    ) {
        PlayerSurface(vm.player, vm.resizeMode)

        if (vm.buffering) {
            CircularProgressIndicator(Modifier.align(Alignment.Center), color = Accent)
        }
        vm.playerMessage?.let {
            Text(
                it, color = Color.White, fontSize = 22.sp,
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Color(0xAA000000), RoundedCornerShape(8.dp))
                    .padding(16.dp)
            )
        }

        val lib = vm.library
        if (lib == null || lib.live.isEmpty()) {
            val msg = when {
                vm.loading -> "Liste yükleniyor…\nBüyük listelerde bir iki dakika sürebilir."
                vm.loadError != null -> vm.loadError + "\n\nAyarlar > Listeyi yenile ile tekrar deneyin."
                lib != null -> "Bu kaynakta canlı kanal bulunamadı."
                else -> "Liste yok."
            }
            Box(Modifier.fillMaxSize().background(ScreenBg), contentAlignment = Alignment.Center) {
                Text(msg, color = Color.White, fontSize = 22.sp, textAlign = TextAlign.Center)
            }
            if (panel == Panel.NONE) {
                Text(
                    "◀ ◀ ◀ : Menü", color = Accent, fontSize = 16.sp,
                    modifier = Modifier.align(Alignment.BottomStart).padding(24.dp)
                )
            }
        }

        // Kanal bilgi bandi (+ yayin akisi)
        if (showBanner && panel == Panel.NONE) {
            vm.currentChannel?.let { ch ->
                val num = channels.indexOfFirst { it.id == ch.id }
                Column(
                    Modifier
                        .align(Alignment.BottomStart)
                        .padding(32.dp)
                        .widthIn(max = 900.dp)
                        .background(Color(0xEE121418), RoundedCornerShape(8.dp))
                        .padding(horizontal = 20.dp, vertical = 12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (num >= 0) {
                            Text("${num + 1}", color = Color(0xFF94A3B8), fontSize = 22.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.width(12.dp))
                        }
                        Text(ch.name, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                        if (vm.isFav(ch)) {
                            Spacer(Modifier.width(10.dp))
                            Text("★", color = Color(0xFFFFD54F), fontSize = 22.sp)
                        }
                    }
                    // Yayinin teknik bilgisi (cozunurluk, codec, fps, ses)
                    if (vm.mediaInfo.isNotEmpty()) {
                        Text(vm.mediaInfo, color = Color(0xFF94A3B8), fontSize = 15.sp, maxLines = 1)
                    }
                    EpgInfo(vm, ch)
                }
            }
        }

        // Kayit gostergesi
        if (vm.isRecording(vm.currentChannel)) {
            Text(
                "● REC", color = Color(0xFFFF5252), fontSize = 18.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(24.dp)
                    .background(Color(0xCC000000), RoundedCornerShape(6.dp))
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            )
        }

        // Yazilan kanal numarasi
        if (numBuf.isNotEmpty()) {
            Text(
                numBuf, color = Color.White, fontSize = 48.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(32.dp)
                    .background(Color(0xEE121418), RoundedCornerShape(10.dp))
                    .padding(horizontal = 24.dp, vertical = 8.dp)
            )
        }

        // Paneller
        when (panel) {
            Panel.MENU -> SidePanel(320) {
                PanelHeader("MENÜ")
                TvList(
                    rows = MENU_ITEMS.map { ListRow(it) },
                    selected = menuIdx,
                    modifier = Modifier.weight(1f),
                    onClick = { i -> menuIdx = i; menuAction(i) }
                )
                Hint("OK: Seç · ▶: Gruplar")
            }
            Panel.GROUPS -> SidePanel(420) {
                if (isTv) PanelHeader("KATEGORİLER")
                else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TvButton("◀ Menü", compact = true) { menuIdx = 0; panel = Panel.MENU }
                    TvButton("Ara", compact = true) { panel = Panel.NONE; vm.openScreen(Screen.SEARCH) }
                    PanelHeader("KATEGORİLER")
                }
                TvList(
                    rows = groups.map { ListRow(it, badge = if (it == vm.liveGroup) "•" else "") },
                    selected = gSel,
                    modifier = Modifier.weight(1f),
                    onClick = { i -> groupIdx = i; selectGroup(i) }
                )
                Hint(if (isTv) "OK: Kanallar · Uzun OK: grubu gizle · ◀: Menü · ▶: Kanallar" else "Gruba dokun: kanalları aç")
            }
            Panel.CHANNELS -> SidePanel(620) {
                if (isTv) PanelHeader(vm.liveGroup.uppercase())
                else {
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()).padding(bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        TvButton("◀ Gruplar", compact = true) { openGroups() }
                        TvButton("Ara", compact = true) { panel = Panel.NONE; vm.openScreen(Screen.SEARCH) }
                        TvButton("Menü", compact = true) { menuIdx = 0; panel = Panel.MENU }
                        TvButton("Seçenekler", compact = true) { openOptions() }
                    }
                    PanelHeader(vm.liveGroup.uppercase())
                }
                val rows = channels.mapIndexed { i, c ->
                    val playing = c.id == vm.currentChannel?.id
                    ListRow(
                        title = c.name,
                        sub = (i + 1).toString(),
                        logo = c.logo,
                        badge = (if (vm.isDead(c)) "✕" else "") + (if (vm.isFav(c)) "★" else "") + (if (playing) "▶" else "")
                    )
                }
                TvList(
                    rows = rows,
                    selected = cSel,
                    modifier = Modifier.weight(1f),
                    emptyText = if (vm.liveGroup == FAV_GROUP)
                        "Henüz favori yok.\nBir kanalın üzerinde OK tuşuna uzun basın."
                    else "Bu grupta kanal yok.",
                    onClick = { i -> channels.getOrNull(i)?.let { vm.playLive(it); panel = Panel.NONE } },
                    onLongClick = { i -> chanIdx = i; channels.getOrNull(i)?.let { toggleFav(it) } }
                )
                channels.getOrNull(cSel)?.let { sel ->
                    Box(Modifier.padding(top = 6.dp)) { EpgInfo(vm, sel, showProgress = false) }
                }
                Hint(if (isTv) "OK: İzle · Uzun OK: Favori ★ · ◀: Gruplar · ▶: Kapat" else "Dokun: İzle · Uzun bas: Favori ★ · 5 sn dokunulmazsa kapanır")
            }
            Panel.EPG -> {
                val planned = Recorder.scheduled
                val rows = epgList.map { e ->
                    val isPlanned = planned.any { it.start == e.start && it.title.endsWith(e.title) }
                    val live = e.start <= nowMs
                    ListRow(
                        title = e.title,
                        sub = hm(e.start) + "–" + hm(e.end) + if (live) " · şimdi" else "",
                        badge = if (isPlanned) "⏺" else ""
                    )
                }
                OptionsPanel(
                    rows.map { OptionItem(it) {} }, eSel,
                    "OK: Kaydet / planla · ◀: Geri",
                    title = "YAYIN AKIŞI · " + (vm.currentChannel?.name ?: ""),
                    onClick = { i -> epgAt(i) }
                )
            }
            Panel.OPTIONS -> OptionsPanel(
                options, oSel,
                "OK: Değiştir · ◀ / Geri: Kapat",
                onClick = { i -> optionAt(i) }
            )
            Panel.NONE -> if (touchBar) {
                // Dokunmatik arac cubugu
                Row(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 20.dp)
                        .background(Color(0xEE121418), RoundedCornerShape(12.dp))
                        .swallowTouches()
                        .horizontalScroll(rememberScrollState())
                        .padding(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TvButton("Kanallar") { touchBar = false; openChannels() }
                    TvButton("Gruplar") { touchBar = false; openGroups() }
                    TvButton("Ara") { touchBar = false; vm.openScreen(Screen.SEARCH) }
                    TvButton("Menü") { touchBar = false; menuIdx = 0; panel = Panel.MENU }
                    TvButton("Seçenekler") { touchBar = false; openOptions() }
                    TvButton("◀◀ Önceki") { vm.goPrevious() }
                }
            }
        }
    }
}

@Composable
private fun SidePanel(widthDp: Int, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .align(Alignment.CenterStart)
                .fillMaxHeight()
                .width(panelWidth(widthDp))
                .background(PanelBg)
                .swallowTouches()
                .padding(12.dp),
            content = content
        )
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, color = Color(0xFF94A3B8), fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
}
