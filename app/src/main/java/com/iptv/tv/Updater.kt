package com.iptv.tv

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import okhttp3.Request
import org.json.JSONObject
import java.io.File

/** GitHub'daki son surum bilgisi. */
data class UpdateInfo(val version: String, val apkUrl: String, val sizeBytes: Long)

/**
 * Uygulama ici guncelleme (GitHub Releases). Sadece kurulum ekranini acar; Android
 * kurulumu her seferinde kullanicinin onaylamasini ister (sessiz guncelleme yok).
 */
object Updater {
    private const val API = "https://api.github.com/repos/berzahbey/dedplay-android-ip-tv/releases/latest"
    private const val APK_NAME = "dedplay.apk"

    fun currentVersion(ctx: Context): String =
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "0"

    /** Son surum; bulunamazsa / ag hatasinda null. (IO thread'de cagrilmali) */
    fun fetchLatest(): UpdateInfo? {
        val req = Request.Builder().url(API)
            .header("User-Agent", "dedplay-updater")
            .header("Accept", "application/vnd.github+json")
            .build()
        Http.client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val json = JSONObject(resp.body?.string() ?: return null)
            val tag = json.optString("tag_name").ifEmpty { return null }
            val assets = json.optJSONArray("assets") ?: return null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name") == APK_NAME) {
                    return UpdateInfo(tag.removePrefix("v"), a.optString("browser_download_url"), a.optLong("size"))
                }
            }
        }
        return null
    }

    /** "1.7.10" > "1.7.2" gibi sayisal karsilastirma. */
    fun isNewer(remote: String, local: String): Boolean {
        val r = remote.split('.', '-').map { it.toIntOrNull() ?: 0 }
        val l = local.split('.', '-').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(r.size, l.size)) {
            val a = r.getOrElse(i) { 0 }
            val b = l.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    private fun updateDir(ctx: Context): File =
        File(ctx.externalCacheDir ?: ctx.cacheDir, "updates").apply { mkdirs() }

    /** APK'yi indirir; onProgress(0..100). (IO thread'de cagrilmali) */
    fun download(ctx: Context, info: UpdateInfo, onProgress: (Int) -> Unit): File {
        val out = File(updateDir(ctx), "dedplay-${info.version}.apk")
        val tmp = File(out.path + ".part")
        val req = Request.Builder().url(info.apkUrl).header("User-Agent", "dedplay-updater").build()
        Http.client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw RuntimeException("İndirilemedi (HTTP ${resp.code})")
            val body = resp.body ?: throw RuntimeException("Boş cevap")
            val total = body.contentLength().takeIf { it > 0 } ?: info.sizeBytes
            var done = 0L
            var last = -1
            body.byteStream().use { input ->
                tmp.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val p = (done * 100 / total).toInt()
                            if (p != last) { last = p; onProgress(p) }
                        }
                    }
                }
            }
        }
        if (out.exists()) out.delete()
        if (!tmp.renameTo(out)) throw RuntimeException("Dosya kaydedilemedi")
        return out
    }

    /** Android 8+: bu uygulamanin APK kurma izni var mi? */
    fun canInstall(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || ctx.packageManager.canRequestPackageInstalls()

    /** "Bu kaynaktan yuklemeye izin ver" ayar sayfasini acar (Android 8+). */
    fun openInstallPermission(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val i = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { ctx.startActivity(i) } catch (_: ActivityNotFoundException) {
            ctx.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    /** Android'in kurulum ekranini acar. */
    fun install(ctx: Context, apk: File) {
        val uri: Uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            FileProvider.getUriForFile(ctx, "${ctx.packageName}.updates", apk)
        } else Uri.fromFile(apk)
        val i = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(i)
    }

    /** Eski indirilmis guncelleme dosyalarini temizler. */
    fun cleanup(ctx: Context) {
        runCatching { updateDir(ctx).listFiles()?.forEach { it.delete() } }
    }
}
