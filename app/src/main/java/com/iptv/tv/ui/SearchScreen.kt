package com.iptv.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.iptv.tv.AppViewModel
import com.iptv.tv.Item
import com.iptv.tv.Screen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private fun kindLabel(kind: String) = when (kind) {
    "live" -> "Canlı"
    "movie" -> "Film"
    "series" -> "Dizi"
    else -> kind
}

/** Kanal / film / dizi arama ekrani. */
@Composable
fun SearchScreen(vm: AppViewModel) {
    var results by remember { mutableStateOf<List<Item>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var sel by remember { mutableIntStateOf(0) }
    var listFocused by remember { mutableStateOf(false) }
    val fieldFr = remember { FocusRequester() }
    val listFr = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        // Onceki aramadan donuldiyse dogrudan sonuclara, degilse yazma kutusuna odaklan
        runCatching { if (vm.searchQuery.isNotEmpty()) listFr.requestFocus() else fieldFr.requestFocus() }
    }
    LaunchedEffect(vm.searchQuery, vm.library) {
        val q = vm.searchQuery
        if (q.trim().length < 2) { results = emptyList(); return@LaunchedEffect }
        searching = true
        delay(300)
        results = withContext(Dispatchers.Default) { vm.search(q) }
        sel = 0
        searching = false
    }

    BackHandler { vm.openScreen(Screen.LIVE) }

    val colors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = Color.White, unfocusedTextColor = Color.White,
        focusedBorderColor = Accent, unfocusedBorderColor = Color.Gray,
        focusedLabelColor = Accent, unfocusedLabelColor = Color.LightGray,
        cursorColor = Accent
    )
    val cur = sel.coerceIn(0, (results.size - 1).coerceAtLeast(0))

    Column(
        Modifier
            .fillMaxSize()
            .background(ScreenBg)
            .padding(horizontal = 40.dp, vertical = 28.dp)
    ) {
        Text("ARA", color = Accent, fontSize = 26.sp, modifier = Modifier.padding(bottom = 10.dp))
        OutlinedTextField(
            value = vm.searchQuery,
            onValueChange = { vm.searchQuery = it },
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 900.dp)
                .focusRequester(fieldFr)
                .onPreviewKeyEvent { ev ->
                    // Asagi ok: sonuclara gec
                    if (ev.type == KeyEventType.KeyDown && ev.key == Key.DirectionDown && results.isNotEmpty()) {
                        runCatching { listFr.requestFocus() }
                        true
                    } else false
                },
            label = { Text("Kanal, film veya dizi adı (en az 2 harf)") },
            singleLine = true,
            colors = colors,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = {
                if (results.isNotEmpty()) runCatching { listFr.requestFocus() }
            })
        )
        val info = when {
            searching -> "Aranıyor…"
            vm.searchQuery.trim().length < 2 -> "Yazmak için OK'a basın. Telefondaki Google TV uygulamasının klavyesi daha hızlıdır."
            results.isEmpty() -> "Sonuç yok"
            else -> "${results.size} sonuç · ▼ ile listeye geçin"
        }
        Text(info, color = Color(0xFF94A3B8), fontSize = 14.sp, modifier = Modifier.padding(vertical = 8.dp))

        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .onFocusChanged { listFocused = it.isFocused }
                .tvKeys(
                    onUp = {
                        if (cur == 0) runCatching { fieldFr.requestFocus() } else sel = cur - 1
                        true
                    },
                    onDown = { sel = (cur + 1).coerceAtMost((results.size - 1).coerceAtLeast(0)); true },
                    onOk = { results.getOrNull(cur)?.let { vm.openSearchResult(it) } },
                    onLongOk = {
                        results.getOrNull(cur)?.let {
                            if (it.kind == "live") {
                                val added = vm.toggleFavorite(it)
                                vm.toast(if (added) "★ Favorilere eklendi: ${it.name}" else "Favorilerden çıkarıldı: ${it.name}")
                            }
                        }
                    }
                )
                .focusRequester(listFr)
                .focusable()
        ) {
            TvList(
                rows = results.map {
                    ListRow(
                        title = it.name,
                        sub = kindLabel(it.kind) + " · " + it.group,
                        logo = it.logo,
                        badge = if (it.kind == "live" && vm.isFav(it)) "★" else ""
                    )
                },
                selected = if (listFocused) cur else -1,
                modifier = Modifier.fillMaxSize(),
                rowHeight = 60,
                onClick = { i -> sel = i; results.getOrNull(i)?.let { vm.openSearchResult(it) } },
                onLongClick = { i ->
                    results.getOrNull(i)?.let {
                        if (it.kind == "live") {
                            val added = vm.toggleFavorite(it)
                            vm.toast(if (added) "★ Favorilere eklendi: ${it.name}" else "Favorilerden çıkarıldı: ${it.name}")
                        }
                    }
                }
            )
        }
        Text(
            "OK: Aç · Uzun OK (kanal): Favori ★ · ▲ (en üstte): yazma kutusu · Geri: Canlı TV",
            color = Color(0xFF94A3B8), fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp)
        )
    }
}
