package com.iptv.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.iptv.tv.AppViewModel
import com.iptv.tv.Screen

/** Filmler (kind = "movie") ve Diziler (kind = "series") ekrani. */
@Composable
fun BrowseScreen(vm: AppViewModel, kind: String) {
    val isSeries = kind == "series"
    val title = if (isSeries) "DİZİLER" else "FİLMLER"
    val backScreen = if (isSeries) Screen.SERIES else Screen.MOVIES

    var col by remember { mutableIntStateOf(if (isSeries && vm.episodes != null) 1 else 0) }
    var gIdx by remember { mutableIntStateOf(0) }
    var iIdx by remember { mutableIntStateOf(0) }
    val fr = remember { FocusRequester() }
    RegisterKeyFocus(fr)
    LaunchedEffect(Unit) { runCatching { fr.requestFocus() } }

    val groups = vm.groups(kind)
    val gSel = gIdx.coerceIn(0, (groups.size - 1).coerceAtLeast(0))
    val group = groups.getOrNull(gSel)
    val items = if (group != null) vm.itemsIn(kind, group) else emptyList()
    val eps = vm.episodes
    val listSize = if (isSeries && eps != null) eps.size else items.size
    val iSel = iIdx.coerceIn(0, (listSize - 1).coerceAtLeast(0))

    BackHandler {
        if (isSeries && vm.episodes != null) { vm.closeEpisodes(); iIdx = 0 }
        else vm.openScreen(Screen.LIVE)
    }

    /** Sagdaki listede i. ogeyi ac (film oynat / dizi bolumlerini getir / bolumu oynat). */
    fun openAt(i: Int) {
        if (isSeries && eps != null) {
            eps.getOrNull(i)?.let { vm.playVod("${vm.episodesTitle} · ${it.title}", it.url, backScreen) }
        } else {
            items.getOrNull(i)?.let {
                if (isSeries) vm.openSeries(it) else vm.playVod(it.name, it.url, backScreen)
                if (isSeries) iIdx = 0
            }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(ScreenBg)
            .tvKeys(
                onUp = {
                    if (col == 0) gIdx = (gSel - 1).coerceAtLeast(0) else iIdx = (iSel - 1).coerceAtLeast(0)
                    true
                },
                onDown = {
                    if (col == 0) gIdx = (gSel + 1).coerceAtMost((groups.size - 1).coerceAtLeast(0))
                    else iIdx = (iSel + 1).coerceAtMost((listSize - 1).coerceAtLeast(0))
                    true
                },
                onLeft = {
                    if (col == 1) {
                        if (isSeries && vm.episodes != null) vm.closeEpisodes()
                        col = 0
                    }
                    true
                },
                onRight = {
                    if (col == 0) { col = 1; iIdx = 0 }
                    true
                },
                onOk = {
                    if (col == 0) {
                        col = 1; iIdx = 0
                    } else {
                        openAt(iSel)
                    }
                },
                onLongOk = {
                    if (col == 0 && group != null) {
                        vm.toggleHidden(kind, group)
                        vm.toast("Gizlendi: $group  (geri açmak: Ayarlar > Grupları yönet)")
                    }
                }
            )
            .focusRequester(fr)
            .focusable()
    ) {
        Column(Modifier.fillMaxSize().padding(24.dp)) {
            Text(title, color = Accent, fontSize = 24.sp, modifier = Modifier.padding(bottom = 12.dp))
            Row(Modifier.weight(1f)) {
                Column(Modifier.width(380.dp).fillMaxHeight()) {
                    PanelHeader("KATEGORİLER")
                    TvList(
                        rows = groups.map { ListRow(it) },
                        selected = if (col == 0) gSel else -1,
                        modifier = Modifier.weight(1f),
                        emptyText = if (vm.library == null) "Liste yükleniyor…" else "Bu bölümde içerik yok.",
                        onClick = { i ->
                            if (isSeries && vm.episodes != null) vm.closeEpisodes()
                            gIdx = i; col = 1; iIdx = 0
                        },
                        onLongClick = { i ->
                            groups.getOrNull(i)?.let {
                                vm.toggleHidden(kind, it)
                                vm.toast("Gizlendi: $it  (geri açmak: Ayarlar > Grupları yönet)")
                            }
                        }
                    )
                }
                Spacer(Modifier.width(20.dp))
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    if (isSeries && eps != null) {
                        PanelHeader(vm.episodesTitle.uppercase())
                        val rows = remember(eps) { eps.map { ListRow("S${it.season} · ${it.title}") } }
                        TvList(
                            rows, if (col == 1) iSel else -1, Modifier.weight(1f),
                            onClick = { i -> col = 1; iIdx = i; openAt(i) }
                        )
                    } else {
                        PanelHeader(group?.uppercase() ?: "")
                        val rows = remember(items) { items.map { ListRow(it.name, logo = it.logo) } }
                        TvList(
                            rows, if (col == 1) iSel else -1, Modifier.weight(1f), rowHeight = 64.dp,
                            emptyText = if (vm.episodesLoading) "Bölümler yükleniyor…" else "",
                            onClick = { i -> col = 1; iIdx = i; openAt(i) }
                        )
                    }
                }
            }
            Text(
                "▶/OK: Listeye geç · ◀: Gruplara dön · Uzun OK (grup üstünde): gizle · Geri: Canlı TV",
                color = Color(0xFF94A3B8), fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp)
            )
        }
        if (vm.episodesLoading) {
            Text("Yükleniyor…", color = Color.White, fontSize = 20.sp, modifier = Modifier.align(Alignment.Center))
        }
    }
}
