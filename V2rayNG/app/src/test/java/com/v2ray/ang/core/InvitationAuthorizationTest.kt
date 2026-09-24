package com.v2ray.ang.core

import org.junit.Assert.*
import org.junit.Test

class InvitationAuthorizationTest {
    @Test fun cachedNodesAloneNeverAuthorizeStartup() {
        assertFalse(InvitationAuthorization<Any>().permits(Any(), "node", 0))
    }

    @Test fun expiresAtDeadlineWithoutAnOfflineGracePeriod() {
        val gate = InvitationAuthorization<Any>()
        val owner = Any()
        gate.grant(owner, "node", 1000)
        assertTrue(gate.permits(owner, "node", 999))
        assertFalse(gate.permits(owner, "node", 1000))
        assertEquals(0L, gate.remaining(owner, 5000))
    }

    @Test fun anotherNodeOrServiceCannotReuseTheGrant() {
        val gate = InvitationAuthorization<Any>()
        val owner = Any()
        gate.grant(owner, "node-a", 1000)
        assertFalse(gate.permits(owner, "node-b", 1))
        assertFalse(gate.permits(Any(), "node-a", 1))
    }

    @Test fun stoppingRequiresAFreshAuthorizationEvenBeforeExpiry() {
        val gate = InvitationAuthorization<Any>()
        val owner = Any()
        gate.grant(owner, "node", 1000)
        gate.clear(owner)
        assertFalse(gate.permits(owner, "node", 10))
        assertEquals(0L, gate.remaining(owner, 10))
    }

    @Test fun staleCleanupDoesNotInvalidateANewServiceSession() {
        val gate = InvitationAuthorization<Any>()
        val oldOwner = Any()
        val newOwner = Any()
        gate.grant(oldOwner, "node", 1000)
        gate.grant(newOwner, "node", 2000)
        gate.clear(oldOwner)
        assertTrue(gate.permits(newOwner, "node", 1500))
    }

    @Test fun renewedServerLeaseMayShortenExistingAccess() {
        val gate = InvitationAuthorization<Any>()
        val owner = Any()
        gate.grant(owner, "node", 10000)
        gate.grant(owner, "node", 500)
        assertFalse(gate.permits(owner, "node", 501))
    }
}
