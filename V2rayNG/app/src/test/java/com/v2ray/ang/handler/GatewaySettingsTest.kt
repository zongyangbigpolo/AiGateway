package com.v2ray.ang.handler

import android.content.Context
import android.content.res.AssetManager
import com.tencent.mmkv.MMKV
import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.entities.RulesetItem
import com.v2ray.ang.util.GatewayRuleFile
import com.v2ray.ang.viewmodel.RoutingSettingsViewModel
import org.junit.Assert.*
import org.junit.Before
import org.junit.AfterClass
import org.junit.Test
import org.mockito.Mockito.*
import java.io.File

class GatewaySettingsTest {
    companion object {
        private val storage = mock(MMKV::class.java)
        private val mmkvFactory = mockStatic(MMKV::class.java).also { factory ->
            factory.`when`<MMKV> { MMKV.mmkvWithID("SETTING", MMKV.MULTI_PROCESS_MODE) }.thenReturn(storage)
        }

        @AfterClass
        @JvmStatic
        fun closeFactory() {
            mmkvFactory.close()
        }
    }

    private val values = mutableMapOf<String, Any>()
    private val context = mock(Context::class.java)
    private val assets = mock(AssetManager::class.java)

    @Before
    fun setup() {
        reset(storage)
        values["server_list_to_subscriptions_migrated"] = true
        values["hysteria2_pin_sha256_migrated"] = true
        `when`(storage.containsKey(anyString())).thenAnswer { values.containsKey(it.getArgument<String>(0)) }
        `when`(storage.decodeString(anyString())).thenAnswer { values[it.getArgument<String>(0)] as? String }
        `when`(storage.decodeBool(anyString(), anyBoolean())).thenAnswer {
            values[it.getArgument<String>(0)] as? Boolean ?: it.getArgument<Boolean>(1)
        }
        `when`(storage.encode(anyString(), anyString())).thenAnswer {
            values[it.getArgument<String>(0)] = it.getArgument<String>(1)
            true
        }
        `when`(storage.encode(anyString(), anyBoolean())).thenAnswer {
            values[it.getArgument<String>(0)] = it.getArgument<Boolean>(1)
            true
        }
        `when`(context.assets).thenReturn(assets)
        `when`(assets.open(GatewayRuleFile.DEFAULT_ASSET)).thenAnswer {
            File("src/main/assets/${GatewayRuleFile.DEFAULT_ASSET}").inputStream()
        }
        SettingsChangeManager.consumeRestartService()
    }

    @Test
    fun firstLaunchImportsRulesAndEnablesSplitDns() {
        SettingsManager.initApp(context)
        assertEquals(9, MmkvManager.decodeRoutingRulesets()?.size)
        assertEquals("Loyalsoldier/v2ray-rules-dat", values[AppConfig.PREF_GEO_FILES_SOURCES])
        assertEquals("direct", SettingsManager.getGatewayDefaultOutbound())
        assertEquals("IPIfNonMatch", values[AppConfig.PREF_ROUTING_DOMAIN_STRATEGY])
        assertEquals(true, values[AppConfig.PREF_LOCAL_DNS_ENABLED])
        assertEquals(true, values[AppConfig.PREF_APPEND_HTTP_PROXY])
        assertEquals("2", values[AppConfig.PREF_VPN_BYPASS_LAN])
    }

    @Test
    fun restartPreservesIntentionallyEmptyRulesAndPreferences() {
        values[AppConfig.PREF_ROUTING_RULESET] = ""
        values[AppConfig.PREF_GATEWAY_DEFAULT_OUTBOUND] = "proxy"
        values[AppConfig.PREF_LOCAL_DNS_ENABLED] = false
        values[AppConfig.PREF_APPEND_HTTP_PROXY] = false
        SettingsManager.initApp(context)
        assertEquals("", values[AppConfig.PREF_ROUTING_RULESET])
        assertEquals("proxy", SettingsManager.getGatewayDefaultOutbound())
        assertEquals(false, values[AppConfig.PREF_LOCAL_DNS_ENABLED])
        assertEquals(false, values[AppConfig.PREF_APPEND_HTTP_PROXY])
        verify(assets, never()).open(anyString())
    }

    @Test
    fun disabledLocalListenerDoesNotEnableBrowserProxy() {
        values[AppConfig.PREF_ENABLE_LOCAL_PROXY] = false
        SettingsManager.initApp(context)
        assertEquals(false, values[AppConfig.PREF_APPEND_HTTP_PROXY])
    }

    @Test
    fun restartingAfterCustomImportDoesNotRestoreBuiltInRules() {
        SettingsManager.initApp(context)
        val custom = listOf(RulesetItem(domain = listOf("full:example.com"), outboundTag = "direct"))
        assertTrue(SettingsManager.importGatewayRules(custom))
        SettingsManager.initApp(context)
        assertEquals(custom, MmkvManager.decodeRoutingRulesets())
        verify(assets, times(1)).open(GatewayRuleFile.DEFAULT_ASSET)
    }

    @Test
    fun importingKeepsLockedRulesFirstWithoutDuplicatingThem() {
        val locked = RulesetItem(domain = listOf("full:internal.example.com"), outboundTag = "direct", locked = true)
        MmkvManager.encodeRoutingRulesets(mutableListOf(locked, RulesetItem(outboundTag = "proxy")))
        val imported = RulesetItem(domain = listOf("domain:openai.com"), outboundTag = "proxy")
        assertTrue(SettingsManager.importGatewayRules(listOf(locked, imported)))
        assertEquals(listOf(locked, imported), MmkvManager.decodeRoutingRulesets())
        assertTrue(SettingsChangeManager.consumeRestartService())
        assertTrue(SettingsManager.importGatewayRules(emptyList()))
        assertEquals(listOf(locked), MmkvManager.decodeRoutingRulesets())
    }

    @Test
    fun storageFailureDoesNotReportSuccessOrRequestReconnect() {
        `when`(storage.encode(anyString(), anyString())).thenReturn(false)
        assertFalse(SettingsManager.importGatewayRules(emptyList()))
        assertFalse(SettingsChangeManager.consumeRestartService())
    }

    @Test
    fun draggingMultiplePositionsKeepsViewAndPersistedPriorityInSync() {
        val rules = (1..3).map {
            RulesetItem(remarks = "Rule $it", domain = listOf("full:$it.example.com"), outboundTag = "proxy")
        }
        SettingsManager.importGatewayRules(rules)
        val model = RoutingSettingsViewModel()
        model.reload()
        model.swap(0, 1)
        model.swap(1, 2)
        assertEquals(listOf(rules[1], rules[2], rules[0]), model.getAll())
        assertEquals(model.getAll(), MmkvManager.decodeRoutingRulesets())
    }
}
