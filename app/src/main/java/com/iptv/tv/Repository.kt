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

    fun load(src: Source, ext: String): Library =
        if (src.type == "m3u") loadM3u(src) else loadXtream(src, ext)

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

    private fun categories(src: Source, action: String): Map<String, String> {
        val map = HashMap<String, String>()
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
        val out = ArrayList<Item>()
        Http.get(api(src, action)).use { resp ->
            readObjects(resp.body!!.charStream()) { m ->
                val t = build(m) ?: return@readObjects
                val group = cats[m["category_id"] ?: ""] ?: "Diğer"
                out.add(Item(t.first, m["name"] ?: "?", t.second, group, t.third, kind))
            }
        }
        return out
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