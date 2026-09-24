package com.v2ray.ang.util

import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.Strictness
import com.v2ray.ang.dto.entities.RulesetItem
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.UnknownHostException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

object GatewayRuleFile {
    const val DEFAULT_ASSET = "ai_gateway_rules.json"
    const val MAX_BYTES = 1024 * 1024
    private const val MAX_RULES = 10000
    private val gson = GsonBuilder().setStrictness(Strictness.STRICT).create()
    private val fields = setOf("remarks", "domain", "ip", "outboundTag", "enabled", "locked")
    private val domainLabel = Regex("[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?")

    fun read(input: InputStream): MutableList<RulesetItem> {
        val bytes = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer, 0, minOf(buffer.size, MAX_BYTES + 1 - bytes.size()))
            if (count == -1) break
            bytes.write(buffer, 0, count)
            require(bytes.size() <= MAX_BYTES) { "Rule file exceeds 1 MiB." }
        }
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return parse(decoder.decode(ByteBuffer.wrap(bytes.toByteArray())).toString())
    }

    fun parse(content: String): MutableList<RulesetItem> {
        require(content.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Rule file exceeds 1 MiB." }
        val root = try {
            gson.fromJson(content.removePrefix("\uFEFF"), JsonElement::class.java)
        } catch (e: JsonParseException) {
            throw IllegalArgumentException("Invalid JSON rule file.", e)
        }
        require(root != null && root.isJsonArray) { "Expected a JSON array of domain/IP rules." }
        require(root.asJsonArray.size() <= MAX_RULES) { "Too many rules (maximum $MAX_RULES)." }
        return root.asJsonArray.mapIndexed { index, element ->
            val location = "Rule ${index + 1}"
            require(element.isJsonObject) { "$location must be an object." }
            val rule = element.asJsonObject
            require(rule.keySet().all { it in fields }) { "$location contains unsupported fields." }
            val outbound = string(rule, "outboundTag", location)
            require(outbound in setOf("proxy", "direct", "block")) {
                "$location outboundTag must be proxy, direct or block."
            }
            val domains = strings(rule, "domain", location)
            val ips = strings(rule, "ip", location)
            require((domains != null) xor (ips != null)) {
                "$location must contain either domain or ip, not both."
            }
            domains?.forEach { value ->
                require(validDomain(value)) { "$location has an invalid domain: $value. Use domain:, full: or geosite:." }
            }
            ips?.forEach { value ->
                require(validIp(value)) { "$location has an invalid IP address or CIDR: $value." }
            }
            RulesetItem(
                remarks = if (rule.has("remarks")) string(rule, "remarks", location) else "",
                domain = domains,
                ip = ips,
                outboundTag = outbound,
                enabled = boolean(rule, "enabled", true, location),
                locked = boolean(rule, "locked", false, location)
            )
        }.toMutableList()
    }

    private fun string(rule: JsonObject, key: String, location: String): String {
        val value = rule.get(key)
        require(value != null && value.isJsonPrimitive && value.asJsonPrimitive.isString) {
            "$location $key must be a string."
        }
        return value.asString
    }

    private fun boolean(rule: JsonObject, key: String, default: Boolean, location: String): Boolean {
        if (!rule.has(key)) return default
        val value = rule.get(key)
        require(value.isJsonPrimitive && value.asJsonPrimitive.isBoolean) { "$location $key must be a boolean." }
        return value.asBoolean
    }

    private fun strings(rule: JsonObject, key: String, location: String): List<String>? {
        if (!rule.has(key)) return null
        val value = rule.get(key)
        require(value.isJsonArray && !value.asJsonArray.isEmpty) { "$location $key must be a non-empty array." }
        return value.asJsonArray.map {
            require(it.isJsonPrimitive && it.asJsonPrimitive.isString && it.asString.isNotBlank()) {
                "$location $key must contain non-empty strings."
            }
            it.asString
        }
    }

    private fun validDomain(value: String): Boolean {
        if (value.startsWith("geosite:")) return validGeoCategory(value.substringAfter(':'))
        if (!value.startsWith("domain:") && !value.startsWith("full:")) return false
        val domain = value.substringAfter(':')
        return domain.length in 1..253 && domain.split('.').all { domainLabel.matches(it) }
    }

    private fun validIp(value: String): Boolean {
        if (value.startsWith("geoip:")) return validGeoCategory(value.substringAfter(':'))
        val parts = value.split('/')
        if (parts.size > 2) return false
        val address = parts.first()
        val ipv6 = ':' in address
        if (parts.size == 2) {
            if (!parts[1].matches(Regex("[0-9]{1,3}"))) return false
            val prefix = parts[1].toInt()
            if (prefix !in 0..(if (ipv6) 128 else 32)) return false
        }
        if (!ipv6) {
            val octets = address.split('.')
            return octets.size == 4 && octets.all {
                it.matches(Regex("0|[1-9][0-9]{0,2}")) && it.toInt() <= 255
            }
        }
        if (!address.matches(Regex("[0-9a-fA-F:.]+"))) return false
        return try {
            // Only numeric IPv6 literals reach this call; importing rules never performs DNS lookups.
            InetAddress.getByName(address)
            true
        } catch (_: UnknownHostException) {
            false
        }
    }

    private fun validGeoCategory(value: String): Boolean = value.matches(Regex("[a-zA-Z0-9][a-zA-Z0-9@!._-]{0,127}"))
}
