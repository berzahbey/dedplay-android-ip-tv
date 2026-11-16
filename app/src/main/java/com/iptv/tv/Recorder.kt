package com.iptv.tv

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** Suren bir kaydin durumu. */
data class RecProgress(val job: RecJob, val path: String, val bytes: Long, val error: String? = null)

/** Kayit dosyasi (Kayitlar ekrani icin). */
data class RecFile(val file: File, val volume: String)

/**
 * Kanal kayitlarini yonetir. Kayitlar RecordService (on plan servisi) icinde calisir,
 * boylece uygulama arka plandayken de surer. Planli kayitlar SharedPreferences'ta saklanir.
 */
object Recorder {
    /** Baslangictan once / bitisten sonra pay (EPG saatleri tam tutmayabilir). */
    const val PAD_BEFORE_MS = 60_000L
    const val PAD_AFTER_MS = 2 * 60_000L

    var scheduled by mutableStateOf<List<RecJob>>(emptyList())
        private set
    var running by mutableStateOf<Map<String, RecProgress>>(emptyMap())
        private set

    private val jobs = ConcurrentHashMap<String, Job>()
    private val calls = ConcurrentHashMap<String, Call>()
    private val kick = Channel<Unit>(Channel.CONFLATED)
    private var loaded = false
    private var scope: CoroutineScope? = null

    /** Servisteki zamanlayici dongusu calisiyor mu */
    @Volatile
    var loopActive = false
        private set

    private val client by lazy {
        Http.client.newBuilder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    // ---------- Kalici liste ----------
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("iptv", Context.MODE_PRIVATE)

    fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        val arr = runCatching { JSONArray(prefs(ctx).getString("recJobs", "[]")) }.getOrElse { JSONArray() }
        val list = ArrayList<RecJob>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            list.add(
                RecJob(
                    o.optString("id"), o.optString("channel"), o.optString("title"),
                    o.optString("url"), o.optLong("start"), o.optLong("end")
                )
            )
        }
        // Suresi gecmis planlari at
        val now = System.currentTimeMillis()
        scheduled = list.filter { it.end == 0L || it.end + PAD_AFTER_MS > now }
    }

    private fun save(ctx: Context) {
        val arr = JSONArray()
        scheduled.forEach {
            arr.put(
                JSONObject().put("id", it.id).put("channel", it.channel).put("title", it.title)
                    .put("url", it.url).put("start", it.start).put("end", it.end)
            )
        }
        prefs(ctx).edit().putString("recJobs", arr.toString()).apply()
    }

    // ---------- Kayit yeri ----------
    /** Kullanilabilir kayit klasorleri: [0] TV hafizasi, sonrakiler USB / SD kart. */
    fun volumes(ctx: Context): List<File> =
        ctx.getExternalFilesDirs("Recordings").filterNotNull().ifEmpty { listOf(File(ctx.filesDir, "Recordings")) }

    fun volumeLabel(ctx: Context, index: Int): String {
        val vols = volumes(ctx)
        val f = vols.getOrNull(index) ?: return "-"
        val free = f.usableSpace / (1024L * 1024 * 1024)
        val name = if (index == 0) "TV hafızası" else "USB / harici ($index)"
        return "$name · $free GB boş"
    }

    fun storageIndex(ctx: Context): Int {
        val i = prefs(ctx).getInt("recVolume", 0)
        return if (i in volumes(ctx).indices) i else 0
    }

    fun cycleStorage(ctx: Context): String {
        val n = volumes(ctx).size
        val next = (storageIndex(ctx) + 1) % n
        prefs(ctx).edit().putInt("recVolume", next).apply()
        return volumeLabel(ctx, next)
    }

    /** Tum klasorlerdeki kayit dosyalari, yeniden eskiye. */
    fun files(ctx: Context): List<RecFile> {
        val out = ArrayList<RecFile>()
        volumes(ctx).forEachIndexed { i, dir ->
            dir.listFiles()?.filter { it.isFile && it.name.endsWith(".ts") }?.forEach {
                out.add(RecFile(it, if (i == 0) "TV" else "USB"))
            }
        }
        return out.sortedByDescending { it.file.lastModified() }
    }

    // ---------- Is ekleme / durdurma ----------
    /** Hemen baslayan kayit (end = 0: elle durdurulana kadar). */
    fun recordNow(ctx: Context, channel: String, title: String, url: String, end: Long = 0L): RecJob {
        val job = RecJob(newId(), channel, title, url, System.currentTimeMillis(), end)
        add(ctx, job)
        return job
    }

    /** Ileri bir saate planli kayit. */
    fun schedule(ctx: Context, channel: String, title: String, url: String, start: Long, end: Long): RecJob {
        val job = RecJob(newId(), channel, title, url, start, end)
        add(ctx, job)
        return job
    }

    private fun add(ctx: Context, job: RecJob) {
        load(ctx)
        scheduled = scheduled + job
        save(ctx)
        ensureService(ctx)
        kick.trySend(Unit)
    }

    /** Suren kaydi durdurur veya planli kaydi iptal eder. */
    fun stop(ctx: Context, id: String) {
        calls.remove(id)?.cancel()
        jobs.remove(id)?.cancel()
        if (scheduled.any { it.id == id }) {
            scheduled = scheduled.filter { it.id != id }
            save(ctx)
        }
        kick.trySend(Unit)
    }

    fun isRecordingUrl(url: String) = running.values.any { it.job.url == url }

    fun runningFor(url: String): RecProgress? = running.values.firstOrNull { it.job.url == url }

    /** Planli ya da suren kayit varsa servisi baslatir (uygulama acilisinda da cagrilir). */
    fun ensureService(ctx: Context) {
        load(ctx)
        if (scheduled.isEmpty() && running.isEmpty()) return
        val intent = Intent(ctx, RecordService::class.java)
        runCatching {
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(intent) else ctx.startService(intent)
        }
    }

    // ---------- Servis tarafindan cagrilir ----------
    /** Servis basladiginda zamanlayici dongusunu kurar. Bitince onIdle cagrilir. */
    fun attach(ctx: Context, serviceScope: CoroutineScope, onUpdate: () -> Unit, onIdle: () -> Unit) {
        load(ctx)
        scope = serviceScope
        loopActive = true
        serviceScope.launch {
            try {
                while (true) {
                    startDue(ctx, serviceScope)
                    withContext(Dispatchers.Main) { onUpdate() }
                    if (scheduled.isEmpty() && jobs.isEmpty()) break
                    withTimeoutOrNull(10_000L) { kick.receive() }
                }
            } finally {
                loopActive = false
            }
            withContext(Dispatchers.Main) { onIdle() }
        }
    }

    fun detach() {
        jobs.values.forEach { it.cancel() }
        calls.values.forEach { it.cancel() }
        jobs.clear(); calls.clear()
        running = emptyMap()
        scope = null
    }

    private fun startDue(ctx: Context, sc: CoroutineScope) {
        val now = System.currentTimeMillis()
        val due = scheduled.filter { it.start - PAD_BEFORE_MS <= now }
        if (due.isEmpty()) return
        scheduled = scheduled - due.toSet()
        save(ctx)
        for (job in due) {
            if (job.end != 0L && job.end + PAD_AFTER_MS <= now) continue // kacirilmis
            jobs[job.id] = sc.launch(Dispatchers.IO) { runJob(ctx, job) }
        }
    }

    private suspend fun setRunning(map: Map<String, RecProgress>) =
        withContext(Dispatchers.Main) { running = map }

    private suspend fun runJob(ctx: Context, job: RecJob) {
        val dir = volumes(ctx)[storageIndex(ctx)]
        dir.mkdirs()
        val stamp = SimpleDateFormat("yyyy-MM-dd_HH.mm", Locale.getDefault()).format(Date())
        val file = File(dir, safeName(job.title.ifEmpty { job.channel }) + "_" + stamp + ".ts")
        val stopAt = if (job.end == 0L) Long.MAX_VALUE else job.end + PAD_AFTER_MS
        var progress = RecProgress(job, file.absolutePath, 0L)
        setRunning(running + (job.id to progress))
        try {
            FileOutputStream(file, true).use { out ->
                val written = LongArray(1)
                val report: suspend () -> Unit = {
                    progress = progress.copy(bytes = written[0], error = null)
                    setRunning(running + (job.id to progress))
                }
                var failures = 0
                while (System.currentTimeMillis() < stopAt) {
                    currentCoroutineContext().ensureActive()
                    try {
                        if (job.url.contains(".m3u8", ignoreCase = true)) {
                            recordHls(job, out, written, stopAt, report)
                        } else {
                            recordTs(job, out, written, stopAt, report)
                        }
                        failures = 0
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        currentCoroutineContext().ensureActive()
                        failures++
                        progress = progress.copy(error = "Bağlantı koptu, yeniden deneniyor ($failures)")
                        setRunning(running + (job.id to progress))
                        delay(if (failures < 5) 2_000L else 10_000L)
                    }
                }
            }
        } finally {
            calls.remove(job.id)
            jobs.remove(job.id)
            withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main) {
                running = running - job.id
            }
            if (file.length() == 0L) file.delete()
            kick.trySend(Unit)
        }
    }

    /** Duz TS yayinini dosyaya yazar; baglanti koparsa istisna firlatir (cagiran yeniden baglanir). */
    private suspend fun recordTs(
        job: RecJob, out: FileOutputStream, written: LongArray, stopAt: Long, report: suspend () -> Unit
    ) {
        val call = client.newCall(Request.Builder().url(job.url).header("User-Agent", USER_AGENT).build())
        calls[job.id] = call
        call.execute().use { resp ->
            if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}")
            val input = resp.body!!.byteStream()
            val buf = ByteArray(64 * 1024)
            var lastReport = 0L
            while (System.currentTimeMillis() < stopAt) {
                currentCoroutineContext().ensureActive()
                val n = input.read(buf)
                if (n < 0) throw RuntimeException("Yayın bitti")
                out.write(buf, 0, n)
                written[0] = written[0] + n.toLong()
                val now = System.currentTimeMillis()
                if (now - lastReport > 2_000L) { lastReport = now; report() }
            }
        }
    }

    /** HLS (m3u8) yayininin parcalarini sirayla indirip tek TS dosyasina ekler. */
    private suspend fun recordHls(
        job: RecJob, out: FileOutputStream, written: LongArray, stopAt: Long, report: suspend () -> Unit
    ) {
        var playlistUrl = job.url
        val seen = LinkedHashSet<String>()
        while (System.currentTimeMillis() < stopAt) {
            currentCoroutineContext().ensureActive()
            val text = fetchText(job.id, playlistUrl)
            val base = playlistUrl.toHttpUrlOrNull() ?: throw RuntimeException("Geçersiz adres")
            val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
            if (lines.any { it.startsWith("#EXT-X-STREAM-INF") }) {
                // Ana liste: ilk kalite secenegine gec
                val variant = lines.firstOrNull { !it.startsWith("#") } ?: throw RuntimeException("Boş liste")
                playlistUrl = base.resolve(variant)?.toString() ?: throw RuntimeException("Geçersiz adres")
                continue
            }
            val target = lines.firstOrNull { it.startsWith("#EXT-X-TARGETDURATION") }
                ?.substringAfter(':')?.toDoubleOrNull() ?: 6.0
            val segments = lines.filter { !it.startsWith("#") }.mapNotNull { base.resolve(it)?.toString() }
            for (seg in segments) {
                if (seg in seen) continue
                currentCoroutineContext().ensureActive()
                val call = client.newCall(Request.Builder().url(seg).header("User-Agent", USER_AGENT).build())
                calls[job.id] = call
                call.execute().use { resp ->
                    if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}")
                    val bytes = resp.body!!.bytes()
                    out.write(bytes)
                    written[0] = written[0] + bytes.size.toLong()
                }
                seen.add(seg)
                if (seen.size > 500) seen.remove(seen.first())
                report()
            }
            if (lines.any { it.startsWith("#EXT-X-ENDLIST") }) return
            delay((target * 500).toLong().coerceIn(1_000L, 10_000L))
        }
    }

    private fun fetchText(id: String, url: String): String {
        val call = client.newCall(Request.Builder().url(url).header("User-Agent", USER_AGENT).build())
        calls[id] = call
        return call.execute().use { resp ->
            if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}")
            resp.body!!.string()
        }
    }

    private fun newId() = System.currentTimeMillis().toString() + (100..999).random()

    private fun safeName(s: String): String =
        s.replace(Regex("[\\\\/:*?\"<>|]"), "_").replace(Regex("\\s+"), " ").trim().take(60).ifEmpty { "kayit" }
}
