package com.v2ray.ang.util

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.charset.CharacterCodingException

class GatewayRuleFileTest {
    @Test
    fun importsBundledAiAndGitHubRulesInPriorityOrder() {
        val rules = File("src/main/assets/${GatewayRuleFile.DEFAULT_ASSET}").inputStream().use(GatewayRuleFile::read)
        assertEquals(9, rules.size)
        assertEquals("direct", rules.first().outboundTag)
        assertTrue(rules.any { it.outboundTag == "proxy" && "domain:openai.com" in it.domain.orEmpty() })
        assertTrue(rules.all { it.enabled && it.locked == false })
        assertEquals(listOf("geosite:cn"), rules[6].domain)
        assertEquals("direct", rules[6].outboundTag)
        assertEquals(listOf("geosite:geolocation-!cn"), rules[7].domain)
        assertEquals("proxy", rules[7].outboundTag)
        assertEquals(listOf("geoip:cn"), rules[8].ip)
    }

    @Test
    fun preservesOrderActionsAndFlags() {
        val rules = GatewayRuleFile.parse(
            """[
                {"domain":["full:api.example.com"],"outboundTag":"direct","locked":true},
                {"domain":["domain:example.com"],"outboundTag":"proxy","enabled":false},
                {"ip":["192.0.2.0/24","2001:db8::/32","::1"],"outboundTag":"block"}
            ]"""
        )
        assertEquals(listOf("direct", "proxy", "block"), rules.map { it.outboundTag })
        assertEquals(true, rules.first().locked)
        assertFalse(rules[1].enabled)
    }

    @Test
    fun acceptsEmptyRulesAndUtf8Bom() {
        assertTrue(GatewayRuleFile.parse("[]").isEmpty())
        assertTrue(GatewayRuleFile.read(ByteArrayInputStream("\uFEFF[]".toByteArray())).isEmpty())
    }

    @Test
    fun rejectsInvalidOrAmbiguousRules() {
        val invalid = listOf(
            "", "null", "{}", "[null]", "[1]", "[{}]", "[{]",
            """[{"domain":["domain:example.com"],"outboundTag":"typo"}]""",
            """[{"domain":[],"outboundTag":"proxy"}]""",
            """[{"domain":null,"outboundTag":"proxy"}]""",
            """[{"domain":[null],"outboundTag":"proxy"}]""",
            """[{"domain":["https://example.com"],"outboundTag":"proxy"}]""",
            """[{"domain":["domain:*.example.com"],"outboundTag":"proxy"}]""",
            """[{"domain":["domain:evil..com"],"outboundTag":"proxy"}]""",
            """[{"domain":["geosite:"],"outboundTag":"proxy"}]""",
            """[{"domain":["geosite:../file"],"outboundTag":"proxy"}]""",
            """[{"ip":["geoip:"],"outboundTag":"proxy"}]""",
            """[{"domain":["domain:example.com"],"outboundTag":"proxy","enabled":"false"}]""",
            """[{"domain":["domain:example.com"],"outboundTag":2}]""",
            """[{"domain":["domain:example.com"],"outboundTag":"proxy","typo":true}]""",
            """[{"domain":["domain:example.com"],"ip":["1.1.1.1"],"outboundTag":"proxy"}]""",
            """[{"ip":["999.1.1.1"],"outboundTag":"proxy"}]""",
            """[{"ip":["1.1.1.1/33"],"outboundTag":"proxy"}]""",
            """[{"ip":["2001:db8::/129"],"outboundTag":"proxy"}]""",
            """[{"ip":["2001:::1"],"outboundTag":"proxy"}]""",
            """[{"ip":["example.com"],"outboundTag":"proxy"}]""",
            """[{"ip":["1.2.3.4/-1"],"outboundTag":"proxy"}]""",
            """[{"ip":["1.2.3.4/24/2"],"outboundTag":"proxy"}]""",
            """[{"ip":["1.2.3.4"],"outboundTag":"proxy"}] trailing""",
            """[{'ip':['1.2.3.4'],'outboundTag':'proxy'}]"""
        )
        invalid.forEach { content ->
            assertThrows("Must reject: $content", IllegalArgumentException::class.java) {
                GatewayRuleFile.parse(content)
            }
        }
    }

    @Test
    fun boundsFileSizeAndRejectsInvalidUtf8() {
        val exactlyLimit = ("[]" + " ".repeat(GatewayRuleFile.MAX_BYTES - 2)).toByteArray()
        assertTrue(GatewayRuleFile.read(ByteArrayInputStream(exactlyLimit)).isEmpty())
        assertThrows(IllegalArgumentException::class.java) {
            GatewayRuleFile.read(ByteArrayInputStream(exactlyLimit + byteArrayOf(32)))
        }
        assertThrows(CharacterCodingException::class.java) {
            GatewayRuleFile.read(ByteArrayInputStream(byteArrayOf(0xC3.toByte(), 0x28)))
        }
    }

    @Test
    fun boundsRuleCount() {
        val rule = """{"ip":["1.1.1.1"],"outboundTag":"proxy"}"""
        assertThrows(IllegalArgumentException::class.java) {
            GatewayRuleFile.parse(List(10001) { rule }.joinToString(",", "[", "]"))
        }
    }
}
