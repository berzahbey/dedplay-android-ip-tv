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
const val USER_AGENT = "IPTVSmartersPro"

/** Liste otomatik yenileme araligi (7 gun). Elle: Ayarlar > Listeyi yenile. */
const val AUTO_REFRESH_MS = 7L * 24 * 3600 * 1000

val RESIZE_LABELS = listOf("Sığdır", "Doldur (kırp)", "Uzat")
val BUFFER_LABELS = listOf("Düşük (hızlı açılış)", "Normal", "Yüksek (az takılma)")
val START_LABELS = listOf("Favorilerdeki ilk kanal", "Son izlenen kanal")
val SLEEP_OPTIONS = listOf(0, 30, 60, 90, 120)
