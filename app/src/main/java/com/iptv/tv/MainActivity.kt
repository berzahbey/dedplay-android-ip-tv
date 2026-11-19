package com.iptv.tv

import android.app.Activity
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.iptv.tv.ui.AddSourceScreen
import com.iptv.tv.ui.KeyFocus
import com.iptv.tv.ui.BrowseScreen
import com.iptv.tv.ui.LiveScreen
import com.iptv.tv.ui.ManageGroupsScreen
import com.iptv.tv.ui.MultiScreen
import com.iptv.tv.ui.PanelBg
import com.iptv.tv.ui.RecordingsScreen
import com.iptv.tv.ui.PlayerScreen
import com.iptv.tv.ui.SearchScreen
import com.iptv.tv.ui.SettingsScreen

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Uygulama yeni acildi (arka plandan donus degil): ayara gore ilk favori / son kanal
        if (savedInstanceState == null) vm.onFreshLaunch()
        setContent { AppRoot(vm) }
    }

    /**
     * Ekran odagini kaybetmis cihazlar icin: tus hicbir yerde islenmediyse odagi o anki
     * ekrana geri ver ve tusu bir kez daha gonder. (Geri / Home sisteme birakilir.)
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (super.dispatchKeyEvent(event)) return true
        when (event.keyCode) {
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_HOME,
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE,
            KeyEvent.KEYCODE_POWER -> return false
        }
        val fr = KeyFocus.current ?: return false
        if (runCatching { fr.requestFocus() }.isFailure) return false
        return super.dispatchKeyEvent(event)
    }

    override fun onStop() {
        super.onStop()
        vm.onBackground()
    }

    override fun onStart() {
        super.onStart()
        vm.onForeground()
    }
}

@Composable
fun AppRoot(vm: AppViewModel) {
    val ctx = LocalContext.current
    if (vm.exitRequested) {
        LaunchedEffect(Unit) {
            vm.exitRequested = false
            (ctx as? Activity)?.finish()
        }
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (vm.sources.isEmpty()) {
            AddSourceScreen(vm)
        } else {
            when (vm.screen) {
                Screen.LIVE -> LiveScreen(vm)
                Screen.MOVIES -> BrowseScreen(vm, "movie")
                Screen.SERIES -> BrowseScreen(vm, "series")
                Screen.SETTINGS -> SettingsScreen(vm)
                Screen.MANAGE_GROUPS -> ManageGroupsScreen(vm)
                Screen.ADD_SOURCE -> AddSourceScreen(vm)
                Screen.PLAYER -> PlayerScreen(vm)
                Screen.SEARCH -> SearchScreen(vm)
                Screen.RECORDINGS -> RecordingsScreen(vm)
                Screen.MULTI -> MultiScreen(vm)
            }
        }
        vm.toastText?.let {
            Text(
                it, color = Color.White, fontSize = 18.sp,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 48.dp)
                    .background(PanelBg, RoundedCornerShape(10.dp))
                    .padding(horizontal = 22.dp, vertical = 12.dp)
            )
        }
    }
}
