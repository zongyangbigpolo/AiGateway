package com.v2ray.ang.core

/**
 * Main-thread lifecycle gate. A replacement may start only after both native/resource
 * cleanup and Android's destruction of the previous service have completed.
 */
internal class ServiceLifecycle<T : Any> {
    var owner: T? = null
        private set
    var stopping = false
        private set
    var failed = false
        private set
    private var destroyed = false
    private var stopped = false
    private var restart = false

    data class Completion(val successful: Boolean, val restart: Boolean)

    fun start(candidate: T): Boolean {
        if (owner != null || failed) return false
        owner = candidate
        stopping = false
        destroyed = false
        stopped = false
        return true
    }

    fun acceptsCallback(candidate: T): Boolean = owner === candidate && !stopping

    fun beginStop(candidate: T): Boolean {
        if (!acceptsCallback(candidate)) return false
        stopping = true
        return true
    }

    fun requestRestart(): Boolean {
        if (owner == null || failed) return false
        restart = true
        return true
    }

    fun cancelRestart() {
        restart = false
    }

    fun onDestroyed(candidate: T): Completion? {
        if (owner !== candidate) return null
        destroyed = true
        return complete()
    }

    fun onStopped(candidate: T, successful: Boolean): Completion? {
        if (owner !== candidate || !stopping || stopped) return null
        stopped = true
        failed = !successful
        return complete()
    }

    private fun complete(): Completion? {
        if (!destroyed || !stopped) return null
        val result = Completion(!failed, restart && !failed)
        owner = null
        stopping = false
        restart = false
        return result
    }
}
