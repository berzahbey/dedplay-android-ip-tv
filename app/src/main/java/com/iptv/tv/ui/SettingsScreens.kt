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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
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
import com.iptv.tv.BACK_LABELS
import com.iptv.tv.REFRESH_LABELS
import com.iptv.tv.Screen

@Composable
fun SettingsScreen(vm: AppViewModel) {
    var sel by remember { mutableIntStateOf(0) }
    var confirm by remember { mutableIntStateOf(-1) }
    val fr = remember { FocusRequester() }
    RegisterKeyFocus(fr)
    LaunchedEffect(Unit) { runCatching { fr.requestFocus() } }
    LaunchedEffect(Unit) { vm.loadAccount() }
    BackHandler { vm.openScreen(Screen.LIVE) }

    // Aktif kaynak secici (liste): acikken Geri sadece listeyi kapatir
    var srcPicker by remember { mutableStateOf(false) }
    var srcSel by remember { mutableIntStateOf(0) }
    var delConfirm by remember { mutableStateOf<String?>(null) }
    BackHandler(enabled = srcPicker) { srcPicker = false }

    val src = vm.activeSource()
    val ctx = LocalContext.current
    val appVersion = remember {
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "?"
    }
    val restoreBackup = rememberBackupRestorer(vm) { vm.toast(it) }

    /** OK'a iki kez basinca calisan (onayli) islem. */
    fun confirmed(key: Int, msg: String, action: () -> Unit) {
        if (confirm == key) { confirm = -1; action() } else { confirm = key; vm.toast(msg) }
    }

    // Satir ve islem ayni yerde: siralama degisse de karismaz
    // M3U kaynaginda: calismayan kanal taramasi
    val scanItems: List<OptionItem> = if (!vm.isM3uSource()) emptyList() else listOf(
        OptionItem(ListRow("Çalışmayan kanalları bul (M3U)",
            sub = vm.scanProgress?.let { "Taranıyor: $it · durdurmak için OK" } ?: "${vm.deadCount()} çalışmıyor · taramak için OK")) {
            vm.toggleDeadScan()
        },
        OptionItem(ListRow("Çalışmayan kanalları gizle", sub = if (vm.hideDead) "Açık" else "Kapalı")) { vm.toggleHideDead() }
    )

    // Guncelleme satiri: yeni surum varsa listenin en ustunde, yoksa en altta
    val updateRow = OptionItem(
            ListRow(
                if (vm.updateInfo != null) "⬆ Güncelle" else "Güncellemeleri denetle",
                sub = vm.updateStatus
                    ?: vm.updateInfo?.let { "Yeni sürüm: v${it.version} · yüklemek için OK" }
                    ?: "otomatik: günde bir",
                badge = if (vm.updateInfo != null) "●" else ""
            )
        ) { if (vm.updateInfo != null) vm.startUpdate() else vm.checkUpdate(manual = true) }
    val updateTop = vm.updateInfo != null

    val items: List<OptionItem> = (if (updateTop) listOf(updateRow) else emptyList()) + listOf(
        OptionItem(ListRow("Kaynak ekle (M3U / Xtream)")) { vm.editSourceId = null; vm.openScreen(Screen.ADD_SOURCE) },
        OptionItem(ListRow("Aktif kaynağı düzenle", sub = "sunucu / kullanıcı adı / şifre")) {
            if (src == null) vm.toast("Kaynak yok") else { vm.editSourceId = src.id; vm.openScreen(Screen.ADD_SOURCE) }
        },
        OptionItem(ListRow("Aktif kaynak (seçmek için OK)", sub = src?.name ?: "-")) {
            if (vm.sources.size < 2) vm.toast("Başka kaynak yok")
            else {
                srcSel = vm.sources.indexOfFirst { it.id == src?.id }.coerceAtLeast(0)
                srcPicker = true
            }
        },
        OptionItem(ListRow("Hesap bilgisi (yenilemek için OK)", sub = vm.accountLabel())) { vm.loadAccount() },
        OptionItem(ListRow("Listeyi şimdi yenile", sub = if (vm.loading) "yükleniyor…" else "son güncelleme: ${vm.libraryAgeLabel()}")) {
            vm.refresh(); vm.toast("Liste yenileniyor…")
        },
        OptionItem(ListRow("Otomatik liste yenileme", sub = REFRESH_LABELS[vm.refreshMode])) { vm.cycleRefreshMode() },
    ) + scanItems + listOf(
        OptionItem(ListRow("Yayın formatı (Xtream)", sub = vm.streamExt)) { vm.toggleExt() },
        OptionItem(ListRow("Görüntü oranı", sub = RESIZE_LABELS[vm.resizeMode])) { vm.cycleResize() },
        OptionItem(ListRow("Tema rengi", sub = ThemeState.current.name)) { vm.cycleTheme() },
        OptionItem(ListRow("Tampon (arabellek)", sub = BUFFER_LABELS[vm.bufferMode])) { vm.cycleBuffer() },
        OptionItem(ListRow("Açılışta", sub = START_LABELS[vm.startMode])) { vm.cycleStartMode() },
        OptionItem(ListRow("Geri tuşu", sub = BACK_LABELS[vm.backExit])) { vm.cycleBackExit() },
        OptionItem(ListRow("Uyku zamanlayıcı", sub = vm.sleepLabel())) { vm.cycleSleep() },
        OptionItem(ListRow("Kayıt yeri", sub = vm.recStorageLabel())) { vm.cycleRecStorage() },
        OptionItem(ListRow("Kayıtlar")) { vm.openScreen(Screen.RECORDINGS) },
        OptionItem(ListRow("Canlı TV gruplarını yönet")) { vm.manageKind = "live"; vm.openScreen(Screen.MANAGE_GROUPS) },
        OptionItem(ListRow("Film gruplarını yönet")) { vm.manageKind = "movie"; vm.openScreen(Screen.MANAGE_GROUPS) },
        OptionItem(ListRow("Dizi gruplarını yönet")) { vm.manageKind = "series"; vm.openScreen(Screen.MANAGE_GROUPS) },
        OptionItem(ListRow("Gizli kanalları geri aç", sub = if (confirm == 15) "Onay için tekrar OK" else "${vm.hiddenChannelCount()} kanal gizli")) {
            if (vm.hiddenChannelCount() == 0) vm.toast("Gizli kanal yok")
            else confirmed(15, "Hepsini geri açmak için tekrar OK'a basın") { vm.clearHiddenChannels(); vm.toast("Gizli kanallar geri açıldı") }
        },
        OptionItem(ListRow("Yedek oluştur (Download klasörü)", sub = "iptv_backup.json")) {
            vm.toast(vm.exportBackup())
        },
        OptionItem(ListRow("Yedekten geri yükle (Download klasörü)", sub = if (confirm == 12) "Yüklemek için tekrar OK" else "iptv_backup.json")) {
            confirmed(12, "Geri yüklemek için tekrar OK'a basın") { restoreBackup() }
        },
        OptionItem(ListRow("Favorileri temizle", sub = if (confirm == 13) "Onay için tekrar OK" else vm.favorites.size.toString())) {
            confirmed(13, "Onaylamak için tekrar OK'a basın") { vm.clearFavorites(); vm.toast("Favoriler temizlendi") }
        },
        OptionItem(ListRow("Aktif kaynağı sil", sub = if (confirm == 14) "Onay için tekrar OK" else (src?.name ?: "-") + " · başka liste: Aktif kaynak > uzun bas")) {
            confirmed(14, "Silmek için tekrar OK'a basın") { vm.deleteActive() }
        },
        OptionItem(ListRow("Uygulamayı yeniden başlat")) { vm.restartApp() },
        OptionItem(ListRow("Uygulama sürümü", sub = "v$appVersion")) { vm.toast("dedplay v$appVersion") }
    ) + (if (updateTop) emptyList() else listOf(updateRow))
    val rows = items.map { it.row }

    /** Kaynak listesinde uzun OK / basili tut: iki kez yapilinca siler. */
    fun deleteAt(i: Int) {
        val s = vm.sources.getOrNull(i) ?: return
        if (delConfirm == s.id) {
            delConfirm = null
            vm.deleteSource(s.id)
            vm.toast("Silindi: ${s.name}")
            srcSel = srcSel.coerceIn(0, (vm.sources.size - 1).coerceAtLeast(0))
            if (vm.sources.isEmpty()) srcPicker = false
        } else {
            delConfirm = s.id
            vm.toast("\"${s.name}\" silinsin mi? Onay için tekrar uzun basın")
        }
    }

    val sourceItems = vm.sources.map { s ->
        val sub = if (delConfirm == s.id) "Silmek için tekrar uzun bas" else s.type.uppercase()
        OptionItem(ListRow(s.name, sub = sub, badge = if (s.id == src?.id) "▶" else ""))  {
            if (s.id != vm.activeId) {
                vm.setActive(s.id)
                vm.toast("Kaynak: ${s.name}")
            }
            srcPicker = false
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(ScreenBg)
            .tvKeys(
                onUp = {
                    if (srcPicker) { srcSel = (srcSel - 1).coerceAtLeast(0); delConfirm = null }
                    else { sel = (sel - 1).coerceAtLeast(0); confirm = -1 }
                    true
                },
                onDown = {
                    if (srcPicker) { srcSel = (srcSel + 1).coerceAtMost((sourceItems.size - 1).coerceAtLeast(0)); delConfirm = null }
                    else { sel = (sel + 1).coerceAtMost(rows.size - 1); confirm = -1 }
                    true
                },
                onLeft = { if (srcPicker) { srcPicker = false; true } else false },
                onOk = {
                    if (srcPicker) sourceItems.getOrNull(srcSel)?.action?.invoke()
                    else items.getOrNull(sel)?.action?.invoke()
                },
                onLongOk = { if (srcPicker) deleteAt(srcSel) }
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
        if (srcPicker) {
            OptionsPanel(
                sourceItems, srcSel,
                "OK / dokun: Seç · Uzun OK / basılı tut: Sil · Geri: Kapat",
                title = "KAYNAK SEÇ",
                onClick = { i -> srcSel = i; delConfirm = null; sourceItems.getOrNull(i)?.action?.invoke() },
                onLongClick = { i -> if (srcSel != i) delConfirm = null; srcSel = i; deleteAt(i) }
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
    RegisterKeyFocus(fr)
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
