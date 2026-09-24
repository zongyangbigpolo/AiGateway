package com.v2ray.ang.util

import org.junit.Assert.*
import org.junit.Test

class InvitationEntitlementTest {
    @Test fun expirationIsBasedOnServerTimeAndMonotonicRequestStart() {
        val lease = InvitationProtocol.entitlement("""{"server_time":1700000000,"expires_at":1700000060,"name":"Test"}""")
        assertEquals(65000L, lease.deadlineFrom(5000))
        // Receiving this response 10 seconds later leaves 50 seconds, not a new minute.
        assertEquals(50000L, lease.deadlineFrom(5000) - 15000)
    }

    @Test fun acceptsFractionalUnixSeconds() {
        val lease = InvitationProtocol.entitlement("""{"server_time":1700000000.25,"expires_at":1700000001.75}""")
        assertEquals(1600L, lease.deadlineFrom(100))
    }

    @Test fun expiredOrInvalidResponsesNeverCreateALease() {
        listOf(
            "{}", "[]",
            """{"server_time":2,"expires_at":2}""",
            """{"server_time":3,"expires_at":2}""",
            """{"server_time":-1,"expires_at":20}""",
            """{"server_time":1,"expires_at":"20"}""",
            """{"server_time":1,"expires_at":1e100}""",
            """{"server_time":1,"expires_at":315360002}"""
        ).forEach { json ->
            try {
                InvitationProtocol.entitlement(json)
                fail("Accepted invalid entitlement")
            } catch (_: InvitationException) {
                // Expected: no entitlement is usable.
            }
        }
    }
}
