package com.iptv.tv.ui

import androidx.activity.compose.BackHandler
import kotlin.math.roundToInt
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.DisposableEffect
import android.view.WindowManager
import android.provider.Settings
import android.media.AudioManager
import android.content.Context
import android.app.Activity
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
import androidx.compose.runtime.key
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
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
    RegisterKeyFocus(fr)
    var showInfo by remember { mutableStateOf(true) }
    var pos by remember { mutableLongStateOf(0L) }
    var dur by remember { mutableLongStateOf(0L) }
    var hideAt by remember { mutableLongStateOf(System.currentTimeMillis() + 4000) }
    var showOptions by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf(true) }
    // Kumandada basilan tusa karsilik gelen ekran dugmesi kisa sure yanar
    var flash by remember { mutableStateOf("") }
    LaunchedEffect(flash) { if (flash.isNotEmpty()) { delay(600); flash = "" } }
    var optIdx by remember { mutableIntStateOf(0) }
    val options = playerOptions(vm, live = false)
    val oSel = optIdx.coerceIn(0, (options.size - 1).coerceAtLeast(0))

    LaunchedEffect(Unit) { runCatching { fr.requestFocus() } }
    LaunchedEffect(Unit) {
        while (true) {
            pos = player.currentPosition
            dur = player.duration.coerceAtLeast(0)
            playing = player.playWhenReady
            showInfo = System.currentTimeMillis() < hideAt || !player.isPlaying
            delay(500)
        }
    }

    fun poke() { hideAt = System.currentTimeMillis() + 4000; showInfo = true }
    fun seek(delta: Long) {
        val target = (player.currentPosition + delta).coerceAtLeast(0)
        player.seekTo(if (player.duration > 0) target.coerceAtMost(player.duration) else target)
        flash = when {
            delta <= -60_000 -> "b60"; delta < 0 -> "b10"; delta >= 60_000 -> "f60"; else -> "f10"
        }
        poke()
    }
    fun togglePlay() {
        player.playWhenReady = !player.playWhenReady
        playing = player.playWhenReady
        flash = "pp"
        poke()
    }

    BackHandler { if (showOptions) showOptions = false else vm.stopVod() }

    fun openOptions() { optIdx = 0; showOptions = true }

    // Secenek listesi islem yoksa kendiliginden kapanir, film devam eder
    // (her tus / dokunma sureyi bastan baslatir): TV'de 7 sn, dokunmatikte 8 sn
    val isTv = rememberIsTv()
    ResetBrightnessOnExit()

    var touchTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(showOptions, optIdx, touchTick) {
        if (showOptions) { delay(if (isTv) 7000 else 8000); showOptions = false }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onPreviewKeyEvent { touchTick++; false }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) { awaitPointerEvent(PointerEventPass.Initial); touchTick++ }
                }
            }
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
                    } else togglePlay()
                },
                onLongOk = { if (!showOptions) openOptions() },
                onMenu = { if (showOptions) showOptions = false else openOptions(); true },
                onChannelUp = { seek(30_000); true },
                onChannelDown = { seek(-30_000); true },
                onPlayPause = { togglePlay(); true }
            )
            .touchGestures(
                // Dokun: bilgi bandi gizliyse goster, gorunuyorsa duraklat/oynat
                onTap = {
                    if (showOptions) showOptions = false
                    else if (!showInfo) poke()
                    else togglePlay()
                },
                onLongPress = { if (!showOptions) openOptions() },
                // Yukari kaydir: sonraki film / bolum, asagi kaydir: onceki (canli TV'deki kanal degistirme gibi)
                onSwipeUp = { if (!showOptions) vm.nextVod(1) },
                onSwipeDown = { if (!showOptions) vm.nextVod(-1) },
                // Cift dokun: sol taraf 10 sn geri, sag taraf 10 sn ileri
                onDoubleTap = { x -> if (!showOptions) seek(if (x < 0.5f) -10_000 else 10_000) }
            )
            .focusRequester(fr)
            .focusable()
    ) {
        // Doldur / Uzat: TextureView (her cihazda olcege uyar; TV'lerde SurfaceView bunu yok sayabiliyor).
        // Sigdir: SurfaceView (daha verimli). Yuzey turu degisince gorunum yeniden kurulur.
        val scaled = vm.resizeMode != 0
        key(scaled) { PlayerSurface(player, vm.resizeMode, clipToTile = scaled) }
        if (vm.buffering) CircularProgressIndicator(Modifier.align(Alignment.Center), color = Accent)
        vm.playerMessage?.let {
            Text(it, color = Color.White, fontSize = 22.sp, modifier = Modifier.align(Alignment.Center))
        }
        // Duraklatilinca ortada buyuk isaret
        if (!playing && !showOptions) {
            Text(
                "⏸", color = Color.White, fontSize = 72.sp,
                modifier = Modifier.align(Alignment.Center)
                    .background(Color(0x88000000), RoundedCornerShape(16.dp))
                    .padding(horizontal = 28.dp, vertical = 8.dp)
            )
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
                // Oynatma dugmeleri: kumandada ▼ ◀ OK ▶ ▲ tuslarina karsilik gelir, dokunarak da kullanilir
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally)
                ) {
                    CtrlChip("⏪ 1 dk", "▼", flash == "b60") { seek(-60_000) }
                    CtrlChip("◀◀ 10 sn", "◀", flash == "b10") { seek(-10_000) }
                    CtrlChip(if (playing) "⏸ Duraklat" else "▶ Oynat", "OK", flash == "pp") { togglePlay() }
                    CtrlChip("10 sn ▶▶", "▶", flash == "f10") { seek(10_000) }
                    CtrlChip("1 dk ⏩", "▲", flash == "f60") { seek(60_000) }
                }
                Text(
                    "Uzun OK / basılı tut: Ses · Altyazı · Görüntü oranı · Geri: Çık",
                    color = Color(0xFF94A3B8), fontSize = 13.sp
                )
            }
        }
        if (showOptions) {
            OptionsPanel(
                options, oSel, "OK: Değiştir · ◀ / Geri: Kapat",
                onClick = { i -> optIdx = i; options.getOrNull(i)?.action?.invoke() },
                top = if (isTv) null else ({ VolumeBrightnessSliders() })
            )
        }
    }
}

/** Oynatma dugmesi: odak almaz (kumanda tuslari ekran genelinde calisir); dokunarak da kullanilir. */
@Composable
private fun CtrlChip(text: String, key: String, lit: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text, color = if (lit) Color.Black else Color.White, fontSize = 17.sp,
            modifier = Modifier
                .background(if (lit) Accent else Color(0x33FFFFFF), RoundedCornerShape(10.dp))
                .pointerInput(Unit) { detectTapGestures { onClick() } }
                .padding(horizontal = 16.dp, vertical = 10.dp)
        )
        Text(key, color = Color(0xFF94A3B8), fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp))
    }
}
