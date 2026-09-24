package com.v2ray.ang.handler

import android.os.SystemClock
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.dto.entities.SubscriptionItem
import com.v2ray.ang.util.InvitationApi
import com.v2ray.ang.util.InvitationError
import com.v2ray.ang.util.InvitationException
import com.v2ray.ang.util.InvitationProtocol

object InvitationAccess {
    data class Subject(val nodeId: String, val subscriptionId: String, val origin: String, val url: String) {
        val key: String get() = "$nodeId\n$subscriptionId\n$url"
    }

    fun subscriptionOrigin(subscription: SubscriptionItem): String {
        val expected = InvitationProtocol.origin(BuildConfig.INVITATION_SERVICE_URL)
        val saved = subscription.invitationOrigin
            ?: throw InvitationException(InvitationError.REJECTED)
        if (InvitationProtocol.origin(saved) != expected) throw InvitationException(InvitationError.REJECTED)
        InvitationProtocol.subscriptionUrl(expected, subscription.url)
        return expected.toString()
    }

    fun selected(): Subject {
        val guid = MmkvManager.getSelectServer() ?: throw InvitationException(InvitationError.REJECTED)
        val node = MmkvManager.decodeServerConfig(guid) ?: throw InvitationException(InvitationError.REJECTED)
        val subscription = MmkvManager.decodeSubscription(node.subscriptionId)
            ?: throw InvitationException(InvitationError.REJECTED)
        return Subject(guid, node.subscriptionId, subscriptionOrigin(subscription), subscription.url)
    }

    fun stillAssigned(subject: Subject): Boolean {
        val subscription = MmkvManager.decodeSubscription(subject.subscriptionId) ?: return false
        return subscription.invitationOrigin == subject.origin && subscription.url == subject.url
    }

    suspend fun validate(subject: Subject): Long {
        val started = SystemClock.elapsedRealtime()
        val entitlement = InvitationApi().validate(subject.origin, subject.url)
        val deadline = entitlement.deadlineFrom(started)
        if (deadline <= SystemClock.elapsedRealtime()) throw InvitationException(InvitationError.REJECTED)
        return deadline
    }
}
