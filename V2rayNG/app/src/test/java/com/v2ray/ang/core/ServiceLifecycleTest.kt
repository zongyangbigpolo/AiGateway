package com.v2ray.ang.core

import org.junit.Assert.*
import org.junit.Test

class ServiceLifecycleTest {
    private val lifecycle = ServiceLifecycle<Any>()
    private val oldService = Any()
    private val replacement = Any()

    @Test
    fun `restart waits for native cleanup AND Android destruction`() {
        assertTrue(lifecycle.start(oldService))
        assertTrue(lifecycle.requestRestart())
        assertTrue(lifecycle.beginStop(oldService))
        assertFalse(lifecycle.start(replacement))

        // Native stop may take arbitrarily long; no timer releases this gate.
        assertNull(lifecycle.onDestroyed(oldService))
        assertFalse(lifecycle.start(replacement))
        assertEquals(ServiceLifecycle.Completion(true, true), lifecycle.onStopped(oldService, true))
        assertTrue(lifecycle.start(replacement))
    }

    @Test
    fun `native cleanup before onDestroy still cannot restart`() {
        lifecycle.start(oldService)
        lifecycle.requestRestart()
        lifecycle.beginStop(oldService)
        assertNull(lifecycle.onStopped(oldService, true))
        assertFalse(lifecycle.start(replacement))
        assertEquals(ServiceLifecycle.Completion(true, true), lifecycle.onDestroyed(oldService))
        assertTrue(lifecycle.start(replacement))
    }

    @Test
    fun `duplicate start does not replace a live session`() {
        assertTrue(lifecycle.start(oldService))
        assertFalse(lifecycle.start(oldService))
        assertFalse(lifecycle.start(replacement))
        assertSame(oldService, lifecycle.owner)
    }

    @Test
    fun `shutdown callback during stop is ignored and stop is idempotent`() {
        lifecycle.start(oldService)
        assertTrue(lifecycle.acceptsCallback(oldService))
        assertTrue(lifecycle.beginStop(oldService))
        assertFalse(lifecycle.acceptsCallback(oldService))
        assertFalse(lifecycle.beginStop(oldService))
        assertFalse(lifecycle.beginStop(replacement))
    }

    @Test
    fun `delayed old native callback cannot stop replacement`() {
        lifecycle.start(oldService)
        lifecycle.beginStop(oldService)
        lifecycle.onStopped(oldService, true)
        lifecycle.onDestroyed(oldService)
        lifecycle.start(replacement)

        assertFalse(lifecycle.acceptsCallback(oldService))
        assertFalse(lifecycle.beginStop(oldService))
        assertTrue(lifecycle.acceptsCallback(replacement))
        assertNull(lifecycle.onDestroyed(oldService))
        assertNull(lifecycle.onStopped(oldService, true))
        assertSame(replacement, lifecycle.owner)
    }

    @Test
    fun `multiple restart requests coalesce into one completion`() {
        lifecycle.start(oldService)
        lifecycle.requestRestart()
        lifecycle.beginStop(oldService)
        lifecycle.requestRestart()
        lifecycle.requestRestart()
        lifecycle.onStopped(oldService, true)
        assertEquals(ServiceLifecycle.Completion(true, true), lifecycle.onDestroyed(oldService))
        assertNull(lifecycle.onDestroyed(oldService))
        assertNull(lifecycle.onStopped(oldService, true))
    }

    @Test
    fun `explicit stop cancels queued restart`() {
        lifecycle.start(oldService)
        lifecycle.requestRestart()
        lifecycle.beginStop(oldService)
        lifecycle.cancelRestart()
        lifecycle.onStopped(oldService, true)
        assertEquals(ServiceLifecycle.Completion(true, false), lifecycle.onDestroyed(oldService))
    }

    @Test
    fun `start requested during stop is deferred until completion`() {
        lifecycle.start(oldService)
        lifecycle.beginStop(oldService)
        assertTrue(lifecycle.requestRestart())
        assertFalse(lifecycle.start(replacement))
        lifecycle.onDestroyed(oldService)
        assertEquals(ServiceLifecycle.Completion(true, true), lifecycle.onStopped(oldService, true))
    }

    @Test
    fun `failed teardown cannot report success or launch another native core`() {
        lifecycle.start(oldService)
        lifecycle.requestRestart()
        lifecycle.beginStop(oldService)
        assertNull(lifecycle.onStopped(oldService, false))
        assertNull(lifecycle.onStopped(oldService, true))
        assertEquals(ServiceLifecycle.Completion(false, false), lifecycle.onDestroyed(oldService))
        assertTrue(lifecycle.failed)
        assertFalse(lifecycle.start(replacement))
        assertFalse(lifecycle.requestRestart())
    }

    @Test
    fun `ordinary stop permits a subsequent fresh start without restarting automatically`() {
        lifecycle.start(oldService)
        lifecycle.beginStop(oldService)
        lifecycle.onStopped(oldService, true)
        assertEquals(ServiceLifecycle.Completion(true, false), lifecycle.onDestroyed(oldService))
        assertFalse(lifecycle.requestRestart())
        assertTrue(lifecycle.start(replacement))
        lifecycle.beginStop(replacement)
        lifecycle.onStopped(replacement, true)
        assertEquals(ServiceLifecycle.Completion(true, false), lifecycle.onDestroyed(replacement))
    }
}
