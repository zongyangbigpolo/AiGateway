package com.v2ray.ang.core

import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.V2rayConfig

object GatewayRouting {
    fun applyDefault(
        config: V2rayConfig,
        outboundTag: String,
        proxyBalancerTag: String? = null
    ) {
        require(outboundTag == AppConfig.TAG_DIRECT || outboundTag == AppConfig.TAG_PROXY)
        if (outboundTag == AppConfig.TAG_PROXY && proxyBalancerTag != null) {
            config.routing.rules.add(
                if (config.routing.domainStrategy == "IPIfNonMatch") {
                    V2rayConfig.RoutingBean.RulesBean(
                        ip = listOf("0.0.0.0/0", "::/0"),
                        balancerTag = proxyBalancerTag
                    )
                } else {
                    V2rayConfig.RoutingBean.RulesBean(network = "tcp,udp", balancerTag = proxyBalancerTag)
                }
            )
            return
        }
        val index = config.outbounds.indexOfFirst { it.tag == outboundTag }
        require(index >= 0) { "Missing default outbound: $outboundTag" }
        // A catch-all rule would prevent IPIfNonMatch from resolving domains and evaluating IP rules.
        config.outbounds.add(0, config.outbounds.removeAt(index))
    }
}
