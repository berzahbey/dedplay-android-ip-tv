package com.iptv.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.C
import com.iptv.tv.AppViewModel
import com.iptv.tv.EpgEntry
import com.iptv.tv.Item
import com.iptv.tv.RESIZE_LABELS
import com.iptv.tv.Recorder
import com.iptv.tv.Screen
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Secenekler panelindeki bir satir ve OK'a basilinca yapilacak is. closes: is bitince panel kapansin. */
class OptionItem(val row: ListRow, val closes: Boolean = false, val action: () -> Unit)

/**
 * Oynatma sirasinda Menu tusuyla (veya menuden) acilan secenekler.
 * live = true ise canli TV'ye ozel "Onceki kanal" satiri da eklenir.
 */
fun playerOptions(vm: AppViewModel, live: Boolean, onEpg: () -> Unit = {}): List<OptionItem> {
    val list = ArrayList<OptionItem>()
    if (live) {
        list.add(OptionItem(ListRow("Önceki kanala dön", sub = vm.previousChannel?.name ?: "-"), closes = true) { vm.goPrevious() })
    }
    list.add(OptionItem(ListRow("Ses", sub = vm.trackSummary(C.TRACK_TYPE_AUDIO))) { vm.cycleTrack(C.TRACK_TYPE_AUDIO) })
    list.add(OptionItem(ListRow("Altyazı", sub = vm.trackSummary(C.TRACK_TYPE_TEXT))) { vm.cycleTrack(C.TRACK_TYPE_TEXT) })
    list.add(OptionItem(ListRow("Görüntü oranı", sub = RESIZE_LABELS[vm.resizeMode])) { vm.cycleResize() })
    if (live) {
        val ch = vm.currentChannel
        val rec = if (ch != null) Recorder.runningFor(vm.recordUrl(ch)) else null
        if (rec != null) {
            list.add(OptionItem(ListRow("■ Kaydı durdur", sub = "${rec.bytes / (1024 * 1024)} MB", badge = "●")) { vm.toggleRecordCurrent() })
        } else {
            list.add(OptionItem(ListRow("● Kanalı kaydet", sub = "durdurana kadar")) { vm.toggleRecordCurrent() })
        }
        val cur = vm.nowNext(ch).first
        if (cur != null && rec == null) {
            list.add(OptionItem(ListRow("● Bu programı kaydet", sub = "${cur.title} · bitiş ${hm(cur.end)}")) { vm.recordCurrentProgram() })
        }
        list.add(OptionItem(ListRow("Yayın akışı · kayıt planla", sub = if (vm.epg[ch?.id ?: ""].isNullOrEmpty()) "akış yok" else "")) { onEpg() })
        list.add(OptionItem(ListRow("Yayın akışı saat farkı", sub = vm.epgOffsetLabel(ch))) { vm.cycleEpgOffset() })
        list.add(OptionItem(ListRow("Çoklu ekran (2'li / 4'lü)"), closes = true) { vm.openScreen(Screen.MULTI) })
    }
    list.add(OptionItem(ListRow("Uyku zamanlayıcı", sub = vm.sleepLabel())) { vm.cycleSleep() })
    return list
}

/** Sag tarafta acilan secenekler paneli. */
@Composable
fun OptionsPanel(
    items: List<OptionItem>,
    selected: Int,
    hint: String,
    title: String = "SEÇENEKLER",
    onClick: ((Int) -> Unit)? = null
) {
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .width(panelWidth(560))
                .background(PanelBg)
                .swallowTouches()
                .padding(12.dp)
        ) {
            PanelHeader(title)
            TvList(
                items.map { it.row }, selected, Modifier.weight(1f),
                emptyText = "Bu kanal için yayın akışı yok.",
                onClick = onClick
            )
            Text(hint, color = Color(0xFF94A3B8), fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

private val hmFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

fun hm(ms: Long): String = hmFormat.format(Date(ms))

private fun epgLine(prefix: String, e: EpgEntry) = "$prefix ${hm(e.start)}–${hm(e.end)}  ${e.title}"

/** Kanalin simdiki / siradaki programi (EPG varsa). */
@Composable
fun EpgInfo(vm: AppViewModel, item: Item?, showProgress: Boolean = true) {
    val (cur, next) = vm.nowNext(item)
    if (cur == null && next == null) return
    Column(Modifier.fillMaxWidth()) {
        if (cur != null) {
            Text(
                epgLine("Şimdi", cur), color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            if (showProgress && cur.end > cur.start) {
                val p = ((System.currentTimeMillis() - cur.start).toFloat() / (cur.end - cur.start)).coerceIn(0f, 1f)
                LinearProgressIndicator(
                    progress = { p },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    color = Accent
                )
            }
        }
        if (next != null) {
            Text(
                epgLine("Sonra", next), color = Color(0xFF94A3B8), fontSize = 15.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** Panelin bos yerine dokunulunca arkadaki ekrana gecmesin. */
fun Modifier.swallowTouches(): Modifier = this.pointerInput(Unit) { detectTapGestures { } }

/**
 * Yedekten geri yukleme: once yedegi bilinen yerlerde arar; bulamazsa (orn. uygulama
 * yeniden kurulduysa Android 13+ eski yedegi kendiliginden okutmaz) dosya seciciyi acar.
 * Donen fonksiyon cagrildiginda islem baslar; sonuc mesaji onResult ile gelir.
 */
@Composable
fun rememberBackupRestorer(vm: AppViewModel, onResult: (String) -> Unit): () -> Unit {
    val result by rememberUpdatedState(onResult)
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) result(vm.importBackupFromUri(uri))
    }
    return {
        val auto = vm.importBackup()
        if (auto != null) {
            result(auto)
        } else {
            try {
                result("Yedek bulunamadı, iptv_backup.json dosyasını seçin")
                picker.launch(arrayOf("application/json", "text/plain", "application/octet-stream", "*/*"))
            } catch (e: ActivityNotFoundException) {
                result("Yedek bulunamadı ve bu cihazda dosya seçici yok")
            }
        }
    }
}
