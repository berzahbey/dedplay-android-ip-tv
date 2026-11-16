package com.iptv.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.iptv.tv.AppViewModel
import com.iptv.tv.Screen
import com.iptv.tv.Source

@Composable
fun TvButton(text: String, modifier: Modifier = Modifier, selected: Boolean = false, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier
            .onFocusChanged { focused = it.isFocused }
            .background(
                when {
                    focused -> RowSelected
                    selected -> Color(0xFF323842)
                    else -> RowBg
                }, shape
            )
            .then(if (focused) Modifier.border(2.dp, Accent, shape) else Modifier)
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 12.dp)
    ) {
        Text(text, color = Color.White, fontSize = 18.sp)
    }
}

@Composable
fun AddSourceScreen(vm: AppViewModel) {
    var name by remember { mutableStateOf("IPTV") }
    var type by remember { mutableStateOf("xtream") }
    var url by remember { mutableStateOf("") }
    var host by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    val focus = LocalFocusManager.current
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    val canCancel = vm.sources.isNotEmpty()
    BackHandler(enabled = canCancel) { vm.openScreen(Screen.SETTINGS) }

    val colors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = Color.White, unfocusedTextColor = Color.White,
        focusedBorderColor = Accent, unfocusedBorderColor = Color.Gray,
        focusedLabelColor = Accent, unfocusedLabelColor = Color.LightGray,
        cursorColor = Accent
    )
    val next = KeyboardOptions(imeAction = ImeAction.Next)
    val nextAction = KeyboardActions(onNext = { focus.moveFocus(FocusDirection.Down) })

    fun submit() {
        if (busy) return
        val n = name.trim().ifEmpty { "IPTV" }
        val src: Source
        if (type == "m3u") {
            if (url.isBlank()) { status = "M3U linkini girin"; return }
            src = Source(System.currentTimeMillis().toString(), n, "m3u", url = url.trim())
        } else {
            if (host.isBlank() || user.isBlank() || pass.isBlank()) { status = "Sunucu, kullanıcı adı ve şifre gerekli"; return }
            var h = host.trim()
            if (!h.startsWith("http://", ignoreCase = true) && !h.startsWith("https://", ignoreCase = true)) {
                h = "http://$h"
            }
            if (h.contains("/get.php")) h = h.substringBefore("/get.php")
            if (h.contains("/player_api.php")) h = h.substringBefore("/player_api.php")
            h = h.trimEnd('/')
            src = Source(System.currentTimeMillis().toString(), n, "xtream", host = h, user = user.trim(), pass = pass.trim())
        }
        busy = true
        status = "Bağlanılıyor…"
        vm.addSource(src) { err ->
            busy = false
            status = err ?: ""
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(ScreenBg)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 60.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("KAYNAK EKLE", color = Accent, fontSize = 26.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TvButton("Xtream Kodları", selected = type == "xtream", modifier = Modifier.focusRequester(first)) { type = "xtream" }
            TvButton("M3U Linki", selected = type == "m3u") { type = "m3u" }
        }
        val fm = Modifier.fillMaxWidth().widthIn(max = 720.dp)
        OutlinedTextField(name, { name = it }, fm, label = { Text("Kaynak adı") }, singleLine = true,
            colors = colors, keyboardOptions = next, keyboardActions = nextAction)
        if (type == "m3u") {
            OutlinedTextField(url, { url = it }, fm, label = { Text("M3U linki (http://...)") }, singleLine = true,
                colors = colors, keyboardOptions = next, keyboardActions = nextAction)
        } else {
            OutlinedTextField(host, { host = it }, fm, label = { Text("Sunucu (örn: http://sunucu.com:8080)") },
                singleLine = true, colors = colors, keyboardOptions = next, keyboardActions = nextAction)
            OutlinedTextField(user, { user = it }, fm, label = { Text("Kullanıcı adı") }, singleLine = true,
                colors = colors, keyboardOptions = next, keyboardActions = nextAction)
            OutlinedTextField(pass, { pass = it }, fm, label = { Text("Şifre") }, singleLine = true,
                colors = colors, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TvButton(if (busy) "Bekleyin…" else "Kaydet ve Bağlan") { submit() }
            if (canCancel) TvButton("İptal") { vm.openScreen(Screen.SETTINGS) }
        }
        if (status.isNotEmpty()) Text(status, color = Color(0xFFFFD54F), fontSize = 16.sp)
        Text(
            "İpucu: Yazmak için kumandada OK'a basınca ekran klavyesi açılır. Uzun linkleri telefondaki " +
                "Android TV / Google TV uygulamasının klavyesiyle yazmak çok daha kolaydır.",
            color = Color(0xFF94A3B8), fontSize = 13.sp, modifier = Modifier.widthIn(max = 720.dp)
        )
    }
}
