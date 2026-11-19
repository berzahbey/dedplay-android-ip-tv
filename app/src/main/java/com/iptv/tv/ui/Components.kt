package com.iptv.tv.ui

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.NativeKeyEvent
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.positionChange
import kotlin.math.abs
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage

// ---- Renkler (Siyah / Koyu Tema) ----
/** Tema: paneller, satirlar, vurgu rengi ve ekran arka plani. */
data class AppTheme(
    val name: String,
    val panel: Color,
    val row: Color,
    val rowSelected: Color,
    val accent: Color,
    val screen: Color
)

val THEMES = listOf(
    AppTheme("Gri", Color(0xF4121418), Color(0xFF1E2228), Color(0xFF323842), Color(0xFFE2E8F0), Color(0xFF0F1115)),
    AppTheme("Mavi", Color(0xF40B1730), Color(0xFF13264A), Color(0xFF1E3A70), Color(0xFF8EC5FF), Color(0xFF07101F)),
    AppTheme("Yeşil", Color(0xF40C1A14), Color(0xFF133024), Color(0xFF1E4A37), Color(0xFF6EE7B7), Color(0xFF07120D)),
    AppTheme("Mor", Color(0xF4170F26), Color(0xFF261A3D), Color(0xFF3A2860), Color(0xFFC4B5FD), Color(0xFF0F0A1A)),
    AppTheme("Turuncu", Color(0xF41C130B), Color(0xFF2E2014), Color(0xFF4A321C), Color(0xFFFDBA74), Color(0xFF140D07)),
    AppTheme("Kırmızı", Color(0xF41E0D0F), Color(0xFF331619), Color(0xFF552226), Color(0xFFFCA5A5), Color(0xFF140708))
)

/** Secili tema (degisince tum ekranlar aninda yeniden cizilir). */
object ThemeState {
    var index by mutableIntStateOf(0)
    val current: AppTheme get() = THEMES.getOrElse(index) { THEMES[0] }
}

val PanelBg: Color get() = ThemeState.current.panel
val RowBg: Color get() = ThemeState.current.row
val RowSelected: Color get() = ThemeState.current.rowSelected
val Accent: Color get() = ThemeState.current.accent
val ScreenBg: Color get() = ThemeState.current.screen

/** Listede gosterilecek tek satir. */
data class ListRow(val title: String, val sub: String = "", val logo: String = "", val badge: String = "")

/** Cihaz türü: TV, Tablet veya Telefon. */
enum class DeviceType { TV, TABLET, PHONE }

/** Cihaz türünü döndürür (UI modu + ekran genişliğine göre). */
@Composable
fun rememberDeviceType(): DeviceType {
    val ctx = LocalContext.current
    val config = LocalConfiguration.current
    val uiMode = (ctx.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager)?.currentModeType
    return remember {
        when {
            uiMode == Configuration.UI_MODE_TYPE_TELEVISION -> DeviceType.TV
            config.screenWidthDp >= 600 -> DeviceType.TABLET
            else -> DeviceType.PHONE
        }
    }
}

fun isOk(k: Key) = k == Key.DirectionCenter || k == Key.Enter || k == Key.NumPadEnter

/** Kumanda rakam tuslari: ilk 10 normal, sonraki 10 sayi takimi (index % 10 = rakam). */
private val DIGIT_KEYS = listOf(
    Key.Zero, Key.One, Key.Two, Key.Three, Key.Four, Key.Five, Key.Six, Key.Seven, Key.Eight, Key.Nine,
    Key.NumPad0, Key.NumPad1, Key.NumPad2, Key.NumPad3, Key.NumPad4,
    Key.NumPad5, Key.NumPad6, Key.NumPad7, Key.NumPad8, Key.NumPad9
)

/** Panel genisligi: dar ekranlarda (telefon, dikey tablet) ekranin %92'sini gecmez. */
@Composable
fun panelWidth(widthDp: Int): Dp {
    val screen = LocalConfiguration.current.screenWidthDp
    val tv = rememberIsTv()
    // Dar ekran (telefon dikey): neredeyse tam genislik. TV: en fazla %40 (goruntu arkada gorunsun).
    // Tablet / telefon yatay: %50 (ustteki dokunmatik dugmeler sigsin).
    val maxW = when {
        screen < 700 -> screen * 0.92f
        tv -> screen * 0.40f
        else -> screen * 0.50f
    }
    return minOf(widthDp.toFloat(), maxW).toInt().dp
}

/**
 * Dokunmatik ekran hareketleri: dokun, basili tut, cift dokun (x konumu 0..1),
 * yukari / asagi kaydir.
 */
@Composable
fun Modifier.touchGestures(
    onTap: () -> Unit = {},
    onLongPress: () -> Unit = {},
    onDoubleTap: ((Float) -> Unit)? = null,
    onSwipeUp: () -> Unit = {},
    onSwipeDown: () -> Unit = {},
    onSwipeLeft: (() -> Unit)? = null,
    onSwipeRight: (() -> Unit)? = null
): Modifier {
    val hasHorizontal = onSwipeLeft != null || onSwipeRight != null
    val left by rememberUpdatedState(onSwipeLeft)
    val right by rememberUpdatedState(onSwipeRight)
    val tap by rememberUpdatedState(onTap)
    val long by rememberUpdatedState(onLongPress)
    val dbl by rememberUpdatedState(onDoubleTap)
    val up by rememberUpdatedState(onSwipeUp)
    val down by rememberUpdatedState(onSwipeDown)
    val hasDouble = onDoubleTap != null
    return this
        .pointerInput(hasDouble) {
            detectTapGestures(
                onTap = { tap() },
                onLongPress = { long() },
                onDoubleTap = if (hasDouble) { pos -> dbl?.invoke(pos.x / size.width.coerceAtLeast(1)) } else null
            )
        }
        .pointerInput(Unit) {
            var total = 0f
            detectVerticalDragGestures(
                onDragStart = { total = 0f },
                onDragEnd = {
                    val limit = 80.dp.toPx()
                    if (total < -limit) up() else if (total > limit) down()
                },
                onVerticalDrag = { _, dy -> total += dy }
            )
        }
        .then(if (!hasHorizontal) Modifier else Modifier.pointerInput(Unit) {
            // Yatay kaydirmayi listelerden ONCE yakala (Initial pass): yoksa listedeki satir
            // kaydirmayi "dokunma" sanip kanali acar ve paneli kapatir.
            awaitEachGesture {
                val first = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                val limit = 80.dp.toPx()
                val slop = viewConfiguration.touchSlop
                var dx = 0f
                var dy = 0f
                var horizontal = false
                while (true) {
                    val ev = awaitPointerEvent(PointerEventPass.Initial)
                    val c = ev.changes.firstOrNull { it.id == first.id } ?: break
                    if (!c.pressed) {
                        if (horizontal) {
                            c.consume()
                            if (dx > limit) right?.invoke() else if (dx < -limit) left?.invoke()
                        }
                        break
                    }
                    val d = c.positionChange()
                    dx += d.x; dy += d.y
                    if (!horizontal && abs(dx) > slop && abs(dx) > abs(dy) * 1.5f) horizontal = true
                    if (horizontal) c.consume()
                }
            }
        })
}

/** Cihaz TV mi (kumanda) yoksa telefon/tablet/arac kutusu mu (dokunmatik)? */
@Composable
fun rememberIsTv(): Boolean {
    val ctx = LocalContext.current
    return remember {
        (ctx.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager)?.currentModeType ==
            Configuration.UI_MODE_TYPE_TELEVISION
    }
}

/** Panel genisliği: cihaza göre optimize edilmiş (MultiScreen için). */
@Composable
fun multiPanelWidth(widthDp: Int): Dp {
    val screen = LocalConfiguration.current.screenWidthDp
    val device = rememberDeviceType()
    val maxW = when (device) {
        DeviceType.TV -> screen * 0.32f
        DeviceType.TABLET -> screen * 0.55f
        DeviceType.PHONE -> screen * 0.92f
    }
    return minOf(widthDp.toFloat(), maxW).toInt().dp
}

/** Yazı boyutu: cihaza göre ölçeklendirilir. */
@Composable
fun txtSize(baseSp: Float): Float {
    val device = rememberDeviceType()
    return when (device) {
        DeviceType.TV -> baseSp * 1.1f
        DeviceType.TABLET -> baseSp * 1.1f
        DeviceType.PHONE -> baseSp
    }
}

/** Satır yüksekliği: cihaza göre ölçeklendirilir. */
@Composable
fun rowHeightDp(baseDp: Int): Dp {
    val device = rememberDeviceType()
    return when (device) {
        DeviceType.TV -> (baseDp * 1.1f).toInt().dp
        DeviceType.TABLET -> (baseDp * 1.1f).toInt().dp
        DeviceType.PHONE -> baseDp.dp
    }
}

/** Kenar boşluğu: cihaza göre ölçeklendirilir. */
@Composable
fun padSizeDp(baseDp: Int): Dp {
    val device = rememberDeviceType()
    return when (device) {
        DeviceType.TV -> (baseDp * 1.1f).toInt().dp
        DeviceType.TABLET -> (baseDp * 1.2f).toInt().dp
        DeviceType.PHONE -> baseDp.dp
    }
}

/** Ayarlardaki goruntu orani -> PlayerView resize modu. */
fun resizeModeOf(mode: Int): Int = when (mode) {
    1 -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
    2 -> AspectRatioFrameLayout.RESIZE_MODE_FILL
    else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
}

/**
 * Kumanda tuslarini yakalar. OK tusuna kisa basma -> onOk, uzun basma -> onLongOk.
 * Her lambda tusu tuketip tuketmedigini (true/false) dondurur.
 */
@Composable
fun Modifier.tvKeys(
    onUp: () -> Boolean = { false },
    onDown: () -> Boolean = { false },
    onLeft: () -> Boolean = { false },
    onRight: () -> Boolean = { false },
    onOk: () -> Unit = {},
    onLongOk: () -> Unit = {},
    onChannelUp: () -> Boolean = { false },
    onChannelDown: () -> Boolean = { false },
    onDigit: (Int) -> Boolean = { false },
    onMenu: () -> Boolean = { false },
    onLastChannel: () -> Boolean = { false },
    onPlayPause: () -> Boolean = { false }
): Modifier {
    val longDone = remember { booleanArrayOf(false) }
    return this.onKeyEvent { ev ->
        val k = ev.key
        if (isOk(k)) {
            when (ev.type) {
                KeyEventType.KeyDown -> {
                    if (ev.nativeKeyEvent.repeatCount == 0) {
                        longDone[0] = false
                    } else if (!longDone[0]) {
                        longDone[0] = true
                        onLongOk()
                    }
                    true
                }
                KeyEventType.KeyUp -> {
                    if (!longDone[0]) onOk()
                    longDone[0] = false
                    true
                }
                else -> false
            }
        } else if (ev.type == KeyEventType.KeyDown) {
            when (k) {
                Key.DirectionUp -> onUp()
                Key.DirectionDown -> onDown()
                Key.DirectionLeft -> onLeft()
                Key.DirectionRight -> onRight()
                // Direksiyon / medya tuslari: sonraki-onceki = kanal degistir
                Key.ChannelUp, Key.PageUp, Key.MediaNext, Key.MediaFastForward -> onChannelUp()
                Key.ChannelDown, Key.PageDown, Key.MediaPrevious, Key.MediaRewind -> onChannelDown()
                Key.MediaPlayPause, Key.MediaPlay, Key.MediaPause -> onPlayPause()
                Key.Menu, Key.Info -> onMenu()
                Key.LastChannel -> onLastChannel()
                in DIGIT_KEYS -> onDigit(DIGIT_KEYS.indexOf(k) % 10)
                else -> false
            }
        } else false
    }
}

/** Secili satiri kendimiz yonettigimiz (sanal odak) liste. */
@Composable
fun TvList(
    rows: List<ListRow>,
    selected: Int,
    modifier: Modifier = Modifier,
    rowHeight: Dp = 44.dp,
    textSize: Float = 16f,
    subSize: Float = 13f,
    emptyText: String = "",
    onClick: ((Int) -> Unit)? = null,
    onLongClick: ((Int) -> Unit)? = null
) {
    val state = rememberLazyListState()
    LaunchedEffect(selected, rows.size) {
        if (rows.isNotEmpty()) state.scrollToItem((selected - 3).coerceIn(0, rows.size - 1))
    }
    if (rows.isEmpty()) {
        Box(modifier.padding(16.dp)) { Text(emptyText, color = Color.White, fontSize = 18.sp) }
        return
    }
    LazyColumn(state = state, modifier = modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        itemsIndexed(rows) { i, r ->
            val sel = i == selected
            val click by rememberUpdatedState(onClick)
            val longClick by rememberUpdatedState(onLongClick)
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(rowHeight)
                    .background(if (sel) RowSelected else RowBg, RoundedCornerShape(6.dp))
                    .then(if (sel) Modifier.border(2.dp, Accent, RoundedCornerShape(6.dp)) else Modifier)
                    .then(
                        // Dokunmatik ekran: dokun = sec, basili tut = uzun OK
                        if (onClick != null || onLongClick != null) Modifier.pointerInput(i) {
                            detectTapGestures(
                                onTap = { click?.invoke(i) },
                                onLongPress = { longClick?.invoke(i) }
                            )
                        } else Modifier
                    )
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (r.logo.isNotEmpty()) {
                    AsyncImage(
                        model = r.logo,
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.size((rowHeight - 10.dp))
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    r.title,
                    color = if (sel) Color.White else Accent,
                    fontSize = textSize.sp,
                    fontWeight = if (sel) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (r.sub.isNotEmpty()) {
                    Text(r.sub, color = Color(0xFF94A3B8), fontSize = subSize.sp, maxLines = 1)
                    Spacer(Modifier.width(8.dp))
                }
                if (r.badge.isNotEmpty()) Text(r.badge, color = Color(0xFFFFD54F), fontSize = textSize.sp)
            }
        }
    }
}

@Composable
fun PanelHeader(text: String) {
    val device = rememberDeviceType()
    val size = when (device) {
        DeviceType.TV -> 22f
        DeviceType.TABLET -> 20f
        DeviceType.PHONE -> 18f
    }
    Text(
        text,
        color = Accent,
        fontSize = size.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
    )
}

/** ExoPlayer goruntu yuzeyi (kontroller kapali, odak almaz). */
@Composable
fun PlayerSurface(player: Player, resizeMode: Int = 0, modifier: Modifier = Modifier.fillMaxSize()) {
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            PlayerView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
                )
                useController = false
                isFocusable = false
                isFocusableInTouchMode = false
                this.player = player
            }
        },
        update = { view -> view.resizeMode = resizeModeOf(resizeMode) }
    )
}

/**
 * Kumanda tuslari icin yedek odak: bazi cihazlarda (ozellikle eski / ozel Android kutularinda)
 * ekran odagini kaybedebiliyor ve ok / OK tuslari hicbir yere ulasmiyor. MainActivity boyle bir
 * tusu yakalayinca buradaki ekrana odagi geri verip tusu tekrar gonderir.
 */
object KeyFocus {
    var current: FocusRequester? = null
}

/** Ekranin tus dinleyen alanini yedek odak olarak kaydeder. */
@Composable
fun RegisterKeyFocus(fr: FocusRequester) {
    DisposableEffect(fr) {
        KeyFocus.current = fr
        onDispose { if (KeyFocus.current === fr) KeyFocus.current = null }
    }
}
