package com.iptv.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.iptv.tv.AppViewModel
import com.iptv.tv.BUFFER_LABELS
import com.iptv.tv.RESIZE_LABELS
import com.iptv.tv.START_LABELS
import com.iptv.tv.Screen

@Composable
fun SettingsScreen(vm: AppViewModel) {
    var sel by remember { mutableIntStateOf(0) }
    var confirm by remember { mutableIntStateOf(-1) }
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { fr.requestFocus() } }
    LaunchedEffect(Unit) { vm.loadAccount() }
    BackHandler { vm.openScreen(Screen.LIVE) }

    val src = vm.activeSource()
    val restoreBackup = rememberBackupRestorer(vm) { vm.toast(it) }

    /** OK'a iki kez basinca calisan (onayli) islem. */
    fun confirmed(key: Int, msg: String, action: () -> Unit) {
        if (confirm == key) { confirm = -1; action() } else { confirm = key; vm.toast(msg) }
    }

    // Satir ve islem ayni yerde: siralama degisse de karismaz
    val items: List<OptionItem> = listOf(
        OptionItem(ListRow("Kaynak ekle (M3U / Xtream)")) { vm.editSourceId = null; vm.openScreen(Screen.ADD_SOURCE) },
        OptionItem(ListRow("Aktif kaynağı düzenle", sub = "sunucu / kullanıcı adı / şifre")) {
            if (src == null) vm.toast("Kaynak yok") else { vm.editSourceId = src.id; vm.openScreen(Screen.ADD_SOURCE) }
        },
        OptionItem(ListRow("Aktif kaynak (değiştirmek için OK)", sub = src?.name ?: "-")) { vm.cycleSource() },
        OptionItem(ListRow("Hesap bilgisi (yenilemek için OK)", sub = vm.accountLabel())) { vm.loadAccount() },
        OptionItem(ListRow("Listeyi yenile", sub = if (vm.loading) "yükleniyor…" else "otomatik: 7 günde bir")) {
            vm.refresh(); vm.toast("Liste yenileniyor…")
        },
        OptionItem(ListRow("Yayın formatı (Xtream)", sub = vm.streamExt)) { vm.toggleExt() },
        OptionItem(ListRow("Görüntü oranı", sub = RESIZE_LABELS[vm.resizeMode])) { vm.cycleResize() },
        OptionItem(ListRow("Tampon (arabellek)", sub = BUFFER_LABELS[vm.bufferMode])) { vm.cycleBuffer() },
        OptionItem(ListRow("Açılışta", sub = START_LABELS[vm.startMode])) { vm.cycleStartMode() },
        OptionItem(ListRow("Uyku zamanlayıcı", sub = vm.sleepLabel())) { vm.cycleSleep() },
        OptionItem(ListRow("Kayıt yeri", sub = vm.recStorageLabel())) { vm.cycleRecStorage() },
        OptionItem(ListRow("Kayıtlar")) { vm.openScreen(Screen.RECORDINGS) },
        OptionItem(ListRow("Canlı TV gruplarını yönet")) { vm.manageKind = "live"; vm.openScreen(Screen.MANAGE_GROUPS) },
        OptionItem(ListRow("Film gruplarını yönet")) { vm.manageKind = "movie"; vm.openScreen(Screen.MANAGE_GROUPS) },
        OptionItem(ListRow("Dizi gruplarını yönet")) { vm.manageKind = "series"; vm.openScreen(Screen.MANAGE_GROUPS) },
        OptionItem(ListRow("Yedek oluştur (Download klasörü)", sub = "iptv_backup.json")) {
            vm.toast(vm.exportBackup())
        },
        OptionItem(ListRow("Yedekten geri yükle (Download klasörü)", sub = if (confirm == 12) "Yüklemek için tekrar OK" else "iptv_backup.json")) {
            confirmed(12, "Geri yüklemek için tekrar OK'a basın") { restoreBackup() }
        },
        OptionItem(ListRow("Favorileri temizle", sub = if (confirm == 13) "Onay için tekrar OK" else vm.favorites.size.toString())) {
            confirmed(13, "Onaylamak için tekrar OK'a basın") { vm.clearFavorites(); vm.toast("Favoriler temizlendi") }
        },
        OptionItem(ListRow("Aktif kaynağı sil", sub = if (confirm == 14) "Onay için tekrar OK" else "")) {
            confirmed(14, "Silmek için tekrar OK'a basın") { vm.deleteActive() }
        },
        OptionItem(ListRow("Uygulamayı yeniden başlat")) { vm.restartApp() }
    )
    val rows = items.map { it.row }

    Box(
        Modifier
            .fillMaxSize()
            .background(ScreenBg)
            .tvKeys(
                onUp = { sel = (sel - 1).coerceAtLeast(0); confirm = -1; true },
                onDown = { sel = (sel + 1).coerceAtMost(rows.size - 1); confirm = -1; true },
                onOk = { items.getOrNull(sel)?.action?.invoke() }
            )
            .focusRequester(fr)
            .focusable()
    ) {
        Column(Modifier.fillMaxSize().padding(40.dp)) {
            Text("AYARLAR", color = Accent, fontSize = 26.sp, modifier = Modifier.padding(bottom = 16.dp))
            TvList(
                rows, sel, Modifier.weight(1f).fillMaxWidth(),
                onClick = { i -> sel = i; items.getOrNull(i)?.action?.invoke() }
            )
            Text(
                "▲▼: Gezin · OK: Seç · Geri: Canlı TV", color = Color(0xFF94A3B8), fontSize = 13.sp,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

/** Canli / film / dizi gruplarini gosterip gizlemek icin ekran. */
@Composable
fun ManageGroupsScreen(vm: AppViewModel) {
    val kind = vm.manageKind
    val title = when (kind) {
        "movie" -> "FİLM GRUPLARI"
        "series" -> "DİZİ GRUPLARI"
        else -> "CANLI TV GRUPLARI"
    }
    var sel by remember { mutableIntStateOf(0) }
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { fr.requestFocus() } }
    BackHandler { vm.openScreen(Screen.SETTINGS) }

    val groups = vm.groups(kind, includeHidden = true)
    val cur = sel.coerceIn(0, (groups.size - 1).coerceAtLeast(0))
    val rows = groups.map {
        val h = vm.isHidden(kind, it)
        ListRow(it, sub = if (h) "GİZLİ" else "görünür", badge = if (h) "✗" else "✓")
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(ScreenBg)
            .tvKeys(
                onUp = { sel = (cur - 1).coerceAtLeast(0); true },
                onDown = { sel = (cur + 1).coerceAtMost((groups.size - 1).coerceAtLeast(0)); true },
                onOk = {
                    groups.getOrNull(cur)?.let {
                        val nowHidden = vm.toggleHidden(kind, it)
                        vm.toast(if (nowHidden) "Gizlendi: $it" else "Gösteriliyor: $it")
                    }
                }
            )
            .focusRequester(fr)
            .focusable()
    ) {
        Column(Modifier.fillMaxSize().padding(40.dp)) {
            Text(title, color = Accent, fontSize = 26.sp, modifier = Modifier.padding(bottom = 4.dp))
            Text(
                "OK: Grubu göster / gizle", color = Color(0xFF94A3B8), fontSize = 14.sp,
                modifier = Modifier.padding(bottom = 12.dp)
            )
            TvList(
                rows, cur, Modifier.weight(1f).fillMaxWidth(),
                emptyText = "Liste henüz yüklenmedi veya bu bölümde grup yok.",
                onClick = { i ->
                    sel = i
                    groups.getOrNull(i)?.let {
                        val nowHidden = vm.toggleHidden(kind, it)
                        vm.toast(if (nowHidden) "Gizlendi: $it" else "Gösteriliyor: $it")
                    }
                }
            )
        }
    }
}
