package com.iptv.tv

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

private val TURKISH_LABEL = Regex("(?iu)(türk|turk|\\btr\\b|\\btur\\b)")

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val store = Store(app)

    val player: ExoPlayer = buildPlayer(app)

    // ---------- Durum ----------
    var sources by mutableStateOf(store.loadSources())
        private set
    var activeId by mutableStateOf(store.activeSourceId)
        private set
    var library by mutableStateOf<Library?>(null)
        private set
    var loading by mutableStateOf(false)
        private set
    var loadError by mutableStateOf<String?>(null)
        private set
    var screen by mutableStateOf(Screen.LIVE)
        private set
    var favorites by mutableStateOf(store.loadSet("fav"))
        private set
    var hidden by mutableStateOf(store.loadSet("hidden"))
        private set
    var streamExt by mutableStateOf(store.streamExt)
        private set
    var currentChannel by mutableStateOf<Item?>(null)
        private set
    var previousChannel by mutableStateOf<Item?>(null)
        private set
    var liveGroup by mutableStateOf(FAV_GROUP)
    var playerMessage by mutableStateOf<String?>(null)
        private set
    var buffering by mutableStateOf(false)
        private set
    var vodTitle by mutableStateOf("")
        private set
    var toastText by mutableStateOf<String?>(null)
        private set
    var episodes by mutableStateOf<List<Episode>?>(null)
        private set
    var episodesTitle by mutableStateOf("")
        private set
    var episodesLoading by mutableStateOf(false)
        private set
    var manageKind by mutableStateOf("live")
    /** Kaynak ekleme ekrani duzenleme modunda acildiysa duzenlenen kaynagin id si. */
    var editSourceId by mutableStateOf<String?>(null)
    var searchQuery by mutableStateOf("")

    // Ayarlar
    var resizeMode by mutableIntStateOf(store.resizeMode)
        private set
    var bufferMode by mutableIntStateOf(store.bufferMode)
        private set
    var startMode by mutableIntStateOf(store.startMode)
        private set
    var sleepMinutes by mutableIntStateOf(0)
        private set
    var sleepEndsAt by mutableLongStateOf(0L)
        private set
    /** Uyku zamanlayicisi dolunca true olur; MainActivity uygulamayi kapatir. */
    var exitRequested by mutableStateOf(false)

    /** Ses/altyazi parcalari degisince artar (Compose yeniden ciziyor). */
    var tracksVersion by mutableIntStateOf(0)
        private set

    /** Uygulama ekranda mi (arka plana gecince coklu ekran karolari durur) */
    var appVisible by mutableStateOf(true)
        private set

    /** Xtream hesap bilgisi (baglanti sayisi, bitis) */
    var account by mutableStateOf<AccountInfo?>(null)
        private set

    /** Kanal id -> yayin akisi */
    var epg by mutableStateOf<Map<String, List<EpgEntry>>>(emptyMap())
        private set

    private var vodReturn = Screen.MOVIES
    private var playingUrl = ""
    private var toastJob: Job? = null
    private var watchJob: Job? = null
    private var sleepJob: Job? = null
    private var lastFrames = -1
    private var failCount = 0
    private var autoAudioUrl = ""
    private var freshStart = false
    private val epgFetchedAt = HashMap<String, Long>()
    /** Saglayicidan gelen ham yayin akisi; epg bunun saat duzeltmesi uygulanmis halidir. */
    private var epgRaw: Map<String, List<EpgEntry>> = emptyMap()

    /** Arac / medya tuslari (direksiyon, kulaklik, bluetooth kumanda) icin medya oturumu. */
    private val mediaSession: MediaSession = MediaSession(app, "dedplay")

    private var idx: Map<String, Map<String, List<Item>>> = emptyMap()
    private var liveById: Map<String, Item> = emptyMap()
    private var liveOrder: Map<String, Int> = emptyMap()

    /**
     * Cihazin kendi cozucusu yoksa (orn. tablette AC3 / E-AC3 Dolby ses, UHD/4K kanallarda yaygin)
     * FFmpeg yazilim cozucusune duser. Cihaz destekliyorsa yine donanim cozucusu kullanilir.
     */
    private fun renderers(app: Application) = DefaultRenderersFactory(app)
        .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
        .setEnableDecoderFallback(true)

    private fun buildPlayer(app: Application): ExoPlayer {
        // Bazi IPTV yayinlari tam anahtar kare (IDR) gondermez; bu bayraklar olmadan
        // ExoPlayer goruntuyu cope atip siyah ekranda bekler.
        val extractors = DefaultExtractorsFactory().setTsExtractorFlags(
            DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or
                DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS
        )
        val b = when (store.bufferMode) {
            0 -> intArrayOf(5_000, 20_000, 1_000, 2_000)
            2 -> intArrayOf(30_000, 90_000, 4_000, 8_000)
            else -> intArrayOf(15_000, 50_000, 2_500, 5_000)
        }
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(b[0], b[1], b[2], b[3])
            .build()
        return ExoPlayer.Builder(app, renderers(app))
            .setMediaSourceFactory(
                // DefaultDataSource: http(s) -> OkHttp, file:// (kayitlar) -> dosya
                DefaultMediaSourceFactory(app, extractors).setDataSourceFactory(
                    DefaultDataSource.Factory(app, OkHttpDataSource.Factory(Http.streamClient).setUserAgent(USER_AGENT))
                )
            )
            .setLoadControl(loadControl)
            .build()
    }

    init {
        Recorder.load(app)
        Recorder.ensureService(app)
        setupMediaSession()
        applyLanguagePrefs()
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                // Ilk hatadan sonraki yeniden denemede de yukleniyor simgesi gorunsun
                buffering = state == Player.STATE_BUFFERING ||
                    (state == Player.STATE_IDLE && failCount == 1 && isLiveActive())
                if (state != Player.STATE_READY && isLiveActive() && player.playWhenReady) {
                    armWatch(if (state == Player.STATE_BUFFERING) 12_000L else 1_500L)
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) { playerMessage = null; failCount = 0 }
                updateSessionState(isPlaying)
            }

            override fun onPlayerError(error: PlaybackException) {
                if (screen == Screen.LIVE) {
                    // Canli yayinda bekci (armWatch) 1,5 sn sonra kanali yeniden acar.
                    // Ilk hatada uyari gosterme (cogu sunucu ikinci denemede acar), sadece yukleniyor simgesi.
                    failCount++
                    buffering = failCount < 2
                    if (failCount >= 2) playerMessage = "Yayın açılamadı – yeniden deneniyor…"
                } else {
                    buffering = false
                    playerMessage = "Yayın açılamadı"
                }
            }

            override fun onTracksChanged(tracks: Tracks) {
                tracksVersion++
                autoPickAudio()
            }
        })
        val src = activeSource()
        if (src != null) {
            val cached = store.loadLibrary(src.id)
            if (cached != null) {
                updateLibrary(cached)
                if (System.currentTimeMillis() - cached.updated > AUTO_REFRESH_MS) refresh()
            } else refresh()
        }
        loadAccount()
    }

    // ---------- Kaynaklar ----------
    fun activeSource(): Source? = sources.firstOrNull { it.id == activeId } ?: sources.firstOrNull()

    fun addSource(src: Source, onDone: (String?) -> Unit) {
        viewModelScope.launch {
            try {
                val finalSrc = if (src.type == "xtream") {
                    withContext(Dispatchers.IO) { Repository.checkAndResolveXtream(src) }
                } else src
                sources = sources + finalSrc
                store.saveSources(sources)
                setActive(finalSrc.id)
                screen = Screen.LIVE
                onDone(null)
            } catch (e: Throwable) {
                onDone(e.message ?: "Bağlanılamadı")
            }
        }
    }

    /**
     * Secilen M3U dosyasini uygulama klasorune kopyalar ve kaynak olarak ekler.
     * (Kopya sayesinde liste yenilemede dosyaya tekrar erisim izni gerekmez.)
     */
    fun importM3uFile(uri: Uri, name: String, onDone: (String?) -> Unit) {
        val ctx = getApplication<Application>()
        viewModelScope.launch {
            try {
                val id = System.currentTimeMillis().toString()
                val path = withContext(Dispatchers.IO) {
                    val dir = File(ctx.filesDir, "m3u").apply { mkdirs() }
                    val out = File(dir, "$id.m3u")
                    val input = ctx.contentResolver.openInputStream(uri) ?: throw RuntimeException("Dosya açılamadı")
                    input.use { inp -> out.outputStream().use { inp.copyTo(it) } }
                    val head = out.bufferedReader().use { r -> CharArray(4096).let { buf -> String(buf, 0, r.read(buf).coerceAtLeast(0)) } }
                    if (!head.contains("#EXTM3U", true) && !head.contains("#EXTINF", true)) {
                        out.delete()
                        throw RuntimeException("Bu bir M3U dosyası değil")
                    }
                    out.absolutePath
                }
                sources = sources + Source(id, name, "m3ufile", url = path)
                store.saveSources(sources)
                setActive(id)
                screen = Screen.LIVE
                onDone(null)
            } catch (e: Throwable) {
                onDone(e.message ?: "Dosya yüklenemedi")
            }
        }
    }

    /** Var olan kaynagi (ayni id ile) gunceller: favoriler / gizli gruplar korunur, liste yeniden indirilir. */
    fun updateSource(src: Source, onDone: (String?) -> Unit) {
        viewModelScope.launch {
            try {
                val finalSrc = if (src.type == "xtream") {
                    withContext(Dispatchers.IO) { Repository.checkAndResolveXtream(src) }
                } else src
                sources = sources.map { if (it.id == finalSrc.id) finalSrc else it }
                store.saveSources(sources)
                store.clearCache(finalSrc.id)
                setActive(finalSrc.id)
                loadAccount()
                editSourceId = null
                screen = Screen.LIVE
                onDone(null)
            } catch (e: Throwable) {
                onDone(e.message ?: "Bağlanılamadı")
            }
        }
    }

    /** Kaynagi id ile siler (aktif olani degistirmeden). */
    fun removeSource(id: String) {
        val src = sources.firstOrNull { it.id == id } ?: return
        store.clearCache(id)
        if (src.type == "m3ufile") runCatching { File(src.url).delete() }
        sources = sources.filter { it.id != id }
        store.saveSources(sources)
    }

    fun setActive(id: String) {
        activeId = id
        store.activeSourceId = id
        stopPlayback()
        currentChannel = null
        previousChannel = null
        liveGroup = FAV_GROUP
        library = null
        rebuild(null)
        epg = emptyMap()
        epgRaw = emptyMap()
        epgFetchedAt.clear()
        val cached = store.loadLibrary(id)
        if (cached != null) updateLibrary(cached) else refresh()
    }

    fun cycleSource() {
        if (sources.size < 2) { toast("Başka kaynak yok"); return }
        val i = sources.indexOfFirst { it.id == activeSource()?.id }
        val next = sources[(i + 1) % sources.size]
        setActive(next.id)
        toast("Kaynak: ${next.name}")
    }

    fun deleteActive() {
        val src = activeSource() ?: return
        store.clearCache(src.id)
        if (src.type == "m3ufile") runCatching { File(src.url).delete() }
        sources = sources.filter { it.id != src.id }
        store.saveSources(sources)
        stopPlayback()
        library = null; rebuild(null); currentChannel = null; previousChannel = null
        val next = sources.firstOrNull()
        if (next != null) setActive(next.id) else { activeId = ""; store.activeSourceId = "" }
    }

    // ---------- Liste yukleme ----------
    fun refresh() {
        val src = activeSource() ?: return
        viewModelScope.launch {
            loading = true
            loadError = null
            try {
                val lib = withContext(Dispatchers.IO) {
                    Repository.load(src, streamExt).also { store.saveLibrary(src.id, it) }
                }
                updateLibrary(lib)
            } catch (e: Throwable) {
                loadError = "Liste yüklenemedi: ${e.message ?: e.javaClass.simpleName}"
            }
            loading = false
        }
    }

    private fun updateLibrary(lib: Library) {
        rebuild(lib)
        library = lib
    }

    private fun rebuild(lib: Library?) {
        idx = if (lib == null) emptyMap() else mapOf(
            "live" to lib.live.groupBy { it.group },
            "movie" to lib.movies.groupBy { it.group },
            "series" to lib.series.groupBy { it.group }
        )
        liveById = lib?.live?.associateBy { it.id } ?: emptyMap()
        liveOrder = lib?.live?.withIndex()?.associate { it.value.id to it.index } ?: emptyMap()
    }

    // ---------- Gruplar ve ogeler ----------
    /** Gizli gruplar haric (includeHidden=true ise hepsi) grup adlari. */
    fun groups(kind: String, includeHidden: Boolean = false): List<String> {
        library; hidden // Compose'un degisimi izlemesi icin okuma
        val all = idx[kind]?.keys?.toList() ?: emptyList()
        return if (includeHidden) all else all.filter { "$kind:$it" !in hidden }
    }

    fun liveGroupsWithFav(): List<String> = listOf(FAV_GROUP) + groups("live")

    fun itemsIn(kind: String, group: String): List<Item> {
        library; favorites
        if (kind == "live" && group == FAV_GROUP) {
            val prefix = "$activeId|"
            return favorites.filter { it.startsWith(prefix) }
                .mapNotNull { liveById[it.removePrefix(prefix)] }
                .sortedBy { item -> liveOrder[item.id] ?: item.id.toIntOrNull() ?: Int.MAX_VALUE }
        }
        return idx[kind]?.get(group) ?: emptyList()
    }

    fun isHidden(kind: String, group: String) = "$kind:$group" in hidden

    fun toggleHidden(kind: String, group: String): Boolean {
        val key = "$kind:$group"
        hidden = if (key in hidden) hidden - key else hidden + key
        store.saveSet("hidden", hidden)
        if (kind == "live" && liveGroup != FAV_GROUP && liveGroup !in groups("live")) {
            liveGroup = groups("live").firstOrNull() ?: FAV_GROUP
        }
        return key in hidden
    }

    // ---------- Arama ----------
    /** Isimde arar; gizli gruplar haric, tur basina en fazla 100 sonuc. */
    fun search(query: String): List<Item> {
        val lib = library ?: return emptyList()
        val q = query.trim()
        if (q.length < 2) return emptyList()
        val hid = hidden
        val match = { it: Item -> it.name.contains(q, ignoreCase = true) && "${it.kind}:${it.group}" !in hid }
        return lib.live.asSequence().filter(match).take(100).toList() +
            lib.movies.asSequence().filter(match).take(100).toList() +
            lib.series.asSequence().filter(match).take(100).toList()
    }

    /** Arama sonucundan secilen ogeyi acar. */
    fun openSearchResult(item: Item) {
        when (item.kind) {
            "live" -> {
                if (item.group in groups("live")) liveGroup = item.group
                screen = Screen.LIVE
                playLive(item)
            }
            "series" -> {
                openScreen(Screen.SERIES)
                openSeries(item)
            }
            else -> playVod(item.name, item.url, Screen.SEARCH)
        }
    }

    // ---------- Favoriler ----------
    private fun favKey(item: Item) = "$activeId|${item.id}"
    fun isFav(item: Item) = favKey(item) in favorites

    fun toggleFavorite(item: Item): Boolean {
        val k = favKey(item)
        favorites = if (k in favorites) favorites - k else favorites + k
        store.saveSet("fav", favorites)
        return k in favorites
    }

    fun clearFavorites() {
        favorites = emptySet()
        store.saveSet("fav", favorites)
    }

    // ---------- Oynatma ----------
    private fun liveUrl(item: Item): String =
        if (activeSource()?.type == "xtream" && item.kind == "live")
            item.url.substringBeforeLast('.') + "." + streamExt
        else item.url

    private fun isLiveActive() = screen == Screen.LIVE && playingUrl.isNotEmpty()

    private fun play(url: String) {
        playingUrl = url
        playerMessage = null
        lastFrames = -1
        failCount = 0
        // Onceki kanalin ses/altyazi secimi yeni kanala tasinmasin (dil tercihi kalir)
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().clearOverrides().build()
        player.setMediaItem(MediaItem.fromUri(url))
        player.prepare()
        player.playWhenReady = true
    }

    private fun stopPlayback() {
        watchJob?.cancel()
        player.stop()
        playingUrl = ""
    }

    fun playLive(item: Item) {
        val cur = currentChannel
        if (cur != null && cur.id != item.id) previousChannel = cur
        currentChannel = item
        store.lastChannelId = item.id
        updateSessionTitle(item.name)
        play(liveUrl(item))
    }

    /** Bir onceki izlenen kanala doner. */
    fun goPrevious() {
        val p = previousChannel
        if (p == null) { toast("Önceki kanal yok"); return }
        playLive(p)
    }

    /** Gecerli gruptaki n. kanali acar (listede gorunen numara). */
    fun playNumber(n: Int) {
        val list = itemsIn("live", liveGroup)
        val item = list.getOrNull(n - 1)
        if (item == null) { toast("Kanal $n bu grupta yok"); return }
        playLive(item)
    }

    /** Uygulama yeni acildiginda (MainActivity.onCreate) cagrilir. */
    fun onFreshLaunch() {
        freshStart = true
        stopPlayback()
        if (screen != Screen.LIVE) openScreen(Screen.LIVE)
    }

    /** Canli TV ekrani acilinca kanali baslatir. */
    fun ensureLive() {
        val lib = library ?: return
        if (freshStart) {
            freshStart = false
            if (startMode == 0) {
                val fav = itemsIn("live", FAV_GROUP).firstOrNull()
                if (fav != null) {
                    currentChannel = fav
                    liveGroup = FAV_GROUP
                }
            }
        }
        var ch = currentChannel
        if (ch == null) {
            ch = liveById[store.lastChannelId]
            if (ch == null) {
                val g = groups("live").firstOrNull()
                ch = if (g != null) idx["live"]?.get(g)?.firstOrNull() else lib.live.firstOrNull()
            }
            if (ch == null) return
            currentChannel = ch
            liveGroup = if (ch.group in groups("live")) ch.group else (groups("live").firstOrNull() ?: FAV_GROUP)
        }
        if (playingUrl != liveUrl(ch)) {
            store.lastChannelId = ch.id
            updateSessionTitle(ch.name)
            play(liveUrl(ch))
        }
    }

    fun zap(delta: Int) {
        val list = itemsIn("live", liveGroup)
        if (list.isEmpty()) return
        val cur = currentChannel
        val i = if (cur == null) -1 else list.indexOfFirst { it.id == cur.id }
        val n = if (i < 0) 0 else (i + delta).mod(list.size)
        playLive(list[n])
    }

    fun playVod(title: String, url: String, back: Screen) {
        vodTitle = title
        vodReturn = back
        updateSessionTitle(title)
        stopPlayback()
        play(url)
        screen = Screen.PLAYER
    }

    fun stopVod() {
        stopPlayback()
        screen = vodReturn
    }

    fun openScreen(s: Screen) {
        if (s != Screen.LIVE && s != Screen.PLAYER) stopPlayback()
        if (s != Screen.SERIES) episodes = null
        screen = s
    }

    // ---------- Donma bekcisi ----------
    // Canli yayinda 10 sn'de bir ekrana yeni kare gelip gelmedigine bakar; gelmiyorsa,
    // yayin uzun sure yukleniyorsa veya hata verdiyse kanali bastan acar.
    private fun armWatch(ms: Long) {
        watchJob?.cancel()
        watchJob = viewModelScope.launch {
            delay(ms)
            watchTick()
        }
    }

    private fun watchTick() {
        if (!isLiveActive()) return
        if (!player.playWhenReady) { armWatch(10_000L); return }
        if (player.playbackState == Player.STATE_READY) {
            val counters = player.videoDecoderCounters
            if (counters == null) { armWatch(10_000L); return }
            counters.ensureUpdated()
            val frames = counters.renderedOutputBufferCount
            val prev = lastFrames
            lastFrames = frames
            if (frames != prev) { armWatch(10_000L); return }
        }
        reloadCurrent()
    }

    private fun reloadCurrent() {
        if (playingUrl.isEmpty()) return
        lastFrames = -1
        player.setMediaItem(MediaItem.fromUri(playingUrl))
        player.prepare()
        player.playWhenReady = true
        armWatch(15_000L)
    }

    /** Ana ekrana gecince: canli yayin baglantisini tamamen kapat (asili kalmasin). */
    fun onBackground() {
        appVisible = false
        watchJob?.cancel()
        player.playWhenReady = false
        if (screen == Screen.LIVE) player.stop()
    }

    /** Uygulamaya geri donunce: canli kanali sifirdan ac, filmi kaldigi yerden surdur. */
    fun onForeground() {
        appVisible = true
        Recorder.ensureService(getApplication())
        when (screen) {
            Screen.LIVE -> if (playingUrl.isNotEmpty()) reloadCurrent()
            Screen.PLAYER -> player.playWhenReady = true
            else -> {}
        }
    }

    // ---------- Ses / altyazi ----------
    private fun applyLanguagePrefs() {
        val b = player.trackSelectionParameters.buildUpon()
        // Turkce yoksa ikinci tercih Ingilizce; ikisi de yoksa yayinin varsayilan sesi
        b.setPreferredAudioLanguages(store.audioLang, "en")
        when (val s = store.subLang) {
            "" -> {}
            "off" -> b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            else -> b.setPreferredTextLanguage(s)
        }
        player.trackSelectionParameters = b.build()
    }

    private fun trackLabel(f: Format, n: Int): String {
        val code = f.language?.takeIf { it.isNotBlank() && it != "und" }
        val lang = code?.let { c ->
            val name = Locale(c).getDisplayLanguage(Locale("tr"))
            if (name.isNotBlank() && !name.equals(c, ignoreCase = true)) {
                name.substring(0, 1).uppercase(Locale("tr")) + name.substring(1)
            } else c.uppercase()
        }
        val parts = ArrayList<String>()
        f.label?.takeIf { it.isNotBlank() }?.let { parts.add(it) }
        if (lang != null && parts.none { it.equals(lang, ignoreCase = true) }) parts.add(lang)
        if (f.channelCount != Format.NO_VALUE && f.channelCount > 2) parts.add("${f.channelCount} kanal")
        return if (parts.isEmpty()) "Parça $n" else parts.joinToString(" · ")
    }

    /** Ses parcasi tercih edilen dilde mi? Dil etiketi eksikse adina da bakar (Turkce/Turkish/TR/TUR). */
    private fun isPreferredAudio(o: TrackOption, want: String): Boolean {
        if (o.lang.isNotEmpty() && (o.lang.equals(want, true) || (want == "tr" && o.lang.equals("tur", true)))) return true
        return want == "tr" && TURKISH_LABEL.containsMatchIn(o.label)
    }

    /**
     * Kanal acilinca bir kez calisir: secili ses tercih edilen dilde degilse, o dildeki
     * parcaya gecer (yayin dil etiketi eksik/yanlis oldugunda da). Kullanici sonra elle
     * baska ses secerse ayni kanalda tekrar karismaz.
     */
    private fun autoPickAudio() {
        if (playingUrl.isEmpty() || autoAudioUrl == playingUrl) return
        val opts = trackOptions(C.TRACK_TYPE_AUDIO)
        if (opts.isEmpty()) return
        autoAudioUrl = playingUrl
        if (opts.size < 2) return
        val want = store.audioLang
        val cur = opts.getOrNull(selectedTrackIndex(C.TRACK_TYPE_AUDIO, opts))
        if (cur != null && isPreferredAudio(cur, want)) return
        val target = opts.firstOrNull { isPreferredAudio(it, want) } ?: return
        val g = player.currentTracks.groups.getOrNull(target.groupIndex) ?: return
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, target.trackIndex))
            .build()
    }

    /** type: C.TRACK_TYPE_AUDIO veya C.TRACK_TYPE_TEXT */
    fun trackOptions(type: Int): List<TrackOption> {
        tracksVersion
        val out = ArrayList<TrackOption>()
        if (type == C.TRACK_TYPE_TEXT) out.add(TrackOption("Kapalı", -1, -1))
        var n = 1
        player.currentTracks.groups.forEachIndexed { gi, g ->
            if (g.type == type) {
                for (ti in 0 until g.length) {
                    if (!g.isTrackSupported(ti)) continue
                    val f = g.getTrackFormat(ti)
                    out.add(TrackOption(trackLabel(f, n++), gi, ti, f.language ?: ""))
                }
            }
        }
        return out
    }

    fun selectedTrackIndex(type: Int, opts: List<TrackOption>): Int {
        tracksVersion
        if (type == C.TRACK_TYPE_TEXT &&
            player.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)
        ) return 0
        val groups = player.currentTracks.groups
        opts.forEachIndexed { i, o ->
            if (o.groupIndex >= 0) {
                val g = groups.getOrNull(o.groupIndex)
                if (g != null && g.isTrackSelected(o.trackIndex)) return i
            }
        }
        return 0
    }

    fun trackSummary(type: Int): String {
        val opts = trackOptions(type)
        if (type == C.TRACK_TYPE_AUDIO && opts.size <= 1) return opts.firstOrNull()?.label ?: "Tek ses"
        if (type == C.TRACK_TYPE_TEXT && opts.size <= 1) return "Yok"
        return opts.getOrNull(selectedTrackIndex(type, opts))?.label ?: "-"
    }

    /** OK'a her basista bir sonraki ses/altyazi secenegine gecer. */
    fun cycleTrack(type: Int) {
        val opts = trackOptions(type)
        // Altyazida listenin basinda "Kapali" vardir; iki secenekten azsa degistirilecek bir sey yok.
        if (opts.size < 2) {
            toast(if (type == C.TRACK_TYPE_TEXT) "Bu yayında altyazı yok" else "Bu yayında başka ses yok")
            return
        }
        val next = opts[(selectedTrackIndex(type, opts) + 1) % opts.size]
        selectTrack(type, next)
        toast((if (type == C.TRACK_TYPE_TEXT) "Altyazı: " else "Ses: ") + next.label)
    }

    private fun selectTrack(type: Int, o: TrackOption) {
        val b = player.trackSelectionParameters.buildUpon()
        if (o.groupIndex < 0) {
            b.setTrackTypeDisabled(type, true)
            if (type == C.TRACK_TYPE_TEXT) store.subLang = "off"
        } else {
            val g = player.currentTracks.groups.getOrNull(o.groupIndex) ?: return
            b.setTrackTypeDisabled(type, false)
            b.setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, o.trackIndex))
            if (type == C.TRACK_TYPE_AUDIO) {
                store.audioLang = o.lang
                if (o.lang.isNotEmpty()) b.setPreferredAudioLanguage(o.lang)
            } else if (type == C.TRACK_TYPE_TEXT) {
                store.subLang = o.lang
                if (o.lang.isNotEmpty()) b.setPreferredTextLanguage(o.lang)
            }
        }
        player.trackSelectionParameters = b.build()
    }

    // ---------- Yayin akisi (EPG) ----------
    /** Xtream kaynaklarinda kanalin yayin akisini getirir (10 dk onbellek). */
    fun loadEpg(item: Item?) {
        if (item == null || item.kind != "live") return
        val src = activeSource() ?: return
        if (src.type != "xtream") return
        val now = System.currentTimeMillis()
        val last = epgFetchedAt[item.id]
        if (last != null && now - last < 10 * 60_000L) return
        epgFetchedAt[item.id] = now
        viewModelScope.launch {
            val list = runCatching {
                withContext(Dispatchers.IO) { Repository.shortEpg(src, item.id) }
            }.getOrNull() ?: return@launch
            epgRaw = epgRaw + (item.id to list)
            rebuildEpg()
        }
    }

    /** Ham akisa kanal grubunun saat duzeltmesini uygular. */
    private fun rebuildEpg() {
        epg = epgRaw.mapValues { (id, list) ->
            val group = liveById[id]?.group
            val off = if (group == null) 0L else store.epgOffset(group) * 3_600_000L
            if (off == 0L) list else list.map { it.copy(start = it.start + off, end = it.end + off) }
        }
    }

    fun epgOffsetLabel(item: Item?): String {
        if (item == null) return "-"
        val h = store.epgOffset(item.group)
        return (if (h > 0) "+$h" else "$h") + " saat · grup: " + item.group
    }

    /** Izlenen kanalin grubu icin yayin akisi saatini kaydirir: 0, -1, -2, -3, +1, +2, +3. */
    fun cycleEpgOffset() {
        val ch = currentChannel ?: return
        val opts = listOf(0, -1, -2, -3, 1, 2, 3)
        val cur = store.epgOffset(ch.group)
        val next = opts[(opts.indexOf(cur) + 1) % opts.size]
        store.setEpgOffset(ch.group, next)
        rebuildEpg()
        val now = nowNext(ch).first
        toast("Akış saati: " + (if (next > 0) "+$next" else "$next") + " saat" + (if (now != null) " · Şimdi: ${now.title}" else ""))
    }

    /** Simdiki ve siradaki program. */
    fun nowNext(item: Item?): Pair<EpgEntry?, EpgEntry?> {
        if (item == null) return null to null
        val list = epg[item.id] ?: return null to null
        val now = System.currentTimeMillis()
        val cur = list.firstOrNull { now >= it.start && now < it.end }
        val next = list.firstOrNull { it.start >= (cur?.end ?: now) }
        return cur to next
    }

    // ---------- Hesap ----------
    fun loadAccount() {
        val src = activeSource() ?: return
        if (src.type != "xtream") { account = null; return }
        viewModelScope.launch {
            account = runCatching { withContext(Dispatchers.IO) { Repository.accountInfo(src) } }.getOrNull()
        }
    }

    fun accountLabel(): String {
        val src = activeSource() ?: return "-"
        if (src.type != "xtream") return "M3U kaynağında bilgi yok"
        val a = account ?: return "yükleniyor…"
        val conn = if (a.maxConnections > 0) "${a.maxConnections} bağlantı (şu an ${a.activeConnections} açık)" else "bağlantı sayısı bilinmiyor"
        val exp = if (a.expires > 0) " · bitiş " + java.text.SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()).format(java.util.Date(a.expires)) else ""
        return conn + exp
    }

    private fun connectionWarning() {
        val max = account?.maxConnections ?: 0
        if (max == 1) toast("Uyarı: hesabın tek bağlantılı. Kayıt sürerken izleme kesilebilir.")
    }

    // ---------- Kayit ----------
    /** Kayit icin yayin adresi: Xtream canli kanallarda TS (kaydi en saglam bicim). */
    fun recordUrl(item: Item): String =
        if (activeSource()?.type == "xtream" && item.kind == "live") item.url.substringBeforeLast('.') + ".ts"
        else item.url

    fun isRecording(item: Item?): Boolean = item != null && Recorder.isRecordingUrl(recordUrl(item))

    /** Izlenen kanalin kaydini baslatir / durdurur (elle durdurulana kadar). */
    fun toggleRecordCurrent() {
        val ch = currentChannel ?: return
        val ctx = getApplication<Application>()
        val running = Recorder.runningFor(recordUrl(ch))
        if (running != null) {
            Recorder.stop(ctx, running.job.id)
            toast("Kayıt durduruldu: ${ch.name}")
        } else {
            Recorder.recordNow(ctx, ch.name, ch.name, recordUrl(ch))
            toast("● Kayıt başladı: ${ch.name}")
            connectionWarning()
        }
    }

    /** Su anki programi bitene kadar kaydeder (EPG gerekir). */
    fun recordCurrentProgram() {
        val ch = currentChannel ?: return
        val cur = nowNext(ch).first
        if (cur == null) { toast("Bu kanalda yayın akışı yok"); return }
        Recorder.recordNow(getApplication(), ch.name, "${ch.name} - ${cur.title}", recordUrl(ch), cur.end)
        toast("● Kayıt: ${cur.title} (bitiş ${hhmm(cur.end)})")
        connectionWarning()
    }

    /** Yayin akisindan secilen programi kaydeder / planlar. */
    fun scheduleProgram(item: Item, e: EpgEntry) {
        val now = System.currentTimeMillis()
        val ctx = getApplication<Application>()
        val title = "${item.name} - ${e.title}"
        if (e.end <= now) { toast("Bu program bitti"); return }
        if (Recorder.scheduled.any { it.url == recordUrl(item) && it.start == e.start }) {
            toast("Bu program zaten planlı"); return
        }
        if (e.start <= now) {
            Recorder.recordNow(ctx, item.name, title, recordUrl(item), e.end)
            toast("● Kayıt başladı: ${e.title}")
        } else {
            Recorder.schedule(ctx, item.name, title, recordUrl(item), e.start, e.end)
            toast("Planlandı: ${hhmm(e.start)} ${e.title}")
        }
        connectionWarning()
    }

    private fun hhmm(ms: Long) = java.text.SimpleDateFormat("HH:mm", Locale.getDefault()).format(java.util.Date(ms))

    fun recStorageLabel(): String {
        val ctx = getApplication<Application>()
        return Recorder.volumeLabel(ctx, Recorder.storageIndex(ctx))
    }

    fun cycleRecStorage() {
        val ctx = getApplication<Application>()
        if (Recorder.volumes(ctx).size < 2) { toast("Takılı USB bellek bulunamadı"); return }
        toast("Kayıt yeri: " + Recorder.cycleStorage(ctx))
    }

    fun recordingFiles(): List<RecFile> = Recorder.files(getApplication())

    fun playRecording(f: File) {
        playVod(f.nameWithoutExtension, Uri.fromFile(f).toString(), Screen.RECORDINGS)
    }

    fun deleteRecording(f: File): Boolean {
        if (Recorder.running.values.any { it.path == f.absolutePath }) { toast("Önce kaydı durdurun"); return false }
        val ok = f.delete()
        toast(if (ok) "Silindi: ${f.nameWithoutExtension}" else "Silinemedi")
        return ok
    }

    fun stopRecording(id: String) = Recorder.stop(getApplication(), id)

    // ---------- Coklu ekran ----------
    var multiLayout by mutableIntStateOf(4)

    /** Coklu ekran karosu icin ayri oynatici (kucuk tampon). Cagiran release etmeli. */
    fun newTilePlayer(): ExoPlayer {
        val app = getApplication<Application>()
        val extractors = DefaultExtractorsFactory().setTsExtractorFlags(
            DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or
                DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS
        )
        val load = DefaultLoadControl.Builder().setBufferDurationsMs(5_000, 15_000, 1_000, 2_000).build()
        return ExoPlayer.Builder(app, renderers(app))
            .setMediaSourceFactory(
                // DefaultDataSource: http(s) -> OkHttp, file:// (kayitlar) -> dosya
                DefaultMediaSourceFactory(app, extractors).setDataSourceFactory(
                    DefaultDataSource.Factory(app, OkHttpDataSource.Factory(Http.streamClient).setUserAgent(USER_AGENT))
                )
            )
            .setLoadControl(load)
            .build()
    }

    fun liveUrlOf(item: Item): String = liveUrl(item)

    /** Coklu ekran baslangici: izlenen kanal + favoriler (yoksa ayni gruptakiler). */
    fun multiInitial(): List<Item?> {
        val out = ArrayList<Item?>()
        currentChannel?.let { out.add(it) }
        val pool = itemsIn("live", FAV_GROUP).ifEmpty { itemsIn("live", liveGroup) }
        for (it in pool) {
            if (out.size >= 4) break
            if (out.none { o -> o?.id == it.id }) out.add(it)
        }
        while (out.size < 4) out.add(null)
        return out
    }

    /** Coklu ekrandan secilen karoyu tam ekranda ac. */
    fun fullscreenFromMulti(item: Item?) {
        screen = Screen.LIVE
        if (item != null) playLive(item)
    }

    // ---------- Diziler ----------
    fun openSeries(item: Item) {
        if (item.url.isNotEmpty()) { playVod(item.name, item.url, Screen.SERIES); return }
        val src = activeSource() ?: return
        episodesLoading = true
        episodesTitle = item.name
        viewModelScope.launch {
            try {
                val list = withContext(Dispatchers.IO) { Repository.seriesEpisodes(src, item.id) }
                if (list.isEmpty()) toast("Bölüm bulunamadı") else episodes = list
            } catch (e: Throwable) {
                toast("Bölümler alınamadı: ${e.message}")
            }
            episodesLoading = false
        }
    }

    fun closeEpisodes() { episodes = null }

    // ---------- Ayarlar ----------
    fun toggleExt() {
        streamExt = if (streamExt == "m3u8") "ts" else "m3u8"
        store.streamExt = streamExt
        toast("Yayın formatı: $streamExt")
    }

    fun cycleResize() {
        resizeMode = (resizeMode + 1) % RESIZE_LABELS.size
        store.resizeMode = resizeMode
        toast("Görüntü: ${RESIZE_LABELS[resizeMode]}")
    }

    fun cycleBuffer() {
        bufferMode = (bufferMode + 1) % BUFFER_LABELS.size
        store.bufferMode = bufferMode
        toast("Tampon: ${BUFFER_LABELS[bufferMode]} · Uygulama yeniden başlatınca geçerli olur")
    }

    fun cycleStartMode() {
        startMode = (startMode + 1) % START_LABELS.size
        store.startMode = startMode
        toast("Açılışta: ${START_LABELS[startMode]}")
    }

    fun cycleSleep() {
        val i = SLEEP_OPTIONS.indexOf(sleepMinutes)
        setSleep(SLEEP_OPTIONS[(i + 1) % SLEEP_OPTIONS.size])
    }

    private fun setSleep(min: Int) {
        sleepJob?.cancel()
        sleepMinutes = min
        if (min == 0) {
            sleepEndsAt = 0L
            toast("Uyku zamanlayıcı kapalı")
            return
        }
        sleepEndsAt = System.currentTimeMillis() + min * 60_000L
        toast("Uygulama $min dk sonra kapanacak")
        sleepJob = viewModelScope.launch {
            delay(min * 60_000L - 60_000L)
            toast("Uyku zamanlayıcı: 1 dakika sonra kapanıyor")
            delay(60_000L)
            sleepMinutes = 0
            sleepEndsAt = 0L
            stopPlayback()
            exitRequested = true
        }
    }

    fun sleepLabel(): String {
        if (sleepMinutes == 0) return "Kapalı"
        val left = ((sleepEndsAt - System.currentTimeMillis()) / 60_000L).coerceAtLeast(0) + 1
        return "$sleepMinutes dk (kalan ~$left dk)"
    }

    /** Uygulamayi kapatip yeniden acar. */
    fun restartApp() {
        val ctx = getApplication<Application>()
        val intent = Intent.makeRestartActivityTask(ComponentName(ctx, MainActivity::class.java))
        ctx.startActivity(intent)
        Runtime.getRuntime().exit(0)
    }

    fun exportBackup(): String {
        return try {
            "Yedeklendi: ${store.createBackup()}"
        } catch (e: Exception) {
            "Yedekleme hatası: ${e.localizedMessage ?: e.javaClass.simpleName}"
        }
    }

    /** Yedegi bilinen yerlerde arar (Download, uygulama klasoru). Bulunamazsa null doner. */
    fun importBackup(): String? {
        return try {
            if (store.restoreBackup()) applyRestoredState() else null
        } catch (e: Exception) {
            "Yükleme hatası: ${e.localizedMessage ?: e.javaClass.simpleName}"
        }
    }

    /** Dosya seciciyle secilen yedek dosyasini geri yukler. */
    fun importBackupFromUri(uri: Uri): String {
        return try {
            val text = getApplication<Application>().contentResolver.openInputStream(uri)
                ?.use { it.readBytes().toString(Charsets.UTF_8) }
                ?: return "Dosya açılamadı"
            if (store.restoreFromText(text)) applyRestoredState() else "Bu bir dedplay yedek dosyası değil"
        } catch (e: Exception) {
            "Yükleme hatası: ${e.localizedMessage ?: e.javaClass.simpleName}"
        }
    }

    private fun applyRestoredState(): String {
        sources = store.loadSources()
        activeId = store.activeSourceId
        favorites = store.loadSet("fav")
        hidden = store.loadSet("hidden")
        streamExt = store.streamExt
        stopPlayback()
        currentChannel = null
        liveGroup = FAV_GROUP
        val src = activeSource()
        if (src != null) {
            if (activeId != src.id) { activeId = src.id; store.activeSourceId = src.id }
            val cached = store.loadLibrary(src.id)
            if (cached != null) updateLibrary(cached) else refresh()
        } else {
            library = null
            rebuild(null)
        }
        return "Yedek yüklendi (${sources.size} kaynak, ${favorites.size} favori)"
    }

    fun toast(msg: String) {
        toastText = msg
        toastJob?.cancel()
        toastJob = viewModelScope.launch { delay(2500); toastText = null }
    }

    // ---------- Medya oturumu (direksiyon tuslari) ----------
    private fun setupMediaSession() {
        mediaSession.setCallback(object : MediaSession.Callback() {
            override fun onSkipToNext() { mediaNext() }
            override fun onSkipToPrevious() { mediaPrevious() }
            override fun onFastForward() { mediaNext() }
            override fun onRewind() { mediaPrevious() }
            override fun onPlay() { if (!player.playWhenReady) togglePause() }
            override fun onPause() { if (player.playWhenReady) togglePause() }
            override fun onStop() { if (player.playWhenReady) togglePause() }
        })
        updateSessionState(false)
        mediaSession.isActive = true
    }

    private fun updateSessionState(playing: Boolean) {
        val actions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
            PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_SKIP_TO_NEXT or
            PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_FAST_FORWARD or
            PlaybackState.ACTION_REWIND or PlaybackState.ACTION_STOP
        mediaSession.setPlaybackState(
            PlaybackState.Builder()
                .setActions(actions)
                .setState(
                    if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                    PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f
                )
                .build()
        )
    }

    private fun updateSessionTitle(title: String) {
        mediaSession.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, "dedplay")
                .build()
        )
    }

    /** Sonraki: canli TV'de sonraki kanal, filmde 30 sn ileri. */
    fun mediaNext() {
        when (screen) {
            Screen.LIVE -> zap(1)
            Screen.PLAYER -> player.seekTo(player.currentPosition + 30_000)
            else -> {}
        }
    }

    /** Onceki: canli TV'de onceki kanal, filmde 30 sn geri. */
    fun mediaPrevious() {
        when (screen) {
            Screen.LIVE -> zap(-1)
            Screen.PLAYER -> player.seekTo((player.currentPosition - 30_000).coerceAtLeast(0))
            else -> {}
        }
    }

    /** Oynat / duraklat. Canli yayinda devam ederken kanal bastan acilir (bayat yayin kalmasin). */
    fun togglePause() {
        if (player.playWhenReady) {
            player.playWhenReady = false
            if (screen == Screen.LIVE) toast("Duraklatıldı")
        } else if (screen == Screen.LIVE) {
            reloadCurrent()
        } else {
            player.playWhenReady = true
        }
    }

    override fun onCleared() {
        runCatching { mediaSession.release() }
        player.release()
    }
}
