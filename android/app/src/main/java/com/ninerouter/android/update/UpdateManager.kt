package com.ninerouter.android.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * In-app updates from the GitHub release channel.
 *
 * The app is sideloaded, so Play Core's in-app update API does not apply. This
 * mirrors it instead: check the public GitHub releases API, download the APK
 * asset, then hand it to the system package installer through a FileProvider.
 */
object UpdateManager {
    private const val REPO = "itswill00/9router"
    const val RELEASES_PAGE = "https://github.com/$REPO/releases"
    private const val API_URL = "https://api.github.com/repos/$REPO/releases?per_page=30"
    private const val PREFS = "ninerouter_updater"
    private const val KEY_LAST_CHECK = "last_check"
    private const val KEY_ANNOUNCED = "announced_version"
    private const val CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L

    sealed interface State {
        data object Idle : State
        data object Checking : State
        data object UpToDate : State
        data class Available(val release: Release, val currentVersion: String) : State
        data class Downloading(val release: Release, val progress: Float) : State
        data class Ready(val release: Release, val file: File) : State
        data class Failed(val message: String) : State
    }

    data class Release(
        val version: String,
        val tag: String,
        val title: String,
        val notes: String,
        val apkUrl: String,
        val size: Long,
        val pageUrl: String,
        val sha256: String = "",
    )

    val state = MutableStateFlow<State>(State.Idle)

    private val json = Json { ignoreUnknownKeys = true }
    private val apiClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    private val downloadClient = apiClient.newBuilder()
        .readTimeout(180, TimeUnit.SECONDS)
        .build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val busy = AtomicBoolean(false)

    fun currentVersion(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    }.getOrDefault("")

    /** Epoch millis of the last successful check, or 0 when never checked. */
    fun lastChecked(context: Context): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_LAST_CHECK, 0L)

    /** The launch prompt is shown once per version; "Later" is remembered. */
    fun shouldAnnounce(context: Context, version: String): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_ANNOUNCED, "") != version

    fun markAnnounced(context: Context, version: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_ANNOUNCED, version).apply()
    }

    /** Runs at most once per [CHECK_INTERVAL_MS]; silent failures keep the UI quiet. */
    fun autoCheck(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val last = prefs.getLong(KEY_LAST_CHECK, 0L)
        if (System.currentTimeMillis() - last < CHECK_INTERVAL_MS) return
        check(context, silent = true)
    }

    fun check(context: Context, silent: Boolean = false) {
        if (!busy.compareAndSet(false, true)) return
        state.value = State.Checking
        val current = currentVersion(context)
        scope.launch {
            try {
                val request = Request.Builder()
                    .url(API_URL)
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "9Router-Android")
                    .build()
                val body = apiClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw IOException(
                    if (response.code == 403) "GitHub rate limit reached. Try again later."
                    else "GitHub returned HTTP ${response.code}"
                )
                    response.body?.string().orEmpty()
                }
                val releases = parseReleases(body)
                val latest = releases.maxWithOrNull(
                    Comparator { a, b -> compareVersions(a.version, b.version) }
                )
                if (latest == null) {
                    state.value = if (silent) State.Idle else State.Failed("No Android release found on GitHub.")
                    return@launch
                }
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()
                state.value = if (isNewer(latest.version, current)) {
                    State.Available(latest, current)
                } else {
                    State.UpToDate
                }
            } catch (e: Exception) {
                state.value = if (silent) State.Idle else State.Failed(e.message ?: "Update check failed.")
            } finally {
                busy.set(false)
            }
        }
    }

    fun download(context: Context) {
        val available = state.value as? State.Available ?: return
        if (!busy.compareAndSet(false, true)) return
        val release = available.release
        state.value = State.Downloading(release, 0f)
        scope.launch {
            try {
                val dir = File(context.cacheDir, "updates").apply { mkdirs() }
                val target = File(dir, "9router-${release.version}.apk")
                val tmp = File(dir, "9router-${release.version}.apk.part")
                if (tmp.exists()) tmp.delete()
                val request = Request.Builder()
                    .url(release.apkUrl)
                    .header("Accept", "application/octet-stream")
                    .header("User-Agent", "9Router-Android")
                    .build()
                downloadClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("Download failed: HTTP ${response.code}")
                    val body = response.body ?: throw IOException("Empty download")
                    val total = body.contentLength().takeIf { it > 0 } ?: release.size
                    body.byteStream().use { input ->
                        tmp.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var written = 0L
                            var lastProgressAt = 0L
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                output.write(buffer, 0, read)
                                written += read
                                val now = System.currentTimeMillis()
                                // GitHub progress arrives in 64 KiB chunks; do
                                // not invalidate the whole shell 160 times.
                                if (total > 0 && (now - lastProgressAt >= 250L || written >= total)) {
                                    lastProgressAt = now
                                    state.value = State.Downloading(
                                        release,
                                        (written.toFloat() / total).coerceIn(0f, 1f),
                                    )
                                }
                            }
                        }
                    }
                }
                if (release.size > 0 && tmp.length() != release.size) {
                    tmp.delete()
                    throw IOException("The downloaded APK has the wrong size.")
                }
                if (release.sha256.isNotBlank() && !sha256Matches(tmp, release.sha256)) {
                    tmp.delete()
                    throw IOException("The downloaded APK failed its SHA-256 check.")
                }
                if (tmp.length() < 1_000_000L || !looksLikeApk(tmp)) {
                    tmp.delete()
                    throw IOException("The downloaded file is not a valid APK.")
                }
                val info = context.packageManager.getPackageArchiveInfo(tmp.absolutePath, 0)
                if (info == null || info.packageName != context.packageName) {
                    tmp.delete()
                    throw IOException("The downloaded APK is for a different app.")
                }
                if (target.exists()) target.delete()
                if (!tmp.renameTo(target)) {
                    tmp.copyTo(target, overwrite = true)
                    tmp.delete()
                }
                state.value = State.Ready(release, target)
            } catch (e: Exception) {
                state.value = State.Failed(e.message ?: "Download failed.")
            } finally {
                busy.set(false)
            }
        }
    }

    /**
     * Opens the system installer. Returns a message when the user must grant
     * "install unknown apps" first (the settings screen is opened for them).
     */
    fun install(context: Context, file: File): String? {
        if (!file.exists() || file.length() == 0L) return "The downloaded file is missing. Download it again."
        val info = context.packageManager.getPackageArchiveInfo(file.absolutePath, 0)
            ?: return "The downloaded file is not a valid APK."
        if (info.packageName != context.packageName) return "The downloaded APK is for a different app."
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            runCatching {
                context.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${context.packageName}"),
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
            return "Allow \"Install unknown apps\" for 9Router, then tap Install again."
        }
        return runCatching {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file,
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                clipData = android.content.ClipData.newRawUri("9router-update", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            null
        }.getOrElse { e -> e.message ?: "Could not open the package installer." }
    }

    private fun parseReleases(body: String): List<Release> {
        val array = runCatching { json.parseToJsonElement(body) as? JsonArray }.getOrNull() ?: return emptyList()
        return array.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val tag = text(obj["tag_name"]) ?: return@mapNotNull null
            if (!tag.startsWith("android-v")) return@mapNotNull null
            if ((obj["prerelease"] as? JsonPrimitive)?.content == "true") return@mapNotNull null
            if ((obj["draft"] as? JsonPrimitive)?.content == "true") return@mapNotNull null
            val assets = (obj["assets"] as? JsonArray).orEmpty()
            val apkObjects = assets.mapNotNull { it as? JsonObject }
            val chosenName = selectApkAsset(apkObjects.mapNotNull { text(it["name"]) })
            val apk = apkObjects.firstOrNull { text(it["name"]) == chosenName }
                ?: return@mapNotNull null
            val url = text(apk["browser_download_url"]) ?: return@mapNotNull null
            val version = versionOf(tag) ?: return@mapNotNull null
            Release(
                version = version,
                tag = tag,
                title = text(obj["name"]) ?: tag,
                notes = text(obj["body"]).orEmpty(),
                apkUrl = url,
                size = (apk["size"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0L,
                pageUrl = text(obj["html_url"]) ?: RELEASES_PAGE,
                sha256 = text(apk["digest"]).orEmpty().substringAfter("sha256:", ""),
            )
        }
    }

    private fun text(element: kotlinx.serialization.json.JsonElement?): String? =
        (element as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }

    private fun versionOf(value: String): String? {
        val match = Regex("""(\d+)\.(\d+)\.(\d+)""").find(value) ?: return null
        return match.value
    }

    private fun parts(value: String): List<Int> =
        Regex("""(\d+)\.(\d+)\.(\d+)""").find(value)
            ?.groupValues?.drop(1)?.mapNotNull { it.toIntOrNull() }
            ?: emptyList()

    private fun compareVersions(a: String, b: String): Int {
        val left = parts(a)
        val right = parts(b)
        for (i in 0 until 3) {
            val x = left.getOrElse(i) { 0 }
            val y = right.getOrElse(i) { 0 }
            if (x != y) return x.compareTo(y)
        }
        return 0
    }

    private fun isNewer(latest: String, current: String): Boolean {
        if (parts(latest).isEmpty()) return false
        return compareVersions(latest, current) > 0
    }

    private fun looksLikeApk(file: File): Boolean = runCatching {
        file.inputStream().use { stream ->
            val a = stream.read()
            val b = stream.read()
            a == 'P'.code && b == 'K'.code
        }
    }.getOrDefault(false)

    private fun sha256Matches(file: File, expected: String): Boolean = runCatching {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        digest.digest().joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
            .equals(expected.trim(), ignoreCase = true)
    }.getOrDefault(false)
}

/**
 * Release pages carry one release APK. Debug and unsigned artifacts are never
 * installed by the updater, even if an old release still contains one.
 */
internal fun selectApkAsset(names: List<String>): String? {
    val apks = names.filter {
        it.endsWith(".apk", ignoreCase = true) &&
            !it.contains("debug", ignoreCase = true) &&
            !it.contains("unsigned", ignoreCase = true)
    }
    return apks.firstOrNull { it.contains("-release", ignoreCase = true) }
}
