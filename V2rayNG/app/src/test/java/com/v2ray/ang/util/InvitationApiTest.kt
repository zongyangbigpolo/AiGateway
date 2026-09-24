package com.v2ray.ang.util

import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

class InvitationApiTest {
    private val token = "A".repeat(43)
    private val api = InvitationApi(allowLocalHttp = true)

    private data class Reply(val status: Int, val text: String, val chunked: Boolean = false, val location: String? = null)

    private fun withServer(count: Int, reply: (String, String, String) -> Reply, action: suspend (String) -> Unit) {
        ServerSocket(0, 8, InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = 5000
            val origin = "http://127.0.0.1:${server.localPort}"
            val task = FutureTask<Unit> {
                repeat(count) {
                    server.accept().use { socket ->
                        socket.soTimeout = 5000
                        val reader = socket.getInputStream().bufferedReader()
                        val request = reader.readLine()
                        val headers = generateSequence { reader.readLine().takeIf { it.isNotEmpty() } }.toList()
                        val length = headers.firstOrNull { it.startsWith("Content-Length:", true) }
                            ?.substringAfter(':')?.trim()?.toInt() ?: 0
                        val body = StringBuilder()
                        repeat(length) { body.append(reader.read().toChar()) }
                        val response = reply(origin, request, body.toString())
                        val output = socket.getOutputStream()
                        val bytes = response.text.toByteArray()
                        val extra = response.location?.let { "Location: $it\r\n" }.orEmpty()
                        val size = if (response.chunked) "Transfer-Encoding: chunked" else "Content-Length: ${bytes.size}"
                        output.write("HTTP/1.1 ${response.status} Test\r\nConnection: close\r\n$extra$size\r\n\r\n".toByteArray())
                        if (response.chunked) output.write("${bytes.size.toString(16)}\r\n".toByteArray())
                        output.write(bytes)
                        if (response.chunked) output.write("\r\n0\r\n\r\n".toByteArray())
                        output.flush()
                    }
                }
            }
            Thread(task, "invitation-test-http").apply { isDaemon = true; start() }
            runBlocking { action(origin) }
            task.get(10, TimeUnit.SECONDS)
        }
    }

    private suspend fun rejects(reason: InvitationError, action: suspend () -> Unit) {
        try {
            action()
            fail("Expected $reason")
        } catch (e: InvitationException) {
            assertEquals(reason, e.reason)
        }
    }

    @Test fun sendsNormalizedCodeAndStableRequestIdThenDownloadsNodes() {
        val requestId = UUID.randomUUID().toString()
        withServer(4, { origin, request, body ->
            if (request.startsWith("POST /v1/invitations/redeem ")) {
                val json = JsonParser.parseString(body).asJsonObject
                assertEquals("ABCD-1234", json["code"].asString)
                assertEquals(requestId, json["request_id"].asString)
                Reply(200, """{"name":"Test group","subscription_url":"$origin/v1/subscriptions/$token"}""")
            } else {
                assertTrue(request.startsWith("GET /v1/subscriptions/$token "))
                Reply(200, "socks://localhost:18081#Test")
            }
        }) { origin ->
            repeat(2) {
                val grant = api.redeem(origin, " abcd-1234 ", requestId)
                assertEquals("Test group", grant.name)
                assertEquals("socks://localhost:18081#Test", api.subscription(origin, grant.subscriptionUrl))
            }
        }
    }

    @Test fun redirectsAreRejectedInsteadOfFollowingLocation() {
        withServer(1, { origin, _, _ -> Reply(307, "", location = "$origin/destination") }) { origin ->
            rejects(InvitationError.RESPONSE) { api.redeem(origin, "ABCD-1234", UUID.randomUUID().toString()) }
        }
    }

    @Test fun oversizedChunkedBodyIsRejectedWithoutContentLength() {
        withServer(1, { _, _, _ -> Reply(200, "A".repeat(InvitationProtocol.MAX_SUBSCRIPTION_BYTES + 1), true) }) { origin ->
            rejects(InvitationError.RESPONSE) { api.subscription(origin, "$origin/v1/subscriptions/$token") }
        }
    }

    @Test fun errorsDoNotExposeResponseBodyOrCredentials() {
        withServer(1, { _, _, _ -> Reply(429, "DO-NOT-EXPOSE-CODE") }) { origin ->
            rejects(InvitationError.RATE_LIMITED) { api.redeem(origin, "ABCD-1234", UUID.randomUUID().toString()) }
        }
    }

    @Test fun productionClientRejectsCleartextBeforeAnyRequest() {
        withServer(0, { _, _, _ -> error("No request expected") }) { origin ->
            rejects(InvitationError.SERVICE_URL) {
                InvitationApi(allowLocalHttp = false).redeem(origin, "ABCD-1234", UUID.randomUUID().toString())
            }

            @Test fun validationSendsTokenOnlyInPostBodyAndRejectsExpiredAuthorization() {
                withServer(2, { _, request, body ->
                    assertTrue(request.startsWith("POST /v1/entitlements/validate "))
                    assertFalse(request.contains(token))
                    assertEquals(token, JsonParser.parseString(body).asJsonObject["token"].asString)
                    Reply(404, """{"error":"invalid_code"}""")
                }) { origin ->
                    repeat(2) {
                        rejects(InvitationError.REJECTED) {
                            api.validate(origin, "$origin/v1/subscriptions/$token")
                        }
                    }
                }
            }

            @Test fun validationReturnsOnlyAFreshServerTimedLease() {
                withServer(1, { _, _, _ ->
                    Reply(200, """{"server_time":1700000000,"expires_at":1700000060,"name":"Test"}""")
                }) { origin ->
                    assertEquals(61000L, api.validate(origin, "$origin/v1/subscriptions/$token").deadlineFrom(1000))
                }
            }
        }
    }
}
