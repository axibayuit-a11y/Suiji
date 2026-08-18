package com.suiji.app.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.PackageInfo
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import com.suiji.app.BuildConfig
import com.suiji.app.model.AppUpdateInfo
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

class AppUpdateManager(context: Context) {
    private val appContext = context.applicationContext
    private val updatesDirectory = File(appContext.cacheDir, "app_updates").apply { mkdirs() }

    fun checkForUpdate(): Result<AppUpdateInfo?> = runCatching {
        val release = loadLatestRelease()
            val latestVersion = release.getString("tag_name").trim().removePrefix("v")
            if (!ReleaseUpdatePolicy.isNewer(latestVersion, BuildConfig.VERSION_NAME)) {
                return@runCatching null
            }

            val assets = release.getJSONArray("assets")
            val candidates = buildList {
                repeat(assets.length()) { index ->
                    val asset = assets.getJSONObject(index)
                    add(
                        ReleaseAsset(
                            name = asset.getString("name"),
                            downloadUrl = asset.getString("browser_download_url"),
                            size = asset.optLong("size", 0L),
                            digest = asset.optString("digest").takeIf {
                                it.isNotBlank() && !it.equals("null", ignoreCase = true)
                            }
                        )
                    )
                }
            }
            val selected = ReleaseUpdatePolicy.selectAsset(Build.SUPPORTED_ABIS.toList(), candidates)
                ?: error("This release does not contain a compatible APK")
            require(UpdateSourcePolicy.isTrustedReleaseAsset(selected.downloadUrl)) {
                "The release contains an untrusted APK address"
            }
            AppUpdateInfo(
                versionName = latestVersion,
                releaseNotes = release.optString("body").trim(),
                releaseUrl = release.getString("html_url"),
                assetName = selected.name,
                downloadUrl = selected.downloadUrl,
                assetBytes = selected.size,
                sha256Digest = selected.digest
            )
    }

    fun downloadUpdate(
        info: AppUpdateInfo,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit
    ): Result<File> = runCatching {
        val safeName = info.assetName.substringAfterLast('/').takeIf { it.endsWith(".apk") }
            ?: error("Release asset is not an APK")
        val partialFile = File(updatesDirectory, "$safeName.part")
        val apkFile = File(updatesDirectory, safeName)
        apkFile.delete()
        require(UpdateSourcePolicy.isTrustedReleaseAsset(info.downloadUrl)) {
            "The update APK address is not trusted"
        }
        if (info.assetBytes > 0L && partialFile.length() > info.assetBytes) {
            partialFile.delete()
        }

        val failures = mutableListOf<String>()
        if (isComplete(partialFile, info.assetBytes)) {
            try {
                return@runCatching activateDownloadedApk(partialFile, apkFile, info)
            } catch (error: Throwable) {
                failures += "cached file: ${error.message.orEmpty()}"
                partialFile.delete()
            }
        }

        for (sourceUrl in UpdateSourcePolicy.releaseAssetUrls(info.downloadUrl)) {
            try {
                downloadFromSource(sourceUrl, partialFile, info.assetBytes, onProgress)
                if (!isComplete(partialFile, info.assetBytes)) {
                    error("The APK download is incomplete")
                }
                return@runCatching activateDownloadedApk(partialFile, apkFile, info)
            } catch (error: Throwable) {
                failures += "${URL(sourceUrl).host}: ${error.message.orEmpty()}"
                if (info.assetBytes > 0L && partialFile.length() >= info.assetBytes) {
                    partialFile.delete()
                }
            }
        }
        error("All update download sources failed: ${failures.joinToString("; ")}")
    }

    private fun loadLatestRelease(): JSONObject {
        val failures = mutableListOf<String>()
        for (url in UpdateSourcePolicy.latestReleaseUrls()) {
            val connection = openConnection(url, "application/vnd.github+json")
            try {
                require(connection.responseCode in 200..299) {
                    "HTTP ${connection.responseCode}"
                }
                return JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            } catch (error: Throwable) {
                failures += "${URL(url).host}: ${error.message.orEmpty()}"
            } finally {
                connection.disconnect()
            }
        }
        error("All update check sources failed: ${failures.joinToString("; ")}")
    }

    private fun downloadFromSource(
        sourceUrl: String,
        partialFile: File,
        expectedBytes: Long,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit
    ) {
        var existingBytes = partialFile.length().coerceAtLeast(0L)
        val connection = openConnection(sourceUrl, APK_MIME_TYPE).apply {
            if (existingBytes > 0L) setRequestProperty("Range", "bytes=$existingBytes-")
        }
        try {
            val responseCode = connection.responseCode
            val append = existingBytes > 0L && responseCode == HttpURLConnection.HTTP_PARTIAL
            require(responseCode == HttpURLConnection.HTTP_OK || responseCode == HttpURLConnection.HTTP_PARTIAL) {
                "HTTP $responseCode"
            }
            if (!append) {
                existingBytes = 0L
                partialFile.delete()
            }
            val totalBytes = expectedBytes.takeIf { it > 0L }
                ?: (existingBytes + connection.contentLengthLong).coerceAtLeast(0L)
            var downloadedBytes = existingBytes
            onProgress(downloadedBytes, totalBytes)
            BufferedInputStream(connection.inputStream).use { input ->
                BufferedOutputStream(FileOutputStream(partialFile, append)).use { output ->
                    val buffer = ByteArray(128 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        downloadedBytes += count
                        onProgress(downloadedBytes, totalBytes)
                    }
                }
            }
            require(downloadedBytes > 0L) { "The downloaded APK is empty" }
        } finally {
            connection.disconnect()
        }
    }

    private fun isComplete(file: File, expectedBytes: Long): Boolean =
        file.isFile && file.length() > 0L && (expectedBytes <= 0L || file.length() == expectedBytes)

    private fun activateDownloadedApk(
        partialFile: File,
        apkFile: File,
        info: AppUpdateInfo
    ): File {
        verifyDigest(partialFile, info.sha256Digest)
        verifyApk(partialFile)
        require(partialFile.renameTo(apkFile)) { "Could not activate the downloaded APK" }
        return apkFile
    }

    fun canRequestPackageInstalls(): Boolean = appContext.packageManager.canRequestPackageInstalls()

    fun unknownSourcesIntent(): Intent = Intent(
        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
        Uri.parse("package:${appContext.packageName}")
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun installIntent(apkFile: File): Intent {
        require(apkFile.isFile && apkFile.parentFile == updatesDirectory) { "Update APK is unavailable" }
        val uri = FileProvider.getUriForFile(
            appContext,
            "${appContext.packageName}.files",
            apkFile
        )
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, APK_MIME_TYPE)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    private fun verifyApk(file: File) {
        @Suppress("DEPRECATION")
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }
        val archive = appContext.packageManager.getPackageArchiveInfo(file.absolutePath, flags)
            ?: error("Android could not read the downloaded APK")
        require(archive.packageName == appContext.packageName) {
            "The APK belongs to a different application"
        }
        val installed = appContext.packageManager.getPackageInfo(appContext.packageName, flags)
        require(
            PackageInfoCompat.getLongVersionCode(archive) >
                PackageInfoCompat.getLongVersionCode(installed)
        ) {
            "The downloaded APK is not newer than the installed version"
        }

        val installedSigners = signerDigests(installed)
        val archiveSigners = signerDigests(archive)
        require(installedSigners.isNotEmpty() && installedSigners == archiveSigners) {
            "The APK signing certificate does not match this installation"
        }
    }

    @Suppress("DEPRECATION")
    private fun signerDigests(packageInfo: PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.signingInfo?.apkContentsSigners
        } else {
            packageInfo.signatures
        }
        return signatures?.map { sha256(it.toByteArray()) }?.toSet().orEmpty()
    }

    private fun verifyDigest(file: File, digest: String?) {
        val expected = digest
            ?.takeIf { it.startsWith("sha256:", ignoreCase = true) }
            ?.substringAfter(':')
            ?.lowercase()
            ?: return
        val messageDigest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                messageDigest.update(buffer, 0, count)
            }
        }
        val actual = messageDigest.digest().joinToString("") { "%02x".format(it) }
        require(actual == expected) { "The APK checksum does not match the GitHub release" }
    }

    private fun openConnection(url: String, accept: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("Accept", accept)
            setRequestProperty("User-Agent", "Suiji-Android/${BuildConfig.VERSION_NAME}")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        }

    private fun sha256(value: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(value)
        .joinToString("") { "%02x".format(it) }

    private companion object {
        const val APK_MIME_TYPE = "application/vnd.android.package-archive"
    }
}

data class ReleaseAsset(
    val name: String,
    val downloadUrl: String,
    val size: Long,
    val digest: String? = null
)

object ReleaseUpdatePolicy {
    fun isNewer(latest: String, current: String): Boolean {
        val latestParts = numericParts(latest) ?: return false
        val currentParts = numericParts(current) ?: return false
        val count = maxOf(latestParts.size, currentParts.size)
        repeat(count) { index ->
            val latestValue = latestParts.getOrElse(index) { 0 }
            val currentValue = currentParts.getOrElse(index) { 0 }
            if (latestValue != currentValue) return latestValue > currentValue
        }
        return false
    }

    fun selectAsset(supportedAbis: List<String>, assets: List<ReleaseAsset>): ReleaseAsset? {
        val apkAssets = assets.filter { it.name.endsWith(".apk", ignoreCase = true) }
        if (supportedAbis.any { it.equals("arm64-v8a", ignoreCase = true) }) {
            apkAssets.firstOrNull { it.name.endsWith("-arm64.apk", ignoreCase = true) }
                ?.let { return it }
        }
        return apkAssets.firstOrNull { it.name.endsWith("-universal.apk", ignoreCase = true) }
    }

    private fun numericParts(value: String): List<Int>? {
        val normalized = value.trim().removePrefix("v").substringBefore('-')
        if (!normalized.matches(Regex("\\d+(\\.\\d+)*"))) return null
        return normalized.split('.').map(String::toInt)
    }
}

object UpdateSourcePolicy {
    private const val LATEST_RELEASE_API =
        "https://api.github.com/repos/axibayuit-a11y/Suiji/releases/latest"
    private const val RELEASE_PATH_PREFIX =
        "/axibayuit-a11y/Suiji/releases/download/"
    private val accelerationPrefixes = listOf(
        "https://gh-proxy.com/",
        "https://ghproxy.net/"
    )

    fun latestReleaseUrls(): List<String> = listOf(
        accelerationPrefixes.first() + LATEST_RELEASE_API,
        LATEST_RELEASE_API
    )

    fun releaseAssetUrls(githubUrl: String): List<String> {
        require(isTrustedReleaseAsset(githubUrl)) { "Untrusted GitHub release URL" }
        return accelerationPrefixes.map { it + githubUrl } + githubUrl
    }

    fun isTrustedReleaseAsset(url: String): Boolean = runCatching {
        val parsed = URL(url)
        parsed.protocol.equals("https", ignoreCase = true) &&
            parsed.host.equals("github.com", ignoreCase = true) &&
            parsed.path.startsWith(RELEASE_PATH_PREFIX, ignoreCase = true) &&
            parsed.path.endsWith(".apk", ignoreCase = true)
    }.getOrDefault(false)
}
