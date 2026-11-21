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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.datasource.HttpDataSource
import android.content.res.Configuration
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.hls.DefaultHlsExtractorFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
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
    var backExit by mutableIntStateOf(store.backExit)
    var refreshMode by mutableIntStateOf(store.refreshMode.coerceIn(0, REFRESH_LABELS.size - 1))
    /** Gizlenen tek tek kanallar: "kaynakId|kanalId" */
    var hiddenCh by mutableStateOf(store.loadSet("hiddenCh"))
    /** M3U taramasinda yayin vermedigi gorulen kanallar: "kaynakId|kanalId" */
    var deadCh by mutableStateOf(store.loadSet("deadCh"))
    /** "Calismayanlari gizle" hangi listelerde acik (liste bazinda; kaynak id'leri). */
    var hideDeadSrc by mutableStateOf(initHideDeadSrc())
    /** Aktif listede calismayan kanallar gizli mi? */
    val hideDead: Boolean get() = activeId in hideDeadSrc

    private fun initHideDeadSrc(): Set<String> {
        val set = store.loadSet("hideDeadSrc")
        // Eski surumde tek ve ortak ayardi: acikti ise tum M3U listelerinde acik say
        if (set.isEmpty() && store.hideDead) {
            val all = store.loadSources().filter { it.type == "m3u" || it.type == "m3ufile" }.map { it.id }.toSet()
            store.saveSet("hideDeadSrc", all)
            store.hideDead = false
            return all
        }
        return set
    }
    /** Tarama surerken ilerleme yazisi, yoksa null. */
    var scanProgress by mutableStateOf<String?>(null)
    /** GitHub'da daha yeni surum varsa bilgisi. */
    var updateInfo by mutableStateOf<UpdateInfo?>(null)
    /** Guncelleme denetimi / indirme surerken durum yazisi. */
    var updateStatus by mutableStateOf<String?>(null)
    private var updateJob: Job? = null
    private var downloadedUpdate: java.io.File? = null
    private var scanJob: Job? = null
    /** Izlenen yayinin teknik bilgisi: "FHD 1080p · H.264 · 50 fps · AAC Stereo" */
    var mediaInfo by mutableStateOf("")
    /** Canli kanal, Ayarlar gibi bir ekran acikken sessizce arkada caliyor (donunce aninda devam). */
    private var liveInBackground = false
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
    private var refreshJob: Job? = null
    private var accountJob: Job? = null
    private var episodesJob: Job? = null
    private var lastFrames = -1
    private var failCount = 0
    /** Cihaz bu kanalin goruntusunu cozemiyor (orn. tablette 4K HEVC): yeniden deneme yapma. */
    private var decoderBlocked = false
    /** Android TV mi (Amlogic vb. donanim cozuculer yayina tam kare olmadan girince cokebiliyor). */
    private val isTv = (app.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK) ==
        Configuration.UI_MODE_TYPE_TELEVISION
    /** Su anki oynatma canli yayin mi */
    private var liveMode = false
    /** Canli: yayin tam kare (IDR) beklemeden mi aciliyor (varsayilan; hizli acilis) */
    private var liveLenient = true
    /** TV'de cozucusu cokup tam kareyle (IDR) acilan kanallar */
    private val strictUrls = HashSet<String>()
    /** Donanim cozucusu coktugu icin yazilim cozucusuyle acilan kanallar */
    private val swVideoUrls = HashSet<String>()
    /** Ana oynaticinin kodek secicisi okur (oynatma is parcaciginda) */
    @Volatile private var preferSwVideo = false
    private var decoderRetries = 0
    private var gotFrame = false
    private var strictJob: Job? = null
    /** Saglayici baglantiyi reddetti (407/403 vb.): daha seyrek yeniden dene */
    private var slowRetry = false
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
    private fun renderers(app: Application) = FfmpegAudioRenderersFactory(app).setEnableDecoderFallback(true)

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
        // Ana oynatici: gerekirse goruntu icin yazilim cozucusunu one alir (donanim cozucusu cokunce).
        // (Burada yerel: player sinif basinda kuruldugu icin sinif alani henuz hazir olmazdi.)
        val codecSelector = MediaCodecSelector { mime, secure, tunneling ->
            val list = MediaCodecSelector.DEFAULT.getDecoderInfos(mime, secure, tunneling)
            if (preferSwVideo && mime.startsWith("video/")) list.sortedBy { if (it.softwareOnly) 0 else 1 } else list
        }
        return ExoPlayer.Builder(app, renderers(app).setMediaCodecSelector(codecSelector))
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
        com.iptv.tv.ui.ThemeState.index = store.theme.coerceIn(0, com.iptv.tv.ui.THEMES.size - 1)
        // Son izleme sekli hatirlansin: coklu ekran mi, tam ekran canli mi (hangi yoldan gecilirse gecilsin)
        viewModelScope.launch {
            snapshotFlow { screen }.collect { s ->
                if (s == Screen.MULTI) store.lastScreenMulti = true
                else if (s == Screen.LIVE) store.lastScreenMulti = false
            }
        }
        store.cleanupOldCaches()
        Recorder.load(app)
        Recorder.ensureService(app)
        setupMediaSession()
        applyLanguagePrefs()
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                // Ilk hatadan sonraki yeniden denemede de yukleniyor simgesi gorunsun
                buffering = state == Player.STATE_BUFFERING ||
                    (state == Player.STATE_IDLE && failCount == 1 && isLiveActive())
                if (state != Player.STATE_READY && isLiveActive() && player.playWhenReady && !decoderBlocked) {
                    armWatch(if (state == Player.STATE_BUFFERING) 12_000L else if (slowRetry) 8_000L else 1_500L)
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) { playerMessage = null; failCount = 0; slowRetry = false }
                updateSessionState(isPlaying)
            }

            override fun onPlayerError(error: PlaybackException) {
                // Uzantisiz HLS adresi: ayni adresi HLS olarak hemen yeniden ac
                if (markHlsIfUnrecognized(playingUrl, error)) {
                    setSource(playingUrl)
                    player.prepare()
                    player.playWhenReady = true
                    return
                }
                // TV: donanim goruntu cozucusu coktu (orn. Mi Box'ta 720p H.264 "HD" kanallar).
                // Once bu kanalda yazilim cozucusune gec; yine cokerse tam kareyle (IDR) ac.
                // Diger kanallar etkilenmez (eskisi gibi hizli acilir).
                if (isTv && liveMode && isVideoCodecCrash(error) && decoderRetries < 2) {
                    decoderRetries++
                    if (!preferSwVideo) {
                        swVideoUrls.add(playingUrl)
                        preferSwVideo = true
                    } else {
                        liveLenient = false
                        strictUrls.add(playingUrl)
                    }
                    reloadCurrent()
                    return
                }
                if (isVideoDecoderLimit(error)) {
                    // Yeniden denemek ise yaramaz: cihazin donanimi bu goruntuyu cozemiyor
                    decoderBlocked = true
                    watchJob?.cancel()
                    buffering = false
                    playerMessage = videoLimitMessage(error)
                    return
                }
                val reason = errorReason(error)
                if (screen == Screen.LIVE) {
                    failCount++
                    if (isRefused(error)) {
                        // Saglayici reddediyor (cogu zaman baglanti limiti): 1,5 sn'de bir zorlama
                        slowRetry = true
                        buffering = false
                        playerMessage = "Sağlayıcı bağlantıyı reddetti ($reason)\nBağlantı limiti dolmuş olabilir – yeniden deneniyor…"
                        armWatch(8_000L)
                        return
                    }
                    // Canli yayinda bekci (armWatch) 1,5 sn sonra kanali yeniden acar.
                    // Ilk hatada uyari gosterme (cogu sunucu ikinci denemede acar), sadece yukleniyor simgesi.
                    buffering = failCount < 2
                    if (failCount >= 2) playerMessage = "Yayın açılamadı ($reason) – yeniden deneniyor…"
                } else {
                    buffering = false
                    playerMessage = "Yayın açılamadı ($reason)"
                }
            }

            override fun onTracksChanged(tracks: Tracks) {
                tracksVersion++
                autoPickAudio()
                updateMediaInfo()
            }

            override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                updateMediaInfo()
            }

            override fun onRenderedFirstFrame() {
                gotFrame = true
                updateMediaInfo()
            }
        })
        val src = activeSource()
        if (src != null) {
            val cached = store.loadLibrary(src.id)
            if (cached != null) {
                updateLibrary(cached)
                if (isStale(cached)) refresh()
            } else refresh()
        }
        loadAccount()
        // Gunde bir kez sessiz guncelleme denetimi
        if (System.currentTimeMillis() - store.lastUpdateCheck > 24L * 3600 * 1000) checkUpdate(manual = false)
        else Updater.cleanup(app)
    }

    // ---------- Uygulama ici guncelleme ----------
    fun appVersion(): String = Updater.currentVersion(getApplication())

    /** GitHub'da yeni surum var mi? manual=false ise sadece yeni surum bulununca haber verir. */
    fun checkUpdate(manual: Boolean) {
        if (updateJob?.isActive == true) return
        if (manual) updateStatus = "Denetleniyor…"
        updateJob = viewModelScope.launch {
            val latest = try {
                withContext(Dispatchers.IO) { Updater.fetchLatest() }
            } catch (_: Throwable) { null }
            store.lastUpdateCheck = System.currentTimeMillis()
            updateStatus = null
            val cur = appVersion()
            if (latest != null && Updater.isNewer(latest.version, cur)) {
                updateInfo = latest
                toast("Yeni sürüm var: v${latest.version} · Ayarlar > Güncelle")
            } else {
                updateInfo = null
                if (manual) toast(if (latest == null) "Güncelleme bilgisi alınamadı (internet?)" else "En son sürümü kullanıyorsunuz (v$cur)")
            }
        }
    }

    /** Yeni surumu indirir ve Android'in kurulum ekranini acar. */
    fun startUpdate() {
        val info = updateInfo ?: run { checkUpdate(manual = true); return }
        val ctx = getApplication<Application>()
        if (updateJob?.isActive == true) return
        if (!Updater.canInstall(ctx)) {
            toast("Önce bu uygulamaya yükleme izni verin, sonra tekrar Güncelle'ye basın")
            Updater.openInstallPermission(ctx)
            return
        }
        downloadedUpdate?.takeIf { it.exists() }?.let { runCatching { Updater.install(ctx, it) }; return }
        updateJob = viewModelScope.launch {
            try {
                updateStatus = "İndiriliyor… %0"
                val file = withContext(Dispatchers.IO) {
                    Updater.download(ctx, info) { p ->
                        viewModelScope.launch(Dispatchers.Main) { updateStatus = "İndiriliyor… %$p" }
                    }
                }
                downloadedUpdate = file
                updateStatus = null
                Updater.install(ctx, file)
            } catch (e: Throwable) {
                updateStatus = null
                toast("Güncelleme başarısız: ${e.message ?: e.javaClass.simpleName}")
            }
        }
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

    /** Herhangi bir kaynagi siler (aktifse bir sonrakine gecer); coklu ekrandaki karolarindan da cikarir. */
    fun deleteSource(id: String) {
        if (id == activeId) deleteActive() else removeSource(id)
        if (id in hideDeadSrc) { hideDeadSrc = hideDeadSrc - id; store.saveSet("hideDeadSrc", hideDeadSrc) }
        val p = "$id|"
        if (deadCh.any { it.startsWith(p) }) { deadCh = deadCh.filterNot { it.startsWith(p) }.toSet(); store.saveSet("deadCh", deadCh) }
        var changed = false
        for (i in multiTiles.indices) {
            if (multiTiles[i]?.sourceId == id) { multiTiles[i] = null; changed = true }
        }
        if (changed) store.saveMultiTiles(multiTiles.toList())
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
        refreshJob?.cancel()
        accountJob?.cancel()
        loading = false
        account = null
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
        if (cached != null) { updateLibrary(cached); if (isStale(cached)) refresh() } else refresh()
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
        // Ayni anda iki indirme olmasin ve eski kaynagin yaniti yeni kaynagi ezmesin
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            loading = true
            loadError = null
            try {
                val lib = withContext(Dispatchers.IO) {
                    Repository.load(src, streamExt).also { store.saveLibrary(src.id, it) }
                }
                if (activeId == src.id) updateLibrary(lib)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (activeId == src.id) loadError = "Liste yüklenemedi: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                if (activeId == src.id) loading = false
            }
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

    fun liveGroupsWithFav(): List<String> = listOf(FAV_GROUP, ALL_GROUP) + groups("live")

    private var allLiveCache: Triple<Library?, Set<String>, List<Item>>? = null

    /** Gizli gruplar haric tum canli kanallar (liste sirasiyla). */
    private fun allLive(): List<Item> {
        val lib = library
        val hid = hidden
        allLiveCache?.let { if (it.first === lib && it.second === hid) return it.third }
        val list = lib?.live?.filter { "live:${it.group}" !in hid } ?: emptyList()
        allLiveCache = Triple(lib, hid, list)
        return list
    }

    fun itemsIn(kind: String, group: String): List<Item> {
        library; favorites; hiddenCh; deadCh; hideDeadSrc; activeId
        if (kind == "live") return visibleLive(group, liveBase(group))
        return idx[kind]?.get(group) ?: emptyList()
    }

    private fun liveBase(group: String): List<Item> {
        val kind = "live"
        if (group == ALL_GROUP) return allLive()
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
        if (kind == "live" && liveGroup != FAV_GROUP && liveGroup != ALL_GROUP && liveGroup !in groups("live")) {
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
    private fun liveUrl(item: Item): String = liveUrlOf(activeSource()?.id ?: activeId, item)

    private fun isLiveActive() = screen == Screen.LIVE && playingUrl.isNotEmpty()

    private fun play(url: String, live: Boolean = false) {
        playingUrl = url
        liveMode = live
        liveLenient = url !in strictUrls
        preferSwVideo = url in swVideoUrls
        decoderRetries = 0
        slowRetry = false
        playerMessage = null
        lastFrames = -1
        failCount = 0
        decoderBlocked = false
        liveInBackground = false
        player.volume = 1f
        mediaInfo = ""
        // Onceki kanalin ses/altyazi secimi yeni kanala tasinmasin (dil tercihi kalir)
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().clearOverrides().build()
        setSource(url)
        player.prepare()
        player.playWhenReady = true
    }

    /**
     * Kaynagi oynaticiya verir. Normalde eskisi gibi (esnek, hizli). Sadece TV'de cozucusu coken
     * kanallar tam kareyle (IDR) acilir; 5 sn icinde goruntu gelmezse esnek moda geri donulur.
     */
    private fun setSource(url: String) {
        gotFrame = false
        strictJob?.cancel()
        if (!(isTv && liveMode) || liveLenient) {
            player.setMediaItem(mediaItemFor(url))
            return
        }
        player.setMediaSource(tileMediaSource(url, false))
        strictJob = viewModelScope.launch {
            repeat(4) {
                delay(5_000L)
                if (gotFrame || playingUrl != url || liveLenient || !liveMode) return@launch
                if (player.playbackState == Player.STATE_READY) {
                    liveLenient = true
                    strictUrls.remove(url)
                    reloadCurrent()
                    return@launch
                }
            }
        }
    }

    // ---------- HLS tanima ----------
    // Uzantisi olmayan (orn. M3U film linkleri) HLS adresleri ExoPlayer tarafindan taninmaz.
    // Icerigi onceden indirmek yerine (canli TS akislarinda bitmez ve fazladan baglanti acar)
    // once normal acilir; "taninmayan format" hatasi gelirse ayni adres HLS olarak yeniden denenir.
    private val hlsUrls = HashSet<String>()

    /** Adres icin MediaItem: .m3u8 veya daha once HLS oldugu anlasilan adreslere HLS tipi verilir. */
    fun mediaItemFor(url: String): MediaItem =
        if (url in hlsUrls || url.substringBefore('?').lowercase().endsWith(".m3u8"))
            MediaItem.Builder().setUri(url).setMimeType(MimeTypes.APPLICATION_M3U8).build()
        else MediaItem.fromUri(url)

    /** Taninmayan format hatasinda adresi HLS olarak isaretler; ilk kez isaretlendiyse true. */
    fun markHlsIfUnrecognized(url: String?, e: PlaybackException): Boolean {
        if (url.isNullOrEmpty()) return false
        if (e.errorCode != PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED) return false
        return hlsUrls.add(url)
    }

    private fun stopPlayback() {
        watchJob?.cancel()
        player.stop()
        playingUrl = ""
        liveInBackground = false
        player.volume = 1f
        mediaInfo = ""
    }

    fun playLive(item: Item) {
        // Film / dizi ogesi canli TV ekraninda acilmasin: film oynaticisinda (sarma, duraklatma) ac
        if (item.kind == "movie" || item.kind == "series") {
            playVod(item.name, item.url, if (screen == Screen.MULTI) Screen.MULTI else Screen.MOVIES)
            return
        }
        val cur = currentChannel
        if (cur != null && cur.id != item.id) previousChannel = cur
        currentChannel = item
        store.setLastChannel(activeId, item.id)
        updateSessionTitle(item.name)
        play(liveUrl(item), live = true)
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
        // Uygulamadan coklu ekrandayken cikildiysa yine coklu ekranla ac (karolar kayitli)
        val target = if (store.lastScreenMulti && sources.isNotEmpty()) Screen.MULTI else Screen.LIVE
        if (screen != target) openScreen(target)
    }

    /** Canli TV ekrani acilinca kanali baslatir. */
    private var noLiveHintFor: Library? = null

    fun ensureLive() {
        val lib = library ?: return
        // Sadece film / dizi iceren liste (orn. film M3U'su): liste ilk acildiginda ilk film baslasin
        // (bir kez; filmden Canli TV'ye donulunce tekrar baslatilmaz)
        if (lib.live.isEmpty() && (lib.movies.isNotEmpty() || lib.series.isNotEmpty())) {
            if (noLiveHintFor !== lib) {
                noLiveHintFor = lib
                val movie = groups("movie").firstOrNull()?.let { itemsIn("movie", it).firstOrNull() }
                    ?: lib.movies.firstOrNull()
                if (movie != null && movie.url.isNotEmpty()) {
                    val g = groups("movie").firstOrNull()
                    val list = (if (g != null) itemsIn("movie", g) else lib.movies).map { it.name to it.url }
                    val i = list.indexOfFirst { it.second == movie.url }.coerceAtLeast(0)
                    playVodQueue(list, i, Screen.MOVIES, "movie")
                }
                else openScreen(Screen.SERIES)
            }
            return
        }
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
            ch = liveById[store.lastChannelFor(activeId)]
            if (ch != null) {
                currentChannel = ch
                liveGroup = if (ch.group in groups("live")) ch.group else ALL_GROUP
            } else {
                // Liste ilk kez aciliyor: favorilerdeki ilk kanal, favori yoksa "Tüm Kanallar"in ilki
                // (gizlenen / calismayan kanallar atlanir)
                val fav = itemsIn("live", FAV_GROUP).firstOrNull()
                if (fav != null) { ch = fav; liveGroup = FAV_GROUP }
                else { ch = itemsIn("live", ALL_GROUP).firstOrNull() ?: lib.live.firstOrNull(); liveGroup = ALL_GROUP }
                if (ch == null) return
                currentChannel = ch
            }
        }
        if (playingUrl != liveUrl(ch)) {
            store.setLastChannel(activeId, ch.id)
            updateSessionTitle(ch.name)
            play(liveUrl(ch), live = true)
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

    /** Film / dizi ekraninda son grup, secili oge ve sutun (oynaticidan donunce ayni yere donulur). */
    val browseGroup = HashMap<String, Int>()
    val browseItem = HashMap<String, Int>()
    val browseCol = HashMap<String, Int>()

    // Film / bolum sirasi: oynaticida asagi/yukari kaydirinca ayni listedeki onceki / sonraki
    private var vodQueue: List<Pair<String, String>> = emptyList()
    private var vodQueueIdx = -1
    private var vodQueueKind = ""

    /** Listeden film / bolum oynatir; ayni listede onceki / sonraki icin sira hatirlanir. */
    fun playVodQueue(list: List<Pair<String, String>>, index: Int, back: Screen, kind: String) {
        val it = list.getOrNull(index) ?: return
        vodQueue = list
        vodQueueIdx = index
        vodQueueKind = kind
        playVod(it.first, it.second, back)
    }

    /** Ayni listedeki sonraki (+1) / onceki (-1) film / bolum. */
    fun nextVod(delta: Int) {
        if (vodQueue.isEmpty()) return
        val n = vodQueueIdx + delta
        if (n !in vodQueue.indices) { toast(if (delta > 0) "Listenin sonu" else "Listenin başı"); return }
        if (vodQueueKind.isNotEmpty()) browseItem[vodQueueKind] = n
        playVodQueue(vodQueue, n, vodReturn, vodQueueKind)
        toast(vodQueue[n].first)
    }

    fun playVod(title: String, url: String, back: Screen) {
        vodTitle = title
        vodReturn = back
        updateSessionTitle(title)
        if (url.isEmpty()) {
            toast("Bu kanalin yayin adresi bos")
            return
        }
        stopPlayback()
        play(url)
        screen = Screen.PLAYER
    }

    fun stopVod() {
        stopPlayback()
        screen = vodReturn
    }

    fun openScreen(s: Screen) {
        val liveNow = (screen == Screen.LIVE || liveInBackground) && playingUrl.isNotEmpty()
        when {
            s == Screen.LIVE -> if (liveInBackground) {
                // Ayarlar vb. ekrandan donus: kanal zaten arkada caliyor, bastan acma
                liveInBackground = false
                player.volume = 1f
                if (player.playbackState == Player.STATE_IDLE || !player.playWhenReady) reloadCurrent()
                else { lastFrames = -1; armWatch(10_000L) }
            }
            s in KEEP_LIVE_SCREENS && liveNow -> {
                liveInBackground = true
                player.volume = 0f
                watchJob?.cancel()
            }
            s != Screen.PLAYER -> stopPlayback()
        }
        if (s != Screen.SERIES) closeEpisodes()
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

    /** Bilgi bandi icin cozunurluk / codec / fps / ses bilgisi. */
    private fun updateMediaInfo() {
        val v = player.videoFormat
        val a = player.audioFormat
        val parts = ArrayList<String>()
        if (v != null) {
            val h = if (player.videoSize.height > 0) player.videoSize.height else v.height
            when {
                h >= 2000 -> parts.add("4K UHD")
                h >= 1000 -> parts.add("FHD 1080p")
                h >= 700 -> parts.add("HD 720p")
                h > 0 -> parts.add("SD ${h}p")
            }
            codecName(v.sampleMimeType)?.let { parts.add(it) }
            if (v.frameRate > 0f) parts.add("${Math.round(v.frameRate)} fps")
        }
        if (a != null) {
            val ch = when (a.channelCount) {
                1 -> " Mono"; 2 -> " Stereo"; 6 -> " 5.1"; 8 -> " 7.1"
                else -> if (a.channelCount > 0) " ${a.channelCount}ch" else ""
            }
            parts.add((codecName(a.sampleMimeType) ?: "Ses") + ch)
        }
        mediaInfo = parts.joinToString(" · ")
    }

    private fun codecName(mime: String?): String? = when (mime) {
        null -> null
        "video/hevc" -> "HEVC"
        "video/avc" -> "H.264"
        "video/mpeg2" -> "MPEG-2"
        "video/av01" -> "AV1"
        "video/x-vnd.on2.vp9" -> "VP9"
        "audio/mp4a-latm" -> "AAC"
        "audio/ac3" -> "Dolby AC3"
        "audio/eac3", "audio/eac3-joc" -> "Dolby E-AC3"
        "audio/mpeg", "audio/mpeg-L2" -> "MPEG"
        "audio/mpeg-L1" -> "MPEG"
        "audio/opus" -> "Opus"
        "audio/vnd.dts", "audio/vnd.dts.hd" -> "DTS"
        else -> mime.substringAfter('/').uppercase()
    }

    /** Hata, cihazin goruntu cozucusunun bu formati kaldiramamasindan mi? */
    private fun isVideoDecoderLimit(e: PlaybackException): Boolean {
        val codes = setOf(
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED
        )
        if (e.errorCode !in codes) return false
        val mime = (e as? ExoPlaybackException)?.rendererFormat?.sampleMimeType ?: return false
        return mime.startsWith("video/")
    }

    private fun videoLimitMessage(e: PlaybackException): String {
        val f = (e as? ExoPlaybackException)?.rendererFormat
        val res = if (f != null && f.height >= 2000) "4K " else ""
        val codec = when (f?.sampleMimeType) {
            "video/hevc" -> "HEVC/H.265"
            "video/avc" -> "H.264"
            else -> f?.sampleMimeType?.removePrefix("video/")?.uppercase() ?: ""
        }
        return if (res.isNotEmpty()) "Bu cihaz bu kanalın ${res}görüntüsünü ($codec) çözemiyor.\nKanalın HD / FHD sürümünü deneyin."
        else "Bu cihaz bu kanalın görüntüsünü ($codec) çözemedi."
    }

    /** Donanim cozucusu yayin sirasinda coktu mu (4K / desteklenmeyen format degil). */
    private fun isVideoCodecCrash(e: PlaybackException): Boolean {
        if (e.errorCode != PlaybackException.ERROR_CODE_DECODING_FAILED &&
            e.errorCode != PlaybackException.ERROR_CODE_DECODER_INIT_FAILED) return false
        val f = (e as? ExoPlaybackException)?.rendererFormat ?: return false
        return f.sampleMimeType?.startsWith("video/") == true && f.height < 2000
    }

    private fun httpCode(e: Throwable): Int? {
        var t: Throwable? = e
        var depth = 0
        while (t != null && depth < 10) {
            val cur: Throwable = t
            if (cur is HttpDataSource.InvalidResponseCodeException) return cur.responseCode
            val msg = cur.message ?: ""
            if (msg.contains("HTTP", true)) {
                val m = Regex("[(]([0-9]{3})[)]").find(msg)
                if (m != null) return m.groupValues[1].toInt()
            }
            t = cur.cause; depth++
        }
        return null
    }

    /** Saglayici erisimi reddetti mi (baglanti limiti, yetki, engel). */
    private fun isRefused(e: PlaybackException): Boolean = httpCode(e) in setOf(401, 403, 407, 429, 458, 509)

    /** Hata icin kisa, okunur sebep (mesajlara eklenir). */
    private fun errorReason(e: PlaybackException): String {
        httpCode(e)?.let { return "HTTP $it" }
        return when (e.errorCode) {
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED -> "bağlantı kurulamadı"
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "zaman aşımı"
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> "bulunamadı"
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED -> "format okunamadı"
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED -> "çözücü hatası"
            PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
            PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED -> "ses çıkışı açılamadı"
            PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW -> "yayının gerisinde kalındı"
            else -> "kod ${e.errorCode}"
        }
    }

    private fun reloadCurrent() {
        if (playingUrl.isEmpty()) return
        lastFrames = -1
        setSource(playingUrl)
        player.prepare()
        player.playWhenReady = true
        armWatch(15_000L)
    }

    /** Ana ekrana gecince: canli yayin baglantisini tamamen kapat (asili kalmasin). */
    fun onBackground() {
        appVisible = false
        watchJob?.cancel()
        player.playWhenReady = false
        if (screen == Screen.LIVE || liveInBackground) player.stop()
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
            // Altyazi dili secilmediyse yayinin "varsayilan" isaretli altyazisi kendiliginden
            // acilmasin (orn. listede Sesotho varsayilan isaretli); zorunlu (forced) altyazilar kalir.
            "" -> b.setIgnoredTextSelectionFlags(C.SELECTION_FLAG_DEFAULT)
            "off" -> b.setIgnoredTextSelectionFlags(0).setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            else -> b.setIgnoredTextSelectionFlags(0).setPreferredTextLanguage(s)
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
        // Kaynak degisirse eski kaynagin hesap bilgisi gelmesin
        accountJob?.cancel()
        accountJob = viewModelScope.launch {
            val info = runCatching { withContext(Dispatchers.IO) { Repository.accountInfo(src) } }.getOrNull()
            if (activeId == src.id) account = info
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
    var multiLayout by mutableIntStateOf(store.loadMultiLayout())
    /** Coklu ekran karo goruntusu: 0 Sigdir, 1 Doldur (kirp), 2 Uzat. Varsayilan: Doldur. */
    var multiFit by mutableIntStateOf(store.multiFit)
    /** Coklu ekranda secili (sesi gelen) karo; uygulama tekrar acilinca ayni karo secili gelir. */
    var multiFocus: Int
        get() = store.multiFocus
        set(v) { store.multiFocus = v }

    fun cycleMultiFit() {
        multiFit = (multiFit + 1) % 3
        store.multiFit = multiFit
        toast("Karo görüntüsü: " + MULTI_FIT_LABELS[multiFit])
    }

    /** Coklu ekran kartlari: her karo kendi kaynagini ve kanalini tutar.
     *  AppViewModel'de durdugu icin ekran gecislerinde (MULTI<->LIVE) kaybolmaz. */
    val multiTiles = mutableStateListOf<MultiChannel?>()

    /** Coklu ekran secicisinde o an gozatilan kaynak (her karo farkli kaynaktan olabilir). */
    var multiPickSourceId by mutableStateOf("")
        private set

    /** Secili kaynagin listesi yukleniyor mu? */
    var multiPickLoading by mutableStateOf(false)
        private set

    /*
     * Aktif kaynak disinda yalnizca tek bir kaynagin kutuphanesi bellekte tutulur;
     * boylece cok sayida kaynak eklense de coklu ekran bellegi sismez.
     */
    private var multiExtraSourceId: String? = null
    private var multiExtraLib by mutableStateOf<Library?>(null)
    private var multiPickJob: Job? = null

    fun sourceName(id: String): String = sources.firstOrNull { it.id == id }?.name ?: "-"

    /** Coklu ekran karosu icin yayin adresi: kanalin geldigi kaynagin turune gore. */
    fun liveUrlOf(sourceId: String, item: Item): String =
        if (sources.firstOrNull { it.id == sourceId }?.type == "xtream" && item.kind == "live")
            item.url.substringBeforeLast('.') + "." + streamExt
        else item.url

    private fun multiPickLibrary(): Library? =
        if (multiPickSourceId == activeId) library
        else if (multiExtraSourceId == multiPickSourceId) multiExtraLib
        else null

    /** Secicide gozatilacak kaynagi ayarlar; listesi yoksa onbellege alir / indirir. */
    fun beginMultiPick(sourceId: String) {
        val src = sources.firstOrNull { it.id == sourceId } ?: activeSource() ?: return
        val same = src.id == multiPickSourceId
        if (same && (src.id == activeId || multiExtraSourceId == src.id || multiPickLoading)) return
        multiPickSourceId = src.id
        multiPickJob?.cancel()
        multiPickLoading = false
        if (src.id == activeId) return
        if (multiExtraSourceId == src.id && multiExtraLib != null) return
        // Onceki ekstra kaynagin listesini bellekten dusur
        multiExtraSourceId = null
        multiExtraLib = null
        multiPickLoading = true
        multiPickJob = viewModelScope.launch {
            try {
                val lib = withContext(Dispatchers.IO) {
                    store.loadLibrary(src.id)
                        ?: Repository.load(src, streamExt).also { store.saveLibrary(src.id, it) }
                }
                if (multiPickSourceId != src.id) return@launch
                multiExtraSourceId = src.id
                multiExtraLib = lib
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (multiPickSourceId == src.id) {
                    toast("Kaynak listesi alınamadı: ${e.message ?: e.javaClass.simpleName}")
                }
            } finally {
                if (multiPickSourceId == src.id) multiPickLoading = false
            }
        }
    }

    /** Secicide liste yukleniyor mu? (aktif kaynagin ilk yuklemesi dahil) */
    fun multiPickBusy(): Boolean =
        multiPickLoading || (multiPickSourceId == activeId && library == null && loading)

    /** Secicideki kaynakta, gizli gruplar haric, secilen icerik turune gore favoriler + tum ogeler + gruplar.
     *  contentType: "canli", "film" veya "dizi" */
    fun multiPickGroups(contentType: String): List<String> {
        multiPickSourceId; multiExtraLib; hidden
        val lib = multiPickLibrary() ?: return listOf(FAV_GROUP, ALL_GROUP)
        val (items, hiddenPrefix) = when (contentType) {
            "film" -> lib.movies to "movie"
            "dizi" -> lib.series to "series"
            else -> lib.live to "live"
        }
        val groups = items.asSequence()
            .map { it.group }
            .filter { "$hiddenPrefix:$it" !in hidden }
            .distinct()
            .toList()
        return listOf(FAV_GROUP, ALL_GROUP) + groups
    }

    /** Secicideki kaynak + grup icin ogeler (gizli / calismayan kanallar uygulanir).
     *  contentType: "canli", "film" veya "dizi" */
    fun multiPickItems(contentType: String, group: String): List<Item> {
        multiPickSourceId; multiExtraLib; favorites; hidden; hiddenCh; deadCh; hideDeadSrc
        val lib = multiPickLibrary() ?: return emptyList()
        val sourceId = multiPickSourceId
        val p = "$sourceId|"
        val hid = hidden
        val (items, hiddenPrefix) = when (contentType) {
            "film" -> lib.movies to "movie"
            "dizi" -> lib.series to "series"
            else -> lib.live to "live"
        }
        val pool = items.filter { "$hiddenPrefix:${it.group}" !in hid }
        val base = when (group) {
            ALL_GROUP -> pool
            FAV_GROUP -> pool.filter { "$p${it.id}" in favorites }
            else -> pool.filter { it.group == group }
        }
        val hc = hiddenCh
        // Coklu ekran seciciden bakilan listenin kendi "gizle" ayari
        val dc = if (sourceId in hideDeadSrc) deadCh else emptySet()
        if (hc.none { it.startsWith(p) } && dc.none { it.startsWith(p) }) return base
        return base.filter { "$p${it.id}" !in hc && "$p${it.id}" !in dc }
    }

    /** Secicideki kaynakta bu turde (canli / film / dizi) icerik var mi? Liste yuklenmediyse null. */
    fun multiPickHasContent(contentType: String): Boolean? {
        multiPickSourceId; multiExtraLib
        val lib = multiPickLibrary() ?: return null
        return when (contentType) {
            "film" -> lib.movies.isNotEmpty()
            "dizi" -> lib.series.isNotEmpty()
            else -> lib.live.isNotEmpty()
        }
    }

    fun cycleMultiPickSource() {
        if (sources.size < 2) { toast("Başka kaynak yok"); return }
        val i = sources.indexOfFirst { it.id == multiPickSourceId }
        val next = sources[(i + 1) % sources.size]
        beginMultiPick(next.id)
    }

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

    /**
     * Karo icin yayin kaynagi. lenient=false: goruntu tam kareyle (IDR) baslar, acilista renk lekesi olmaz.
     * lenient=true: tam kare gondermeyen yayinlar icin tam kareyi beklemeden baslar (karo siyah kalmasin).
     */
    fun tileMediaSource(url: String, lenient: Boolean): MediaSource {
        val app = getApplication<Application>()
        val ds = DefaultDataSource.Factory(app, OkHttpDataSource.Factory(Http.streamClient).setUserAgent(USER_AGENT))
        val tsFlags = if (lenient)
            DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS
        else DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS
        val item = mediaItemFor(url)
        return if (item.localConfiguration?.mimeType == MimeTypes.APPLICATION_M3U8)
            HlsMediaSource.Factory(ds).setExtractorFactory(DefaultHlsExtractorFactory(tsFlags, true)).createMediaSource(item)
        else DefaultMediaSourceFactory(app, DefaultExtractorsFactory().setTsExtractorFlags(tsFlags))
            .setDataSourceFactory(ds).createMediaSource(item)
    }

    /** Coklu ekrana giriste 4 karonu bir kez doldur. Sonraki girislerde kayitli secimler kalir. */
    fun multiEnsureInitial() {
        if (multiTiles.isNotEmpty()) return
        // Kayitli secimleri yukle
        val saved = store.loadMultiTiles()
        if (saved.isNotEmpty()) {
            multiTiles.addAll(saved)
            // Eksik karolari doldur
            while (multiTiles.size < 4) multiTiles.add(null)
            return
        }
        // Ilk kez aciliyorsa: aktif kaynaktan doldur
        val out = ArrayList<MultiChannel?>()
        val srcId = activeSource()?.id ?: activeId
        currentChannel?.let { out.add(MultiChannel(srcId, it)) }
        val pool = itemsIn("live", FAV_GROUP).ifEmpty { itemsIn("live", liveGroup) }
        for (it in pool) {
            if (out.size >= 4) break
            if (out.none { o -> o?.item?.id == it.id }) out.add(MultiChannel(srcId, it))
        }
        while (out.size < 4) out.add(null)
        multiTiles.addAll(out)
        store.saveMultiTiles(multiTiles.toList())
    }

    /** Coklu ekrandaki bir karoyu secilen kaynaga/kanala degistirir ve kalici olarak kaydeder. */
    fun setMultiTile(index: Int, ch: MultiChannel?) {
        if (index in multiTiles.indices) multiTiles[index] = ch
        store.saveMultiTiles(multiTiles.toList())
    }

    fun saveMultiLayout(layout: Int) {
        store.saveMultiLayout(layout)
    }

    /** Coklu ekrandan secilen karoyu tam ekranda ac (gerekirse kaynagini da degistirir). */
    fun fullscreenFromMulti(ch: MultiChannel?) {
        val item = ch?.item
        if (item == null) { screen = Screen.LIVE; return }
        if (ch.sourceId != activeId && sources.any { it.id == ch.sourceId }) setActive(ch.sourceId)
        if (item.kind == "movie" || item.kind == "series") {
            playVod(item.name, item.url, Screen.MULTI)
            return
        }
        screen = Screen.LIVE
        liveGroup = item.group
        playLive(item)
    }

    // ---------- Diziler ----------
    private var episodesSeriesId: String? = null

    fun openSeries(item: Item) {
        if (item.url.isNotEmpty()) { playVod(item.name, item.url, Screen.SERIES); return }
        val src = activeSource() ?: return
        // Onceki dizinin bolumleri yeni dizi yuklenirken ekranda kalmasin
        episodesJob?.cancel()
        episodesSeriesId = item.id
        episodes = null
        episodesLoading = true
        episodesTitle = item.name
        episodesJob = viewModelScope.launch {
            try {
                val list = withContext(Dispatchers.IO) { Repository.seriesEpisodes(src, item.id) }
                if (episodesSeriesId != item.id) return@launch
                if (list.isEmpty()) toast("Bölüm bulunamadı") else episodes = list
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (episodesSeriesId == item.id) toast("Bölümler alınamadı: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                if (episodesSeriesId == item.id) episodesLoading = false
            }
        }
    }

    fun closeEpisodes() {
        episodesJob?.cancel()
        episodesJob = null
        episodesSeriesId = null
        episodesLoading = false
        episodes = null
    }

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

    /** Kayitli liste, secilen yenileme suresinden eski mi? */
    private fun isStale(lib: Library): Boolean {
        val ms = REFRESH_MS.getOrElse(refreshMode) { -1L }
        if (ms < 0) return false
        return System.currentTimeMillis() - lib.updated >= ms
    }

    fun cycleRefreshMode() {
        refreshMode = (refreshMode + 1) % REFRESH_LABELS.size
        store.refreshMode = refreshMode
        toast("Liste otomatik yenileme: ${REFRESH_LABELS[refreshMode]}")
    }

    /** Ayarlarda gosterilecek: son guncelleme zamani. */
    fun libraryAgeLabel(): String {
        val t = library?.updated ?: return "-"
        val mins = (System.currentTimeMillis() - t) / 60_000
        return when {
            mins < 1 -> "az önce"
            mins < 60 -> "$mins dk önce"
            mins < 24 * 60 -> "${mins / 60} saat önce"
            else -> "${mins / (24 * 60)} gün önce"
        }
    }

    fun cycleTheme() {
        val n = (com.iptv.tv.ui.ThemeState.index + 1) % com.iptv.tv.ui.THEMES.size
        com.iptv.tv.ui.ThemeState.index = n
        store.theme = n
        toast("Tema: ${com.iptv.tv.ui.THEMES[n].name}")
    }

    fun cycleBackExit() {
        backExit = (backExit + 1) % BACK_LABELS.size
        store.backExit = backExit
        toast("Geri tuşu: ${BACK_LABELS[backExit]}")
    }

    // ---------- Kanal gizleme ----------
    private fun chKey(it: Item) = "$activeId|${it.id}"
    fun isChannelHidden(it: Item) = chKey(it) in hiddenCh

    fun toggleChannelHidden(it: Item): Boolean {
        val k = chKey(it)
        hiddenCh = if (k in hiddenCh) hiddenCh - k else hiddenCh + k
        store.saveSet("hiddenCh", hiddenCh)
        return k in hiddenCh
    }

    // ---------- M3U kanal taramasi ----------
    fun isM3uSource() = activeSource()?.type.let { it == "m3u" || it == "m3ufile" }
    fun isDead(it: Item) = "$activeId|${it.id}" in deadCh
    fun deadCount(): Int { val p = "$activeId|"; return deadCh.count { it.startsWith(p) } }

    /**
     * Sadece M3U kaynaklarinda: tum canli kanallari ayni anda en fazla 6 baglantiyla dener,
     * yayin vermeyenleri isaretler. (Xtream'de hesabin baglanti siniri yuzunden yapilmaz.)
     * Tarama surerken tekrar cagrilirsa durdurur.
     */
    fun toggleDeadScan() {
        if (scanJob?.isActive == true) {
            scanJob?.cancel(); scanJob = null; scanProgress = null
            toast("Tarama durduruldu")
            return
        }
        if (!isM3uSource()) { toast("Tarama sadece M3U kaynaklarında yapılabilir"); return }
        val list = library?.live.orEmpty()
        if (list.isEmpty()) { toast("Liste boş"); return }
        val srcId = activeId
        scanProgress = "0 / ${list.size}"
        scanJob = viewModelScope.launch {
            val dead = ConcurrentHashMap.newKeySet<String>()
            val done = AtomicInteger(0)
            val sem = Semaphore(6)
            coroutineScope {
                for (ch in list) {
                    launch(Dispatchers.IO) {
                        sem.withPermit {
                            if (!Http.isAlive(ch.url)) dead.add("$srcId|${ch.id}")
                        }
                        val n = done.incrementAndGet()
                        if (n % 5 == 0 || n == list.size) {
                            withContext(Dispatchers.Main) { scanProgress = "$n / ${list.size} · ${dead.size} çalışmıyor" }
                        }
                    }
                }
            }
            val p = "$srcId|"
            deadCh = deadCh.filterNot { it.startsWith(p) }.toSet() + dead
            store.saveSet("deadCh", deadCh)
            scanProgress = null
            scanJob = null
            toast("Tarama bitti: ${list.size} kanaldan ${dead.size} tanesi çalışmıyor")
        }
    }

    fun toggleHideDead() {
        val id = activeId
        hideDeadSrc = if (id in hideDeadSrc) hideDeadSrc - id else hideDeadSrc + id
        store.saveSet("hideDeadSrc", hideDeadSrc)
        val name = activeSource()?.name ?: ""
        toast(if (hideDead) "$name: çalışmayan kanallar gizlendi" else "$name: çalışmayan kanallar gösteriliyor")
    }

    fun clearDead() {
        val p = "$activeId|"
        deadCh = deadCh.filterNot { it.startsWith(p) }.toSet()
        store.saveSet("deadCh", deadCh)
    }

    fun hiddenChannelCount(): Int { val p = "$activeId|"; return hiddenCh.count { it.startsWith(p) } }

    fun clearHiddenChannels() {
        val p = "$activeId|"
        hiddenCh = hiddenCh.filterNot { it.startsWith(p) }.toSet()
        store.saveSet("hiddenCh", hiddenCh)
    }

    private var visKey: List<Any?> = emptyList()
    private val visCache = HashMap<String, List<Item>>()

    /** Gizlenen kanallari listeden cikarir (grup basina onbellekli). */
    private fun visibleLive(group: String, base: List<Item>): List<Item> {
        val p = "$activeId|"
        val hc = hiddenCh
        val dc = if (hideDead) deadCh else emptySet()
        if (hc.none { it.startsWith(p) } && dc.none { it.startsWith(p) }) return base
        val key = listOf(library, hidden, hc, dc, favorites, activeId)
        if (key.size != visKey.size || key.indices.any { key[it] !== visKey[it] }) { visCache.clear(); visKey = key }
        return visCache.getOrPut(group) { base.filter { "$p${it.id}" !in hc && "$p${it.id}" !in dc } }
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
        // Tam yedekten gelen ayarlar
        hiddenCh = store.loadSet("hiddenCh")
        deadCh = store.loadSet("deadCh")
        hideDeadSrc = initHideDeadSrc()
        resizeMode = store.resizeMode
        bufferMode = store.bufferMode
        startMode = store.startMode
        backExit = store.backExit
        refreshMode = store.refreshMode.coerceIn(0, REFRESH_LABELS.size - 1)
        com.iptv.tv.ui.ThemeState.index = store.theme.coerceIn(0, com.iptv.tv.ui.THEMES.size - 1)
        multiLayout = store.loadMultiLayout()
        multiFit = store.multiFit
        multiTiles.clear()
        applyLanguagePrefs()
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
