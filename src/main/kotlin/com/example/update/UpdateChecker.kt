package com.example.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.CacheControl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** Release metadata published in `version.json`. */
data class ReleaseInfo(
    val version: String,
    val downloadUrl: String,
    val releaseNotes: String,
    val sha256: String? = null,
    val sizeBytes: Long = 0L,
    val releasePageUrl: String? = null,
)

/** Observable state of the update workflow. */
sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class UpToDate(val currentVersion: String) : UpdateState
    data class Available(val release: ReleaseInfo) : UpdateState

    data class Downloading(val receivedBytes: Long, val totalBytes: Long) : UpdateState {
        val percent: Int
            get() = if (totalBytes > 0) ((receivedBytes * 100) / totalBytes).toInt().coerceIn(0, 100) else 0
    }

    data class Ready(val release: ReleaseInfo, val installer: File) : UpdateState
    data class Failed(val message: String) : UpdateState
}

/**
 * Fetches the remote update manifest and downloads the new installer.
 *
 * The manifest is intentionally tiny and cache-busted on every check:
 * ```json
 * {
 *   "version": "2.2.0",
 *   "download_url": "https://github.com/<owner>/<repo>/releases/download/Release/WaveFocus-2.2.0-Setup.exe",
 *   "release_notes": "New theme color slider ...",
 *   "sha256": "optional lowercase hex digest",
 *   "size_bytes": 105488896
 * }
 * ```
 */
class UpdateChecker(
    private val manifestUrl: String = DEFAULT_MANIFEST_URL,
    private val client: OkHttpClient = defaultClient(),
) {

    suspend fun fetchLatest(): Result<ReleaseInfo> = withContext(Dispatchers.IO) {
        runCatching {
            require(manifestUrl.startsWith("https://", ignoreCase = true)) {
                "Update manifest must be served over HTTPS"
            }
            val request = Request.Builder()
                .url(manifestUrl)
                .header("Accept", "application/json")
                .cacheControl(CacheControl.Builder().noCache().build())
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("Update server returned HTTP ${response.code}")
                val body = response.body?.string().orEmpty()
                if (body.isBlank()) error("Update server returned an empty manifest")

                val json = JSONObject(body)
                val version = json.optString("version").trim()
                if (SemVer.parse(version) == null) error("Update manifest has an invalid version")

                ReleaseInfo(
                    version = version,
                    downloadUrl = json.optString("download_url").trim(),
                    releaseNotes = json.optString("release_notes").trim(),
                    sha256 = json.optString("sha256").trim().lowercase().takeIf { it.isNotEmpty() },
                    sizeBytes = json.optLong("size_bytes", 0L).coerceAtLeast(0L),
                    releasePageUrl = json.optString("release_page").trim().takeIf { it.isNotEmpty() },
                )
            }
        }
    }

    suspend fun download(
        release: ReleaseInfo,
        onProgress: (received: Long, total: Long) -> Unit,
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            require(release.downloadUrl.startsWith("https://", ignoreCase = true)) {
                "Download link must use HTTPS"
            }
            val target = UpdateInstaller.installerFile(release.version)
            if (target.exists() && target.length() > 0L) {
                val complete = release.sizeBytes <= 0L || target.length() == release.sizeBytes
                val checksumOk = release.sha256 == null || sha256(target) == release.sha256
                if (complete && checksumOk) {
                    onProgress(target.length(), target.length())
                    return@runCatching target
                }
                target.delete()
            }

            val request = Request.Builder()
                .url(release.downloadUrl)
                .header("Accept", "application/octet-stream")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("Download failed with HTTP ${response.code}")
                val body = response.body ?: error("Download returned an empty response")
                val total = body.contentLength().takeIf { it > 0L } ?: release.sizeBytes

                var received = 0L
                var lastReport = 0L
                body.byteStream().use { input ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            received += read
                            val now = System.currentTimeMillis()
                            if (now - lastReport >= 120L) {
                                lastReport = now
                                onProgress(received, total)
                            }
                        }
                    }
                }
                onProgress(received, total)

                if (release.sizeBytes > 0L && target.length() != release.sizeBytes) {
                    target.delete()
                    error("Download is incomplete (${target.length()} of ${release.sizeBytes} bytes)")
                }
                if (release.sha256 != null && sha256(target) != release.sha256) {
                    target.delete()
                    error("Downloaded file failed the checksum check")
                }
            }
            target
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        /**
         * Hosted on the same Firebase project as the website. Update this in
         * one place if the manifest moves.
         */
        const val DEFAULT_MANIFEST_URL =
            "https://gen-lang-client-0965379779.web.app/updates/version.json"

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.MINUTES)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
    }
}

/** Downloads land in the OS temp folder and are launched from there. */
object UpdateInstaller {

    fun installerFile(version: String): File {
        val safeVersion = version.replace(Regex("[^0-9A-Za-z._-]"), "_")
        val folder = File(System.getProperty("java.io.tmpdir"), "WaveFocus").apply { mkdirs() }
        return File(folder, "WaveFocus-$safeVersion-Setup.exe")
    }

    fun supportsAutoInstall(): Boolean =
        System.getProperty("os.name").orEmpty().lowercase().contains("win")

    /** Starts the downloaded installer and returns immediately. */
    fun launch(installer: File): Result<Unit> = runCatching {
        check(supportsAutoInstall()) { "Automatic install is only supported on Windows" }
        check(installer.isFile && installer.length() > 0L) { "The downloaded installer is missing" }
        ProcessBuilder(installer.absolutePath)
            .directory(installer.parentFile)
            .start()
    }

    fun openDownloadPage(url: String): Result<Unit> = runCatching {
        require(url.startsWith("https://", ignoreCase = true)) { "Unsafe link" }
        java.awt.Desktop.getDesktop().browse(java.net.URI(url))
    }
}
