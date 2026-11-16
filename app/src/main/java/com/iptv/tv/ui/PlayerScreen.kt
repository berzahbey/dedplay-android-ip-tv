package com.iptv.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
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
import kotlinx.coroutines.delay

private fun fmt(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%02d:%02d".format(m, sec)
}

/** Film / dizi bolumu oynatma ekrani: OK duraklat, sol/sag 10 sn atla. */
@Composable
fun PlayerScreen(vm: AppViewModel) {
    val player = vm.player
    val fr = remember { FocusRequester() }
    var showInfo by remember { mutableStateOf(true) }
    var pos by remember { mutableLongStateOf(0L) }
    var dur by remember { mutableLongStateOf(0L) }
    var hideAt by remember { mutableLongStateOf(System.currentTimeMillis() + 4000) }
    var showOptions by remember { mutableStateOf(false) }
    var optIdx by remember { mutableIntStateOf(0) }
    val options = playerOptions(vm, live = false)
    val oSel = optIdx.coerceIn(0, (options.size - 1).coerceAtLeast(0))

    LaunchedEffect(Unit) { runCatching { fr.requestFocus() } }
    LaunchedEffect(Unit) {
        while (true) {
            pos = player.currentPosition
            dur = player.duration.coerceAtLeast(0)
            showInfo = System.currentTimeMillis() < hideAt || !player.isPlaying
            delay(500)
        }
    }

    fun poke() { hideAt = System.currentTimeMillis() + 4000; showInfo = true }
    fun seek(delta: Long) {
        val target = (player.currentPosition + delta).coerceAtLeast(0)
        player.seekTo(if (player.duration > 0) target.coerceAtMost(player.duration) else target)
        poke()
    }

    BackHandler { if (showOptions) showOptions = false else vm.stopVod() }

    fun openOptions() { optIdx = 0; showOptions = true }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .tvKeys(
                onLeft = { if (showOptions) showOptions = false else seek(-10_000); true },
                onRight = { if (!showOptions) seek(10_000); true },
                onUp = {
                    if (showOptions) optIdx = (oSel - 1).coerceAtLeast(0) else seek(60_000)
                    true
                },
                onDown = {
                    if (showOptions) optIdx = (oSel + 1).coerceAtMost((options.size - 1).coerceAtLeast(0))
                    else seek(-60_000)
                    true
                },
                onOk = {
                    if (showOptions) {
                        options.getOrNull(oSel)?.action?.invoke()
                    } else {
                        player.playWhenReady = !player.playWhenReady
                        poke()
                    }
                },
                onLongOk = { if (!showOptions) openOptions() },
                onMenu = { if (showOptions) showOptions = false else openOptions(); true }
            )
            .focusRequester(fr)
            .focusable()
    ) {
        PlayerSurface(player, vm.resizeMode)
        if (vm.buffering) CircularProgressIndicator(Modifier.align(Alignment.Center), color = Accent)
        vm.playerMessage?.let {
            Text(it, color = Color.White, fontSize = 22.sp, modifier = Modifier.align(Alignment.Center))
        }
        if (showInfo) {
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color(0xEE121418))
                    .padding(24.dp)
            ) {
                Text(vm.vodTitle, color = Color.White, fontSize = 22.sp, maxLines = 1)
                if (dur > 0) {
                    LinearProgressIndicator(
                        progress = { (pos.toFloat() / dur).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        color = Accent
                    )
                    Text("${fmt(pos)} / ${fmt(dur)}", color = Color(0xFF94A3B8), fontSize = 14.sp)
                }
                Text(
                    "OK: Duraklat/Oynat · ◀▶: 10 sn · ▲▼: 1 dk · Uzun OK / Menü: Ses·Altyazı · Geri: Çık",
                    color = Color(0xFF94A3B8), fontSize = 13.sp
                )
            }
        }
        if (showOptions) {
            OptionsPanel(options, oSel, "OK: Değiştir · ◀ / Geri: Kapat")
        }
    }
}
