package com.v2ray.ang.viewmodel

import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.entities.AssetUrlCache
import com.v2ray.ang.dto.entities.AssetUrlItem
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UserAssetViewModelTest {
    @Test
    fun generatedSourcesHaveStableEditableIds() {
        val first = UserAssetViewModel.buildAssetList(null, "Loyalsoldier/v2ray-rules-dat")
        val second = UserAssetViewModel.buildAssetList(emptyList(), "Loyalsoldier/v2ray-rules-dat")
        assertEquals(first.map { it.guid }, second.map { it.guid })
        assertTrue(first.all { it.assetUrl.locked != true })
        val geosite = first.single { it.assetUrl.remarks == AppConfig.GEOSITE_DAT }
        assertTrue(geosite.assetUrl.url.contains("Loyalsoldier/v2ray-rules-dat"))
        assertTrue(geosite.assetUrl.url.endsWith("/geosite.dat"))
    }

    @Test
    fun savedUrlsAndLocalImportsOverrideGeneratedSources() {
        val saved = listOf(
            AssetUrlCache("local", AssetUrlItem(AppConfig.GEOSITE_DAT, "file")),
            AssetUrlCache("custom", AssetUrlItem(AppConfig.GEOIP_ONLY_CN_PRIVATE_DAT, "https://example.com/custom.dat"))
        )
        val result = UserAssetViewModel.buildAssetList(saved, "Loyalsoldier/v2ray-rules-dat")
        assertEquals(3, result.size)
        saved.forEach { entry -> assertEquals(entry, result.single { it.guid == entry.guid }) }
    }

    @Test
    fun changingRepositoryRebuildsGeneratedUrls() {
        val result = UserAssetViewModel.buildAssetList(emptyList(), "v2fly/domain-list-community")
        assertTrue(result.single { it.assetUrl.remarks == AppConfig.GEOSITE_DAT }
            .assetUrl.url.contains("v2fly/domain-list-community"))
    }

    @Test
    fun remoteSourcesMustBeHttpsWithoutCredentialsOrFragments() {
        listOf("https://example.com/geosite.dat", "https://example.com:8443/file?download=1")
            .forEach { assertTrue(it, UserAssetViewModel.isHttpsUrl(it)) }
        listOf(
            "", "file", "file:///geosite.dat", "http://example.com/file", "https:///file",
            "https://user:pass@example.com/file", "https://example.com/file#fragment",
            "https://example.com:99999/file", "https://example.com/a b", "../geosite.dat"
        ).forEach { assertFalse(it, UserAssetViewModel.isHttpsUrl(it)) }
    }

    @Test
    fun localImportsAreSkippedRatherThanReportedAsFailures() = runBlocking {
        val viewModel = UserAssetViewModel()
        val field = UserAssetViewModel::class.java.getDeclaredField("assets")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val assets = field.get(viewModel) as MutableList<AssetUrlCache>
        assets.add(AssetUrlCache("local", AssetUrlItem(AppConfig.GEOSITE_DAT, "file")))
        val result = viewModel.downloadGeoFiles(java.io.File("."), 0)
        assertEquals(0, result.successCount)
        assertEquals(0, result.failureCount)
        assertTrue(result.failedAssets.isEmpty())
    }
}
