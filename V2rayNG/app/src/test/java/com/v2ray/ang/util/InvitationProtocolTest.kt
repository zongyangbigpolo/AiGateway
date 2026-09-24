package com.v2ray.ang.util

import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class InvitationProtocolTest {
    private val origin = InvitationProtocol.origin("https://invite.example.com", false)
    private val token = "A".repeat(43)

    private fun rejects(reason: InvitationError, block: () -> Unit) {
        try {
            block()
            fail("Expected $reason")
        } catch (e: InvitationException) {
            assertEquals(reason, e.reason)
        }
    }

    @Test fun codeIsNormalizedWithoutAllowingUnicodeOrControlCharacters() {
        assertEquals("ABCD-1234", InvitationProtocol.code("  abcd-1234 \n"))
        listOf("short", "ABCDEF你好", "ABCDEFG\nH", "A".repeat(129)).forEach {
            rejects(InvitationError.CODE) { InvitationProtocol.code(it) }
        }
    }

    @Test fun serviceMustBeHttpsOriginExceptExplicitDebugLoopback() {
        assertEquals("https://invite.example.com/", origin.toString())
        listOf("http://invite.example.com", "https://a.com/path", "https://a.com/?key=a",
            "https://a.com/#x", "https://user:password@a.com", "http://10.0.2.2:18084").forEach {
            rejects(InvitationError.SERVICE_URL) { InvitationProtocol.origin(it, false) }
        }
        assertEquals(18084, InvitationProtocol.origin("http://10.0.2.2:18084", true).port)
        rejects(InvitationError.SERVICE_URL) { InvitationProtocol.origin("http://10.0.2.2.evil.com", true) }
    }

    @Test fun subscriptionCannotRedirectTrustToAnotherOriginOrPath() {
        val good = "https://invite.example.com/v1/subscriptions/$token"
        assertEquals(good, InvitationProtocol.subscriptionUrl(origin, good).toString())
        listOf("https://evil.com/v1/subscriptions/$token",
            "http://invite.example.com/v1/subscriptions/$token",
            "https://invite.example.com:444/v1/subscriptions/$token",
            "$good?secret=x", "$good#fragment",
            "https://invite.example.com/other/$token",
            "https://user@invite.example.com/v1/subscriptions/$token").forEach {
            rejects(InvitationError.RESPONSE) { InvitationProtocol.subscriptionUrl(origin, it) }
        }
    }

    @Test fun grantRequiresTypedBoundedNameAndSubscription() {
        val good = """{"name":"Test","subscription_url":"https://invite.example.com/v1/subscriptions/$token"}"""
        assertEquals("Test", InvitationProtocol.grant(origin, good).name)
        listOf("[]", "{}", good.replace("\"Test\"", "42"), good.replace("Test", "A".repeat(101)),
            good.replace("Test", "\\n"), "{").forEach {
            rejects(InvitationError.RESPONSE) { InvitationProtocol.grant(origin, it) }
        }
    }

    @Test fun rawAndBase64NodeSubscriptionsAreAccepted() {
        val content = "socks://127.0.0.1:18081#Test\nvless://uuid@example.com:443"
        assertEquals(2, InvitationProtocol.nodeLines(content).size)
        val encoded = Base64.getEncoder().encodeToString(content.toByteArray())
        assertEquals(InvitationProtocol.nodeLines(content), InvitationProtocol.nodeLines(encoded))
        assertEquals(1, InvitationProtocol.nodeLines("socks://localhost:1080\nsocks://localhost:1080").size)
    }

    @Test fun configDocumentsAndNestedSubscriptionsCannotEnterNodeImporter() {
        listOf("", "{}", """{"outbounds":[]}""", "https://another.example.com/sub",
            "socks://localhost:1080\nhttps://another.example.com/sub", "socks://", "garbage",
            (1..501).joinToString("\n") { "socks://localhost:$it" },
            "socks://" + "a".repeat(InvitationProtocol.MAX_SUBSCRIPTION_BYTES)).forEach {
            rejects(InvitationError.RESPONSE) { InvitationProtocol.nodeLines(it) }
        }
    }

    @Test fun invalidUtf8IsNotSilentlyReplaced() {
        rejects(InvitationError.RESPONSE) { InvitationProtocol.utf8(byteArrayOf(0xc3.toByte(), 0x28)) }
    }

    @Test fun normalizedAttemptsHaveStableServiceSpecificFingerprints() {
        val fingerprint = InvitationProtocol.fingerprint(origin, InvitationProtocol.code("abcd-1234"))
        assertEquals(fingerprint, InvitationProtocol.fingerprint(origin, InvitationProtocol.code(" ABCD-1234 ")))
        assertNotEquals(fingerprint, InvitationProtocol.fingerprint(InvitationProtocol.origin("https://other.com"), "ABCD-1234"))
        assertFalse(fingerprint.contains("ABCD"))
    }
}
