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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.iptv.tv.AppViewModel
import com.iptv.tv.RecFile
import com.iptv.tv.Recorder
import com.iptv.tv.Screen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val dayFormat = SimpleDateFormat("dd.MM HH:mm", Locale.getDefault())

private fun mb(bytes: Long): String {
    val m = bytes / (1024.0 * 1024.0)
    return if (m >= 1024) "%.1f GB".format(m / 1024) else "%.0f MB".format(m)
}

/** Kayitlar: suren kayitlar, planli kayitlar ve kayitli dosyalar. */
@Composable
fun RecordingsScreen(vm: AppViewModel) {
    var sel by remember { mutableIntStateOf(0) }
    var confirm by remember { mutableIntStateOf(-1) }
    var files by remember { mutableStateOf<List<RecFile>>(emptyList()) }
    var reload by remember { mutableIntStateOf(0) }
    val fr = remember { FocusRequester() }

    LaunchedEffect(Unit) { runCatching { fr.requestFocus() } }
    // Dosya listesi: acilista, bir kayit bitince/durunca ve 5 sn'de bir (boyutlar guncellensin)
    LaunchedEffect(reload, Recorder.running.size) {
        while (true) {
            files = withContext(Dispatchers.IO) { vm.recordingFiles() }
            delay(5_000)
        }
    }
    BackHandler { vm.openScreen(Screen.LIVE) }

    val running = Recorder.running.values.toList()
    val planned = Recorder.scheduled.sortedBy { it.start }

    val items = ArrayList<OptionItem>()
    running.forEach { r ->
        val key = items.size
        items.add(
            OptionItem(
                ListRow(
                    "● " + r.job.title.ifEmpty { r.job.channel },
                    sub = (r.error ?: "kaydediliyor · ${mb(r.bytes)}") +
                        (if (r.job.end > 0) " · bitiş " + hm(r.job.end) else "") +
                        (if (confirm == key) " · Durdurmak için tekrar OK" else ""),
                    badge = "REC"
                )
            ) {
                if (confirm == key) { confirm = -1; vm.stopRecording(r.job.id); vm.toast("Kayıt durduruldu") }
                else { confirm = key; vm.toast("Durdurmak için tekrar OK'a basın") }
            }
        )
    }
    planned.forEach { j ->
        val key = items.size
        items.add(
            OptionItem(
                ListRow(
                    "⏺ " + j.title,
                    sub = "Planlı: " + dayFormat.format(Date(j.start)) + "–" + hm(j.end) +
                        (if (confirm == key) " · İptal için tekrar OK" else "")
                )
            ) {
                if (confirm == key) { confirm = -1; vm.stopRecording(j.id); vm.toast("Plan iptal edildi") }
                else { confirm = key; vm.toast("İptal etmek için tekrar OK'a basın") }
            }
        )
    }
    val fileStart = items.size
    files.forEach { f ->
        items.add(
            OptionItem(
                ListRow(
                    f.file.nameWithoutExtension,
                    sub = mb(f.file.length()) + " · " + f.volume + " · " + dayFormat.format(Date(f.file.lastModified())) +
                        (if (confirm == items.size) " · Silmek için tekrar uzun OK" else "")
                )
            ) { vm.playRecording(f.file) }
        )
    }
    val cur = sel.coerceIn(0, (items.size - 1).coerceAtLeast(0))

    Box(
        Modifier
            .fillMaxSize()
            .background(ScreenBg)
            .tvKeys(
                onUp = { sel = (cur - 1).coerceAtLeast(0); confirm = -1; true },
                onDown = { sel = (cur + 1).coerceAtMost((items.size - 1).coerceAtLeast(0)); confirm = -1; true },
                onOk = { items.getOrNull(cur)?.action?.invoke() },
                onLongOk = {
                    // Uzun OK: dosyayi sil (iki kez onay)
                    val f = files.getOrNull(cur - fileStart)
                    if (cur >= fileStart && f != null) {
                        if (confirm == cur) {
                            confirm = -1
                            if (vm.deleteRecording(f.file)) reload++
                        } else {
                            confirm = cur
                            vm.toast("Silmek için tekrar uzun OK'a basın")
                        }
                    }
                }
            )
            .focusRequester(fr)
            .focusable()
    ) {
        Column(Modifier.fillMaxSize().padding(40.dp)) {
            Text("KAYITLAR", color = Accent, fontSize = 26.sp, modifier = Modifier.padding(bottom = 4.dp))
            Text(
                "Kayıt yeri: " + vm.recStorageLabel(), color = Color(0xFF94A3B8), fontSize = 14.sp,
                modifier = Modifier.padding(bottom = 12.dp)
            )
            TvList(
                items.map { it.row }, cur, Modifier.weight(1f).fillMaxWidth(),
                emptyText = "Henüz kayıt yok.\nCanlı TV'de ▶ (Seçenekler) > Kanalı kaydet veya Yayın akışı ile kayıt yapabilirsiniz."
            )
            Text(
                "OK: İzle / durdur / iptal · Uzun OK: Kaydı sil · Geri: Canlı TV",
                color = Color(0xFF94A3B8), fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}
