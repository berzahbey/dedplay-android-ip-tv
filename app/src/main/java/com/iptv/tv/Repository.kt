package com.iptv.tv

import android.util.JsonReader
import android.util.JsonToken
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.URLEncoder
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

object Http {
    private val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    })

    private val sslContext = SSLContext.getInstance("SSL").apply {
        init(null, trustAllCerts, SecureRandom())
    }

    val client: OkHttpClient = OkHttpClient.Builder()
        .sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
        .hostnameVerifier { _, _ -> true }
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    /**
     * Oynatici icin ayri istemci: yayin 15 sn veri gondermezse baglanti hata verir
     * ve oynatici kanali yeniden acar (liste indirmede uzun bekleme suresi korunur).
     */
    val streamClient: OkHttpClient = client.newBuilder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /** Kanal taramasi icin kisa sureli istemci. */
    private val probeClient: OkHttpClient = client.newBuilder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS)
        .build()

    /**
     * Yayin adresi cevap veriyor mu? Sadece ilk birkac KB okunur, baglanti hemen kapatilir.
     * m3u8 ise icerigin gercekten bir HLS listesi olmasi beklenir.
     */
    fun isAlive(url: String): Boolean {
        if (!url.startsWith("http", ignoreCase = true)) return true
        return try {
            val req = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
            probeClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return false
                val body = resp.body ?: return false
                val buf = ByteArray(2048)
                val n = body.byteStream().read(buf)
                if (n <= 0) return false
                val head = String(buf, 0, n, Charsets.ISO_8859_1)
                val isHls = url.contains(".m3u8", true) || (resp.header("Content-Type") ?: "").contains("mpegurl", true)
                !isHls || head.contains("#EXT")
            }
        } catch (_: Throwable) {
            false
        }
    }

    fun get(url: String): okhttp3.Response {
        val req = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        val resp = try {
            client.newCall(req).execute()
        } catch (e: Exception) {
            throw RuntimeException("Bağlantı kurulamadı: ${e.localizedMessage ?: e.javaClass.simpleName}", e)
        }
        if (!resp.isSuccessful) {
            val code = resp.code
            val msg = when (code) {
                401, 403 -> "Erişim engellendi (HTTP $code). Kullanıcı adı/şifre veya sunucu engeli olabilir."
                404 -> "Bulunamadı (HTTP 404). Sunucu adresini ve Xtream desteğini kontrol edin."
                500, 502, 503, 504 -> "Sunucu hatası (HTTP $code). IPTV sunucusu geçici olarak kapalı veya yoğun olabilir."
                else -> "Sunucu hata verdi (HTTP $code)"
            }
            resp.close()
            throw RuntimeException(msg)
        }
        return resp
    }
}

object Repository {

    fun load(src: Source, ext: String): Library = when (src.type) {
        "m3u" -> loadM3u(src)
        "m3ufile" -> loadM3uFile(src)
        else -> loadXtream(src, ext)
    }

    /** Cihaza yuklenmis M3U dosyasi (uygulama klasorune kopyalanmis hali). */
    private fun loadM3uFile(src: Source): Library {
        val f = java.io.File(src.url)
        if (!f.exists()) throw RuntimeException("M3U dosyası bulunamadı. Kaynağı silip dosyayı yeniden ekleyin.")
        val parentDir = f.parentFile.absolutePath
        val resolvedContent = f.readLines(Charsets.UTF_8).map { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) trimmed
            else if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true) || trimmed.startsWith("rtmp://", ignoreCase = true)) trimmed
            else if (trimmed.startsWith("/")) trimmed
            else "$parentDir/$trimmed"
        }.joinToString("\n")
        val tempFile = java.io.File("${parentDir}/.temp_m3u_${System.currentTimeMillis()}.m3u")
        tempFile.writeText(resolvedContent, Charsets.UTF_8)
        return tempFile.bufferedReader(Charsets.UTF_8).use { M3uParser.parse(it) }
    }

    private fun loadM3u(src: Source): Library {
        Http.get(src.url).use { resp ->
            val reader = BufferedReader(InputStreamReader(resp.body!!.byteStream(), Charsets.UTF_8), 64 * 1024)
            return M3uParser.parse(reader)
        }
    }

    // ---------- Xtream ----------

    private fun base(src: Source) = src.host.trim().trimEnd('/')

    private fun api(src: Source, action: String = "", extra: String = ""): String {
        val u = URLEncoder.encode(src.user, "UTF-8")
        val p = URLEncoder.encode(src.pass, "UTF-8")
        val a = if (action.isEmpty()) "" else "&action=$action"
        return "${base(src)}/player_api.php?username=$u&password=$p$a$extra"
    }

    /** Giris bilgisini dogrular ve gerekirse calisan protokolu (http/https) dondurur. */
    fun checkAndResolveXtream(src: Source): Source {
        val lastException: Throwable = try {
            checkXtream(src)
            return src
        } catch (e: Throwable) {
            e
        }

        // http:// -> https:// veya https:// -> http:// donusumu ile alternatif protokol dene
        val altHost = if (src.host.startsWith("http://", ignoreCase = true)) {
            src.host.replaceFirst(Regex("(?i)^http://"), "https://")
        } else if (src.host.startsWith("https://", ignoreCase = true)) {
            src.host.replaceFirst(Regex("(?i)^https://"), "http://")
        } else null

        if (altHost != null) {
            val altSrc = src.copy(host = altHost)
            try {
                checkXtream(altSrc)
                return altSrc // Alternatif protokol calisti!
            } catch (_: Throwable) {
                // Alternatif de calismadi
            }
        }

        throw lastException
    }

    /** Giris bilgisini dogrular; hata varsa istisna firlatir. */
    fun checkXtream(src: Source) {
        val responseText = Http.get(api(src)).use { it.body!!.string() }
        val json = try {
            JSONObject(responseText)
        } catch (e: Exception) {
            throw RuntimeException("Sunucudan Xtream yanıtı yerine farklı bir içerik (HTML/Metin) döndü. Adresi kontrol edin.")
        }

        val info = json.optJSONObject("user_info")
            ?: throw RuntimeException(
                json.optString("error", "Geçersiz Xtream yanıtı. Kullanıcı adı veya şifre hatalı olabilir.")
            )

        if (info.optString("auth") != "1") {
            throw RuntimeException("Kullanıcı adı veya şifre hatalı")
        }
        val status = info.optString("status")
        if (status.isNotEmpty() && !status.equals("Active", true)) {
            throw RuntimeException("Hesap durumu pasif: $status")
        }
    }

    private fun loadXtream(src: Source, ext: String): Library {
        checkXtream(src)
        val b = base(src)
        val live = categories(src, "get_live_categories").let { cats ->
            streams(src, "get_live_streams", cats, "live") { m ->
                val id = m["stream_id"] ?: return@streams null
                Triple(id, m["stream_icon"] ?: "", "$b/live/${src.user}/${src.pass}/$id.$ext")
            }
        }
        val movies = runCatching {
            categories(src, "get_vod_categories").let { cats ->
                streams(src, "get_vod_streams", cats, "movie") { m ->
                    val id = m["stream_id"] ?: return@streams null
                    val e = (m["container_extension"] ?: "mp4").ifEmpty { "mp4" }
                    Triple(id, m["stream_icon"] ?: "", "$b/movie/${src.user}/${src.pass}/$id.$e")
                }
            }
        }.getOrDefault(emptyList())
        val series = runCatching {
            categories(src, "get_series_categories").let { cats ->
                streams(src, "get_series", cats, "series") { m ->
                    val id = m["series_id"] ?: return@streams null
                    Triple(id, m["cover"] ?: "", "")
                }
            }
        }.getOrDefault(emptyList())
        return Library(live, movies, series, System.currentTimeMillis())
    }

    /** Kategoriler, saglayicinin verdigi sirayla (LinkedHashMap sirayi korur). */
    private fun categories(src: Source, action: String): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        Http.get(api(src, action)).use { resp ->
            readObjects(resp.body!!.charStream()) { m ->
                val id = m["category_id"]
                if (id != null) map[id] = m["category_name"] ?: "Diğer"
            }
        }
        return map
    }

    private fun streams(
        src: Source,
        action: String,
        cats: Map<String, String>,
        kind: String,
        build: (Map<String, String>) -> Triple<String, String, String>?
    ): List<Item> {
        val out = ArrayList<Pair<Int, Item>>()
        // Gruplar saglayicinin kategori sirasinda gelsin (kanal sirasi grup icinde korunur)
        val catOrder = HashMap<String, Int>()
        cats.keys.forEachIndexed { i, id -> catOrder[id] = i }
        Http.get(api(src, action)).use { resp ->
            readObjects(resp.body!!.charStream()) { m ->
                val t = build(m) ?: return@readObjects
                val cid = m["category_id"] ?: ""
                val group = cats[cid] ?: "Diğer"
                out.add((catOrder[cid] ?: Int.MAX_VALUE) to Item(t.first, m["name"] ?: "?", t.second, group, t.third, kind))
            }
        }
        return out.sortedBy { it.first }.map { it.second }
    }

    /** Bir dizinin bolumlerini getirir. */
    fun seriesEpisodes(src: Source, seriesId: String): List<Episode> {
        val out = ArrayList<Episode>()
        Http.get(api(src, "get_series_info", "&series_id=$seriesId")).use { resp ->
            val eps = JSONObject(resp.body!!.string()).optJSONObject("episodes") ?: return out
            val b = base(src)
            val seasons = eps.keys().asSequence().toList().sortedBy { it.toIntOrNull() ?: 0 }
            for (s in seasons) {
                val arr = eps.optJSONArray(s) ?: continue
                for (i in 0 until arr.length()) {
                    val e = arr.getJSONObject(i)
                    val id = e.optString("id")
                    val ext = e.optString("container_extension", "mp4").ifEmpty { "mp4" }
                    val num = e.optString("episode_num", (i + 1).toString())
                    val title = e.optString("title").ifEmpty { "Bölüm $num" }
                    out.add(Episode(id, title, s.toIntOrNull() ?: 1, "$b/series/${src.user}/${src.pass}/$id.$ext"))
                }
            }
        }
        return out
    }

    /** Xtream hesap bilgisi: baglanti sayisi, bitis tarihi. */
    fun accountInfo(src: Source): AccountInfo {
        val json = JSONObject(Http.get(api(src)).use { it.body!!.string() })
        val info = json.optJSONObject("user_info") ?: throw RuntimeException("Hesap bilgisi alınamadı")
        val exp = info.optString("exp_date").toLongOrNull()?.times(1000) ?: 0L
        return AccountInfo(
            maxConnections = info.optString("max_connections").toIntOrNull() ?: 0,
            activeConnections = info.optString("active_cons").toIntOrNull() ?: 0,
            expires = exp,
            status = info.optString("status")
        )
    }

    /** Xtream kisa yayin akisi: simdiki ve siradaki programlar. */
    fun shortEpg(src: Source, streamId: String, limit: Int = 10): List<EpgEntry> {
        val text = Http.get(api(src, "get_short_epg", "&stream_id=$streamId&limit=$limit")).use { it.body!!.string() }
        val arr = JSONObject(text).optJSONArray("epg_listings") ?: return emptyList()
        val out = ArrayList<EpgEntry>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val start = o.optString("start_timestamp").toLongOrNull() ?: continue
            val end = o.optString("stop_timestamp").toLongOrNull() ?: continue
            val title = decodeB64(o.optString("title"))
            if (title.isEmpty()) continue
            out.add(EpgEntry(title, start * 1000, end * 1000, decodeB64(o.optString("description"))))
        }
        return out.sortedBy { it.start }
    }

    /** Xtream EPG metinleri Base64 gelir; cozulemezse oldugu gibi kullanilir. */
    private fun decodeB64(s: String): String {
        if (s.isEmpty()) return ""
        return try {
            String(android.util.Base64.decode(s, android.util.Base64.DEFAULT), Charsets.UTF_8).trim()
        } catch (_: Exception) {
            s.trim()
        }
    }

    /** JSON dizisindeki her nesneyi duz String->String haritasi olarak verir (akis halinde). */
    private fun readObjects(reader: java.io.Reader, onObject: (Map<String, String>) -> Unit) {
        JsonReader(reader).use { jr ->
            if (jr.peek() != JsonToken.BEGIN_ARRAY) return
            jr.beginArray()
            while (jr.hasNext()) {
                if (jr.peek() != JsonToken.BEGIN_OBJECT) { jr.skipValue(); continue }
                val m = HashMap<String, String>()
                jr.beginObject()
                while (jr.hasNext()) {
                    val key = jr.nextName()
                    when (jr.peek()) {
                        JsonToken.STRING, JsonToken.NUMBER -> m[key] = jr.nextString()
                        JsonToken.BOOLEAN -> m[key] = jr.nextBoolean().toString()
                        JsonToken.NULL -> jr.nextNull()
                        else -> jr.skipValue()
                    }
                }
                jr.endObject()
                onObject(m)
            }
            jr.endArray()
        }
    }
}