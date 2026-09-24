package com.v2ray.ang.core

import com.v2ray.ang.dto.V2rayConfig
import org.junit.Assert.*
import org.junit.Test

class GatewayRoutingTest {
    private fun config(strategy: String = "IPIfNonMatch") = V2rayConfig(
        log = V2rayConfig.LogBean(),
        inbounds = arrayListOf(),
        outbounds = arrayListOf(
            V2rayConfig.OutboundBean(tag = "proxy", protocol = "vmess"),
            V2rayConfig.OutboundBean(tag = "direct", protocol = "freedom"),
            V2rayConfig.OutboundBean(tag = "block", protocol = "blackhole")
        ),
        routing = V2rayConfig.RoutingBean(
            domainStrategy = strategy,
            rules = arrayListOf(
                V2rayConfig.RoutingBean.RulesBean(domain = listOf("domain:openai.com"), outboundTag = "proxy"),
                V2rayConfig.RoutingBean.RulesBean(ip = listOf("192.0.2.0/24"), outboundTag = "proxy"),
                V2rayConfig.RoutingBean.RulesBean(inboundTag = listOf("dns-module"), outboundTag = "proxy")
            )
        )
    )

    @Test
    fun directFallbackPreservesDomainIpAndDnsRulesWithoutCatchAll() {
        val config = config()
        val rules = config.routing.rules.toList()
        GatewayRouting.applyDefault(config, "direct")
        assertEquals(listOf("direct", "proxy", "block"), config.outbounds.map { it.tag })
        assertEquals(rules, config.routing.rules)
        assertEquals("IPIfNonMatch", config.routing.domainStrategy)
    }

    @Test
    fun proxyFallbackCanRestoreProxyFirstForLatencyTests() {
        val config = config()
        GatewayRouting.applyDefault(config, "direct")
        GatewayRouting.applyDefault(config, "proxy")
        assertEquals("proxy", config.outbounds.first().tag)
        assertEquals(3, config.outbounds.size)
    }

    @Test
    fun directFallbackNeverAddsPolicyGroupCatchAll() {
        val config = config()
        GatewayRouting.applyDefault(config, "direct", "balancer-main")
        assertEquals("direct", config.outbounds.first().tag)
        assertTrue(config.routing.rules.none { it.balancerTag != null })
    }

    @Test
    fun proxyPolicyGroupCatchAllKeepsIpResolutionAndDnsRules() {
        val config = config()
        GatewayRouting.applyDefault(config, "proxy", "balancer-main")
        assertEquals("dns-module", config.routing.rules[2].inboundTag?.first())
        assertEquals(listOf("0.0.0.0/0", "::/0"), config.routing.rules.last().ip)
        assertEquals("balancer-main", config.routing.rules.last().balancerTag)
        assertNull(config.routing.rules.last().network)
    }

    @Test
    fun asIsPolicyGroupCatchesBothTcpAndUdp() {
        val config = config("AsIs")
        GatewayRouting.applyDefault(config, "proxy", "balancer-main")
        assertEquals("tcp,udp", config.routing.rules.last().network)
    }

    @Test
    fun missingOrUnknownOutboundIsAnError() {
        assertThrows(IllegalArgumentException::class.java) { GatewayRouting.applyDefault(config(), "typo") }
        val config = config()
        config.outbounds.removeAll { it.tag == "direct" }
        assertThrows(IllegalArgumentException::class.java) { GatewayRouting.applyDefault(config, "direct") }
    }
}
