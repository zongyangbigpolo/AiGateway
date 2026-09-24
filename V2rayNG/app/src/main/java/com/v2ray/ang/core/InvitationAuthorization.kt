package com.v2ray.ang.core

/** A process-local lease, never a persisted/offline permission to start the gateway. */
internal class InvitationAuthorization<T : Any> {
    private var owner: T? = null
    private var subject: String? = null
    private var deadline = 0L

    fun grant(candidate: T, identity: String, validUntilElapsed: Long) {
        owner = candidate
        subject = identity
        deadline = validUntilElapsed
    }

    fun permits(candidate: T, identity: String, elapsed: Long): Boolean =
        owner === candidate && subject == identity && elapsed < deadline

    fun remaining(candidate: T, elapsed: Long): Long =
        if (owner === candidate) (deadline - elapsed).coerceAtLeast(0) else 0

    fun clear(candidate: T) {
        if (owner !== candidate) return
        owner = null
        subject = null
        deadline = 0
    }
}
