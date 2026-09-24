package com.v2ray.ang.util

import com.google.gson.JsonParser
import com.google.gson.JsonParseException
import com.v2ray.ang.BuildConfig
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.IOException
import java.net.Proxy
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.CharacterCodingException
import java.security.MessageDigest
import java.util.Base64
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

enum class InvitationError {
    SERVICE_URL, CODE, REJECTED, RATE_LIMITED, NETWORK, RESPONSE, STORAGE
}

class InvitationException(val reason: InvitationError) : IOException(reason.name)

data class InvitationGrant(val name: String, val subscriptionUrl: String)

data class InvitationEntitlement(val serverTimeMillis: Long, val expiresAtMillis: Long) {
    fun deadlineFrom(requestStartedElapsed: Long): Long {
        val remaining = expiresAtMillis - serverTimeMillis
        if (remaining <= 0 || remaining > 3650L * 86400000L) {
            throw InvitationException(InvitationError.REJECTED)
        }
        return Math.addExact(requestStartedElapsed, remaining)
    }
}

object InvitationProtocol {
    const val MAX_SUBSCRIPTION_BYTES = 1024 * 1024
    const val MAX_NODES = 500
    private val schemes = setOf("ss", "vmess", "vless", "trojan", "socks", "socks4", "socks5", "wireguard", "hysteria2", "hy2")

    fun origin(raw: String, allowLocalHttp: Boolean = BuildConfig.DEBUG): HttpUrl {
        val url = raw.trim().toHttpUrlOrNull() ?: throw InvitationException(InvitationError.SERVICE_URL)
        val localHttp = allowLocalHttp && url.scheme == "http" &&
            url.host in setOf("127.0.0.1", "localhost", "::1", "10.0.2.2")
        if ((!url.isHttps && !localHttp) || url.username.isNotEmpty() || url.password.isNotEmpty() ||
            url.encodedPath != "/" || url.query != null || url.fragment != null
        ) throw InvitationException(InvitationError.SERVICE_URL)
        return url
    }

    fun code(raw: String): String {
        val value = raw.trim()
        if (!Regex("[A-Za-z0-9-]{8,128}").matches(value)) throw InvitationException(InvitationError.CODE)
        return value.uppercase(Locale.ROOT)
    }

    fun fingerprint(origin: HttpUrl, code: String): String =
        MessageDigest.getInstance("SHA-256").digest("${origin}\n$code".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    fun subscriptionUrl(origin: HttpUrl, raw: String): HttpUrl {
        val url = raw.toHttpUrlOrNull() ?: throw InvitationException(InvitationError.RESPONSE)
        if (url.scheme != origin.scheme || url.host != origin.host || url.port != origin.port ||
            url.username.isNotEmpty() || url.password.isNotEmpty() || url.query != null || url.fragment != null ||
            !Regex("/v1/subscriptions/[A-Za-z0-9_-]{32,256}").matches(url.encodedPath)
        ) throw InvitationException(InvitationError.RESPONSE)
        return url
    }

    fun grant(origin: HttpUrl, json: String): InvitationGrant {
        try {
            val root = JsonParser.parseString(json)
            if (!root.isJsonObject) throw InvitationException(InvitationError.RESPONSE)
            fun string(key: String): String {
                val value = root.asJsonObject.get(key)
                if (value == null || !value.isJsonPrimitive || !value.asJsonPrimitive.isString) {
                    throw InvitationException(InvitationError.RESPONSE)
                }
                return value.asString
            }
            val name = string("name").trim()
            if (name.isEmpty() || name.length > 100 || name.any { it.isISOControl() }) {
                throw InvitationException(InvitationError.RESPONSE)
            }
            return InvitationGrant(name, subscriptionUrl(origin, string("subscription_url")).toString())
        } catch (_: JsonParseException) {
            throw InvitationException(InvitationError.RESPONSE)
        }
    }

    fun utf8(bytes: ByteArray): String = try {
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    } catch (_: CharacterCodingException) {
        throw InvitationException(InvitationError.RESPONSE)
    }

    fun entitlement(json: String): InvitationEntitlement {
        try {
            val root = JsonParser.parseString(json)
            if (!root.isJsonObject) throw InvitationException(InvitationError.RESPONSE)
            fun timestamp(key: String): Long {
                val value = root.asJsonObject.get(key)
                if (value == null || !value.isJsonPrimitive || !value.asJsonPrimitive.isNumber) {
                    throw InvitationException(InvitationError.RESPONSE)
                }
                val seconds = value.asString.toDoubleOrNull()
                    ?: throw InvitationException(InvitationError.RESPONSE)
                if (!seconds.isFinite() || seconds <= 0 || seconds > 32_503_680_000L) {
                    throw InvitationException(InvitationError.RESPONSE)
                }
                return (seconds * 1000).toLong()
            }
            return InvitationEntitlement(timestamp("server_time"), timestamp("expires_at")).also {
                it.deadlineFrom(0)
            }
        } catch (_: JsonParseException) {
            throw InvitationException(InvitationError.RESPONSE)
        }
    }

    fun nodeLines(content: String): List<String> {
        if (content.toByteArray(Charsets.UTF_8).size > MAX_SUBSCRIPTION_BYTES) {
            throw InvitationException(InvitationError.RESPONSE)
        }
        var text = content.trim().removePrefix("\uFEFF")
        if (!text.contains("://")) {
            text = try {
                utf8(Base64.getDecoder().decode(text.filterNot { it.isWhitespace() }))
            } catch (_: IllegalArgumentException) {
                throw InvitationException(InvitationError.RESPONSE)
            }
        }
        val lines = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.distinct().toList()
        if (lines.isEmpty() || lines.size > MAX_NODES || lines.any {
                it.length > 16384 || it.any(Char::isISOControl) || it.substringBefore("://") !in schemes ||
                    !it.contains("://") || it.substringAfter("://").isEmpty()
            }
        ) throw InvitationException(InvitationError.RESPONSE)
        return lines
    }
}

class InvitationApi(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .proxy(Proxy.NO_PROXY)
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .build(),
    private val allowLocalHttp: Boolean = BuildConfig.DEBUG
) {
    suspend fun redeem(service: String, rawCode: String, requestId: String): InvitationGrant {
        val origin = InvitationProtocol.origin(service, allowLocalHttp)
        val code = InvitationProtocol.code(rawCode)
        UUID.fromString(requestId)
        val body = JsonUtil.toJson(mapOf("code" to code, "request_id" to requestId))
        val request = Request.Builder().url(origin.resolve("/v1/invitations/redeem")!!)
            .post(body.toRequestBody("application/json; charset=utf-8".toMediaType())).build()
        return InvitationProtocol.grant(origin, requestText(request, 8192))
    }

    suspend fun subscription(service: String, url: String): String {
        val origin = InvitationProtocol.origin(service, allowLocalHttp)
        val target = InvitationProtocol.subscriptionUrl(origin, url)
        return requestText(Request.Builder().url(target).get().build(), InvitationProtocol.MAX_SUBSCRIPTION_BYTES)
    }

    suspend fun validate(service: String, subscriptionUrl: String): InvitationEntitlement {
        val origin = InvitationProtocol.origin(service, allowLocalHttp)
        val target = InvitationProtocol.subscriptionUrl(origin, subscriptionUrl)
        val token = target.pathSegments.last()
        val body = JsonUtil.toJson(mapOf("token" to token))
        val request = Request.Builder().url(origin.resolve("/v1/entitlements/validate")!!)
            .post(body.toRequestBody("application/json; charset=utf-8".toMediaType())).build()
        return InvitationProtocol.entitlement(requestText(request, 8192))
    }

    private suspend fun requestText(request: Request, maxBytes: Int): String {
        execute(request).use { response ->
            if (!response.isSuccessful) {
                throw InvitationException(when (response.code) {
                    400, 401, 403, 404, 410 -> InvitationError.REJECTED
                    429 -> InvitationError.RATE_LIMITED
                    in 300..399 -> InvitationError.RESPONSE
                    else -> InvitationError.NETWORK
                })
            }
            val body = response.body
            if (body.contentLength() > maxBytes) throw InvitationException(InvitationError.RESPONSE)
            val source = body.source()
            if (source.request(maxBytes.toLong() + 1)) throw InvitationException(InvitationError.RESPONSE)
            return InvitationProtocol.utf8(source.readByteArray())
        }
    }

    private suspend fun execute(request: Request): Response = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                // Do not propagate URL-bearing network exceptions into UI or logs.
                continuation.resumeWith(Result.failure(InvitationException(InvitationError.NETWORK)))
            }

            override fun onResponse(call: Call, response: Response) {
                continuation.resume(response) { _, result, _ -> result.close() }
            }
        })
    }
}
