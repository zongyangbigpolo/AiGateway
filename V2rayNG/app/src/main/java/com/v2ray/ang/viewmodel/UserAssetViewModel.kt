package com.v2ray.ang.viewmodel

import androidx.lifecycle.ViewModel
import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.UrlContentRequest
import com.v2ray.ang.dto.entities.AssetUrlCache
import com.v2ray.ang.dto.entities.AssetUrlItem
import com.v2ray.ang.extension.concatUrl
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsChangeManager
import com.v2ray.ang.util.GatewayGeoFiles
import com.v2ray.ang.util.HttpUtil
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import java.io.File
import java.io.IOException
import java.net.URI

class UserAssetViewModel : ViewModel() {
    private val assets = mutableListOf<AssetUrlCache>()

    companion object {
        fun builtInId(name: String) = "builtin:$name"

        fun isHttpsUrl(url: String): Boolean = try {
            val uri = URI(url)
            uri.scheme.equals("https", ignoreCase = true) &&
                !uri.host.isNullOrBlank() && uri.rawUserInfo == null &&
                uri.rawFragment == null && (uri.port == -1 || uri.port in 1..65535)
        } catch (_: IllegalArgumentException) {
            false
        } catch (_: java.net.URISyntaxException) {
            false
        }

        internal fun buildAssetList(
            decodedAssets: List<AssetUrlCache>?,
            geoFilesSource: String
        ): List<AssetUrlCache> {
            val savedAssets = decodedAssets.orEmpty()
            val builtInItems = GatewayGeoFiles.names
                .filter { name -> savedAssets.none { it.assetUrl.remarks == name } }
                .map { name ->
                    AssetUrlCache(
                        builtInId(name),
                        AssetUrlItem(
                            name,
                            if (name == AppConfig.GEOIP_ONLY_CN_PRIVATE_DAT) {
                                AppConfig.GEOIP_ONLY_CN_PRIVATE_URL
                            } else {
                                String.format(AppConfig.GITHUB_DOWNLOAD_URL, geoFilesSource).concatUrl(name)
                            }
                        )
                    )
                }
            return builtInItems + savedAssets
        }
    }

    val itemCount: Int
        get() = assets.size

    fun getAssets(): List<AssetUrlCache> = assets.toList()

    fun getAsset(position: Int): AssetUrlCache? = assets.getOrNull(position)

    fun reload(geoFilesSource: String) {
        val decoded = MmkvManager.decodeAssetUrls()
        assets.clear()
        assets.addAll(buildAssetList(decoded, geoFilesSource))
    }

    suspend fun downloadGeoFiles(
        extDir: File,
        httpPort: Int,
        proxyUsername: String? = null,
        proxyPassword: String? = null
    ): GeoDownloadResult {
        val snapshot = getAssets()
        var successCount = 0
        val failures = mutableListOf<String>()

        snapshot.forEach { cache ->
            currentCoroutineContext().ensureActive()
            val item = cache.assetUrl
            if (item.url == "file") return@forEach
            val portsToTry = if (httpPort == 0) listOf(0) else listOf(httpPort, 0)
            if (portsToTry.any { tryDownload(item, extDir, it, proxyUsername, proxyPassword) }) {
                successCount++
            } else {
                failures.add(item.remarks)
            }
        }

        return GeoDownloadResult(successCount, failures.size, failures)
    }

    private suspend fun tryDownload(
        item: AssetUrlItem,
        extDir: File,
        httpPort: Int,
        proxyUsername: String? = null,
        proxyPassword: String? = null
    ): Boolean {
        var targetTemp: File? = null
        try {
            GatewayGeoFiles.validateName(item.remarks)
            require(isHttpsUrl(item.url)) { "An HTTPS download URL is required" }
            if (!extDir.isDirectory && !extDir.mkdirs()) throw IOException("Cannot create asset directory")
            val candidate = File.createTempFile(".geodata-", ".download", extDir)
            targetTemp = candidate
            val downloaded = runInterruptible {
                HttpUtil.downloadToFile(
                    UrlContentRequest(
                        url = item.url,
                        timeout = 15000,
                        httpPort = httpPort,
                        proxyUsername = proxyUsername,
                        proxyPassword = proxyPassword
                    ),
                    candidate,
                    maxBytes = GatewayGeoFiles.MAX_BYTES
                )
            }
            if (downloaded) {
                currentCoroutineContext().ensureActive()
                GatewayGeoFiles.commitDownload(candidate, File(extDir, item.remarks))
                SettingsChangeManager.makeRestartService()
                return true
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            LogUtil.e(AppConfig.TAG, "Failed to download geo file: ${item.remarks}", e)
        } catch (e: IllegalArgumentException) {
            LogUtil.e(AppConfig.TAG, "Failed to download geo file: ${item.remarks}", e)
        } finally {
            targetTemp?.delete()
        }
        return false
    }

    data class GeoDownloadResult(
        val successCount: Int,
        val failureCount: Int,
        val failedAssets: List<String>
    )
}
