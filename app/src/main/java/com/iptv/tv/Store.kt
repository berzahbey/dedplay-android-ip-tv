package com.iptv.tv

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.ObjectInputStream
import java.io.ObjectOutputStream

/** Kaynaklar, favoriler, gizli gruplar ve onbellek burada saklanir. */
private const val BACKUP_NAME = "iptv_backup.json"

class Store(private val ctx: Context) {
    private val prefs = ctx.getSharedPreferences("iptv", Context.MODE_PRIVATE)

    fun loadSources(): List<Source> {
        // Bozuk / yarim yazilmis kayit uygulamanin acilista cokmesine yol acmasin:
        // okunamayan kaynak atlanir, hicbiri okunamiyorsa bos liste doner.
        val text = prefs.getString("sources", "[]") ?: "[]"
        val arr = runCatching { JSONArray(text) }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            runCatching {
                val o = arr.getJSONObject(i)
                Source(
                    o.getString("id"), o.getString("name"), o.getString("type"),
                    o.optString("url"), o.optString("host"), o.optString("user"), o.optString("pass")
                )
            }.getOrNull()
        }
    }

    fun saveSources(list: List<Source>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().put("id", it.id).put("name", it.name).put("type", it.type)
                .put("url", it.url).put("host", it.host).put("user", it.user).put("pass", it.pass))
        }
        prefs.edit().putString("sources", arr.toString()).apply()
    }

    var activeSourceId: String
        get() = prefs.getString("active", "") ?: ""
        set(v) { prefs.edit().putString("active", v).apply() }

    var streamExt: String
        get() = prefs.getString("ext", "m3u8") ?: "m3u8"
        set(v) { prefs.edit().putString("ext", v).apply() }

    var lastChannelId: String
        get() = prefs.getString("lastChannel", "") ?: ""
        set(v) { prefs.edit().putString("lastChannel", v).apply() }

    /** 0 = Sigdir, 1 = Doldur (kirp), 2 = Uzat */
    var resizeMode: Int
        get() = prefs.getInt("resize", 2)
        set(v) { prefs.edit().putInt("resize", v).apply() }

    /** 0 = Dusuk, 1 = Normal, 2 = Yuksek tampon */
    var bufferMode: Int
        get() = prefs.getInt("buffer", 1)
        set(v) { prefs.edit().putInt("buffer", v).apply() }

    /** Uygulama acilisi: 0 = favorilerdeki ilk kanal, 1 = son izlenen kanal */
    var startMode: Int
        get() = prefs.getInt("startMode", 0)
        set(v) { prefs.edit().putInt("startMode", v).apply() }

    /** Tema rengi (THEMES sirasi). */
    var theme: Int
        get() = prefs.getInt("theme", 0)
        set(v) { prefs.edit().putInt("theme", v).apply() }

    /** Son otomatik guncelleme denetimi (ms). */
    var lastUpdateCheck: Long
        get() = prefs.getLong("lastUpdateCheck", 0L)
        set(v) { prefs.edit().putLong("lastUpdateCheck", v).apply() }

    /** Otomatik liste yenileme: REFRESH_LABELS sirasi (varsayilan: haftada bir). */
    var refreshMode: Int
        get() = prefs.getInt("refreshMode", 3)
        set(v) { prefs.edit().putInt("refreshMode", v).apply() }

    /** M3U taramasinda calismayan bulunan kanallar gizlensin mi. */
    var hideDead: Boolean
        get() = prefs.getBoolean("hideDead", false)
        set(v) { prefs.edit().putBoolean("hideDead", v).apply() }

    /** Geri tusu: 0 = iki kez basinca cikar, 1 = cikmaz (sadece Home). */
    var backExit: Int
        get() = prefs.getInt("backExit", 0)
        set(v) { prefs.edit().putInt("backExit", v).apply() }

    /** Kanal grubuna gore yayin akisi saat duzeltmesi (saat). Saglayici saati yanlis girdiginde. */
    fun epgOffset(group: String): Int = prefs.getInt("epgOff:$group", 0)
    fun setEpgOffset(group: String, hours: Int) { prefs.edit().putInt("epgOff:$group", hours).apply() }

    /**
     * Tercih edilen ses dili. Varsayilan Turkce: tercih yoksa oynatici TV'nin
     * sistem dilini (orn. Hollandaca) secerdi. (Yeni anahtar: eski kayit sifirlanir.)
     */
    var audioLang: String
        get() = prefs.getString("audioLang2", "tr") ?: "tr"
        set(v) { prefs.edit().putString("audioLang2", v.ifEmpty { "tr" }).apply() }

    /** Altyazi tercihi: "" = varsayilan, "off" = kapali, diger = dil kodu */
    var subLang: String
        get() = prefs.getString("subLang", "") ?: ""
        set(v) { prefs.edit().putString("subLang", v).apply() }

    fun loadSet(key: String): Set<String> = HashSet(prefs.getStringSet(key, emptySet()) ?: emptySet())
    fun saveSet(key: String, v: Set<String>) { prefs.edit().putStringSet(key, HashSet(v)).apply() }

    // ----- yedekleme / geri yukleme -----
    /** Yedegi olusturur; nereye yazildigini dondurur. */
    fun createBackup(): String {
        val json = JSONObject().apply {
            put("version", 1)
            put("timestamp", System.currentTimeMillis())
            put("activeSourceId", activeSourceId)
            put("streamExt", streamExt)

            val sourcesArr = JSONArray()
            loadSources().forEach {
                sourcesArr.put(JSONObject().apply {
                    put("id", it.id)
                    put("name", it.name)
                    put("type", it.type)
                    put("url", it.url)
                    put("host", it.host)
                    put("user", it.user)
                    put("pass", it.pass)
                })
            }
            put("sources", sourcesArr)

            val favArr = JSONArray()
            loadSet("fav").forEach { favArr.put(it) }
            put("favorites", favArr)

            val hiddenArr = JSONArray()
            loadSet("hidden").forEach { hiddenArr.put(it) }
            put("hidden", hiddenArr)
        }

        val text = json.toString(2)
        // 1) Uygulamanin kendi klasoru (her zaman yazilabilir)
        val own = privateBackupFile()
        own.parentFile?.mkdirs()
        own.writeText(text, Charsets.UTF_8)
        // 2) Herkese acik Download klasoru (Android 10+ icin MediaStore uzerinden)
        val publicOk = runCatching { writePublicDownload(text) }.getOrDefault(false)
        return if (publicOk) "Download/$BACKUP_NAME" else own.absolutePath
    }

    fun restoreBackup(): Boolean {
        val text = readBackupText() ?: return false
        return restoreFromText(text)
    }

    /** Yedek metnini (iptv_backup.json icerigi) geri yukler. Gecersizse false. */
    fun restoreFromText(text: String): Boolean {
        val json = try { JSONObject(text) } catch (_: Exception) { return false }
        if (!json.has("sources")) return false

        if (json.has("sources")) {
            val arr = json.getJSONArray("sources")
            val list = (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                Source(
                    o.getString("id"), o.getString("name"), o.getString("type"),
                    o.optString("url"), o.optString("host"), o.optString("user"), o.optString("pass")
                )
            }
            saveSources(list)
        }

        if (json.has("activeSourceId")) {
            activeSourceId = json.optString("activeSourceId")
        }

        if (json.has("streamExt")) {
            streamExt = json.optString("streamExt", "m3u8")
        }

        if (json.has("favorites")) {
            val arr = json.getJSONArray("favorites")
            val set = (0 until arr.length()).map { arr.getString(it) }.toSet()
            saveSet("fav", set)
        }

        if (json.has("hidden")) {
            val arr = json.getJSONArray("hidden")
            val set = (0 until arr.length()).map { arr.getString(it) }.toSet()
            saveSet("hidden", set)
        }

        return true
    }

    private fun privateBackupFile(): File {
        val dir = ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: ctx.filesDir
        return File(dir, BACKUP_NAME)
    }

    private fun publicBackupFile(): File =
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), BACKUP_NAME)

    private fun writePublicDownload(text: String): Boolean {
        if (Build.VERSION.SDK_INT < 29) {
            val f = publicBackupFile()
            f.parentFile?.mkdirs()
            f.writeText(text, Charsets.UTF_8)
            return true
        }
        val resolver = ctx.contentResolver
        findOwnDownload()?.let { resolver.delete(it, null, null) }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, BACKUP_NAME)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return false
        val out = resolver.openOutputStream(uri) ?: return false
        out.use { it.write(text.toByteArray(Charsets.UTF_8)) }
        return true
    }

    /** Bu uygulamanin Download klasorune daha once yazdigi yedek (Android 10+). */
    private fun findOwnDownload(): Uri? {
        if (Build.VERSION.SDK_INT < 29) return null
        val coll = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        ctx.contentResolver.query(
            coll, arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.DISPLAY_NAME}=?", arrayOf(BACKUP_NAME), null
        )?.use { c ->
            if (c.moveToFirst()) return ContentUris.withAppendedId(coll, c.getLong(0))
        }
        return null
    }

    /** Yedegi sirasiyla: Download (MediaStore), Download (dosya), uygulama klasoru. */
    private fun readBackupText(): String? {
        runCatching {
            findOwnDownload()?.let { uri ->
                ctx.contentResolver.openInputStream(uri)?.use { return it.readBytes().toString(Charsets.UTF_8) }
            }
        }
        runCatching {
            val f = publicBackupFile()
            if (f.canRead()) return f.readText(Charsets.UTF_8)
        }
        runCatching {
            val f = privateBackupFile()
            if (f.exists()) return f.readText(Charsets.UTF_8)
        }
        return null
    }

    // ----- onbellek -----
    private fun cacheFile(sourceId: String) = File(ctx.cacheDir, "lib_$sourceId.bin")

    fun saveLibrary(sourceId: String, lib: Library) {
        runCatching {
            ObjectOutputStream(cacheFile(sourceId).outputStream().buffered()).use {
                it.writeObject(Library(ArrayList(lib.live), ArrayList(lib.movies), ArrayList(lib.series), lib.updated))
            }
        }
    }

    fun loadLibrary(sourceId: String): Library? = runCatching {
        val f = cacheFile(sourceId)
        if (!f.exists()) return null
        ObjectInputStream(f.inputStream().buffered()).use { it.readObject() as Library }
    }.getOrNull()

    fun clearCache(sourceId: String) { cacheFile(sourceId).delete() }

    // ---------- Coklu ekran karo secimi kaliciligi ----------
    /** Coklu ekrandaki her karo icin secilen kaynak + kanal kalici olarak saklanir. */
    fun saveMultiTiles(tiles: List<MultiChannel?>) {
        val arr = JSONArray()
        tiles.forEach { ch ->
            val o = JSONObject()
            o.put("sourceId", ch?.sourceId ?: "")
            o.put("itemId", ch?.item?.id ?: "")
            o.put("itemName", ch?.item?.name ?: "")
            o.put("itemLogo", ch?.item?.logo ?: "")
            o.put("itemGroup", ch?.item?.group ?: "")
            o.put("itemUrl", ch?.item?.url ?: "")
            o.put("itemKind", ch?.item?.kind ?: "")
            arr.put(o)
        }
        prefs.edit().putString("multiTiles", arr.toString()).apply()
    }

    fun loadMultiTiles(): List<MultiChannel?> {
        val text = prefs.getString("multiTiles", "") ?: ""
        if (text.isEmpty()) return emptyList()
        val arr = runCatching { JSONArray(text) }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.getJSONObject(i)
            val item = Item(
                o.getString("itemId"),
                o.optString("itemName"),
                o.optString("itemLogo"),
                o.optString("itemGroup"),
                o.optString("itemUrl"),
                o.optString("itemKind")
            )
            MultiChannel(o.getString("sourceId"), item)
        }
    }

    fun saveMultiLayout(layout: Int) {
        prefs.edit().putInt("multiLayout", layout).apply()
    }

    fun loadMultiLayout(): Int {
        return prefs.getInt("multiLayout", 2)
    }
}