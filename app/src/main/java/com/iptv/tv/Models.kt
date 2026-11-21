package com.iptv.tv

import java.io.Serializable

/** Kullanicinin eklediği kaynak: M3U linki veya Xtream hesabi. */
data class Source(
    val id: String,
    val name: String,
    val type: String,          // "m3u" veya "xtream"
    val url: String = "",      // m3u icin liste linki
    val host: String = "",     // xtream icin sunucu, ornek http://sunucu:8080
    val user: String = "",
    val pass: String = ""
) : Serializable

/** Canli kanal, film veya dizi (kind: live / movie / series). */
data class Item(
    val id: String,
    val name: String,
    val logo: String,
    val group: String,
    val url: String,           // bos ise (xtream dizisi) detay sayfasindan bolumler alinir
    val kind: String
) : Serializable

data class Library(
    val live: List<Item>,
    val movies: List<Item>,
    val series: List<Item>,
    val updated: Long
) : Serializable

/**
 * Coklu ekran karosundaki kanal. [sourceId] sayesinde her karo farkli bir
 * M3U / Xtream kaynagindan beslenebilir.
 */
data class MultiChannel(val sourceId: String, val item: Item)

data class Episode(val id: String, val title: String, val season: Int, val url: String)

/** Yayin akisi (EPG) kaydi. Zamanlar milisaniye. */
data class EpgEntry(val title: String, val start: Long, val end: Long, val desc: String = "")

/** Xtream hesap bilgisi. expires: ms, 0 = suresiz. */
data class AccountInfo(val maxConnections: Int, val activeConnections: Int, val expires: Long, val status: String)

/** Kayit isi. end = 0 ise elle durdurulana kadar. */
data class RecJob(
    val id: String,
    val channel: String,
    val title: String,
    val url: String,
    val start: Long,
    val end: Long
)

/** Ses / altyazi secenegi. groupIndex < 0 ise "Kapali". */
data class TrackOption(val label: String, val groupIndex: Int, val trackIndex: Int, val lang: String = "")

enum class Screen { LIVE, MOVIES, SERIES, SETTINGS, MANAGE_GROUPS, ADD_SOURCE, PLAYER, SEARCH, RECORDINGS, MULTI }

/** Canli TV uzerindeki kumanda panelleri. */
enum class Panel { NONE, MENU, GROUPS, CHANNELS, OPTIONS, EPG }

const val FAV_GROUP = "★ Favoriler"
/** Favorilerin altinda: gizli gruplar haric tum canli kanallar. */
const val ALL_GROUP = "☰ Tüm Kanallar"
const val USER_AGENT = "IPTVSmartersPro"

/** Liste otomatik yenileme araligi (7 gun). Elle: Ayarlar > Listeyi yenile. */
/** Otomatik liste yenileme secenekleri (Ayarlar). -1 = kapali, sadece elle. */
val REFRESH_LABELS = listOf("Her açılışta", "Günde bir", "3 günde bir", "Haftada bir", "Kapalı (sadece elle)")
private const val DAY_MS = 24L * 3600 * 1000
val REFRESH_MS = listOf(0L, DAY_MS, 3 * DAY_MS, 7 * DAY_MS, -1L)

val RESIZE_LABELS = listOf("Sığdır", "Doldur (kırp)", "Uzat")
val BUFFER_LABELS = listOf("Düşük (hızlı açılış)", "Normal", "Yüksek (az takılma)")
val START_LABELS = listOf("Favorilerdeki ilk kanal", "Son izlenen kanal")
val BACK_LABELS = listOf("İki kez Geri ile çık", "Çıkmaz (sadece Home)")
val SLEEP_OPTIONS = listOf(0, 30, 60, 90, 120)

/** Bu ekranlar acilinca canli kanal durdurulmaz, sessizce arkada calar (donunce aninda devam). */
val KEEP_LIVE_SCREENS = setOf(Screen.SETTINGS, Screen.MANAGE_GROUPS, Screen.SEARCH, Screen.RECORDINGS)

/** Coklu ekran karo goruntusu secenekleri. */
val MULTI_FIT_LABELS = listOf("Sığdır", "Doldur", "Uzat")
