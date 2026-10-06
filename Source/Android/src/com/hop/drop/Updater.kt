package com.hop.drop

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Updates for the phone app, from HopDrop's GitHub Releases. Once a day (or when asked) it reads the latest release;
 * when that is newer and has HopDrop.apk, the app offers "Update". The APK is downloaded into the app's cache, checked
 * (same app, same signing key, higher version), then handed to Android's installer, which still asks the user.
 * Android itself also refuses an update signed with a different key, so a tampered download can't be installed.
 */
object Updater {
    private const val LATEST = "https://api.github.com/repos/Abdallah-Tarek-prog/HopDrop/releases/latest"
    private const val APK = "HopDrop.apk"
    private const val DAY = 24L * 60 * 60 * 1000
    private const val PREFS = "updates"

    data class Release(val version: String, val apkUrl: String, val size: Long)

    /** Debug builds ("HopDrop Dev") are a different app; they never update from the releases. */
    fun supported(context: Context): Boolean = !context.packageName.endsWith(".dev")

    fun enabled(context: Context): Boolean = prefs(context).getBoolean("enabled", true)
    fun setEnabled(context: Context, on: Boolean) = prefs(context).edit().putBoolean("enabled", on).apply()

    /** True when the daily check is due (turned on, and the last one was more than a day ago). */
    fun due(context: Context): Boolean =
        supported(context) && enabled(context) && System.currentTimeMillis() - prefs(context).getLong("checked", 0) > DAY

    /** The newer release, or null when this is the latest. Runs on a background thread; throws when GitHub can't be reached. */
    fun check(context: Context): Release? {
        val connection = open(LATEST)
        try {
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            if (connection.responseCode == 404) return remember(context, null) // No release published yet.
            if (connection.responseCode != 200) throw IOException("GitHub answered ${connection.responseCode}")
            val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            val version = json.optString("tag_name").removePrefix("v")
            val assets = json.optJSONArray("assets")
            var release: Release? = null
            for (i in 0 until (assets?.length() ?: 0)) {
                val asset = assets!!.getJSONObject(i)
                if (asset.optString("name") == APK) release = Release(version, asset.optString("browser_download_url"), asset.optLong("size"))
            }
            return remember(context, release?.takeIf { newer(it.version, installedVersion(context)) })
        } finally {
            connection.disconnect()
        }
    }

    /** The last check's answer, so the offer survives restarts without asking GitHub again. */
    fun known(context: Context): Release? {
        val p = prefs(context)
        val version = p.getString("version", null) ?: return null
        val release = Release(version, p.getString("url", "")!!, p.getLong("size", 0))
        return release.takeIf { newer(it.version, installedVersion(context)) }
    }

    /** Downloads the APK into the cache, reporting 0..1 (or -1 when the size is unknown), and checks it. */
    fun download(context: Context, release: Release, progress: (Float) -> Unit): File {
        val folder = File(context.cacheDir, "updates").apply { deleteRecursively(); mkdirs() }
        val file = File(folder, "HopDrop-${release.version}.apk")
        val connection = open(release.apkUrl)
        try {
            if (connection.responseCode != 200) throw IOException("Download failed (${connection.responseCode})")
            val total = connection.contentLengthLong.takeIf { it > 0 } ?: release.size
            connection.inputStream.use { input ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    var shown = -1
                    while (true) {
                        if (Thread.interrupted()) throw InterruptedException()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        done += read
                        val percent = if (total > 0) (done * 100 / total).toInt() else -1
                        if (percent != shown) { shown = percent; progress(if (total > 0) done.toFloat() / total else -1f) }
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
        verify(context, file)
        return file
    }

    /** Opens Android's installer for a downloaded update. False when HopDrop isn't allowed to install apps yet. */
    fun install(context: Context, apk: File): Boolean {
        if (Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) return false
        val uri = FileProvider.getUriForFile(context, context.packageName + ".updates", apk)
        context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }

    /** "1.0.10" is newer than "1.0.9": compares number by number. */
    fun newer(candidate: String, current: String): Boolean {
        val a = candidate.split('.', '-').map { it.toIntOrNull() ?: 0 }
        val b = current.split('.', '-').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    private fun verify(context: Context, apk: File) {
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES
        val downloaded = pm.getPackageArchiveInfo(apk.path, flags) ?: throw IOException("The download is not a valid app.")
        val installed = pm.getPackageInfo(context.packageName, flags)
        if (downloaded.packageName != context.packageName) throw IOException("The download is a different app.")
        if (signatures(downloaded) != signatures(installed)) throw IOException("The download isn't signed by HopDrop.")
        if (versionCode(downloaded) <= versionCode(installed)) throw IOException("The download is not newer than this version.")
    }

    @Suppress("DEPRECATION")
    private fun signatures(info: PackageInfo): Set<String> =
        (if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures)
            ?.map { it.toCharsString() }?.toSet() ?: emptySet()

    @Suppress("DEPRECATION")
    private fun versionCode(info: PackageInfo): Long = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()

    private fun installedVersion(context: Context): String =
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0"

    private fun remember(context: Context, release: Release?): Release? {
        val edit = prefs(context).edit().putLong("checked", System.currentTimeMillis())
        if (release == null) edit.remove("version").remove("url").remove("size")
        else edit.putString("version", release.version).putString("url", release.apkUrl).putLong("size", release.size)
        edit.apply()
        return release
    }

    private fun open(url: String): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", "HopDrop-Android")
        return connection
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
