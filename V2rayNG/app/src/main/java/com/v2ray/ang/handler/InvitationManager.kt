package com.v2ray.ang.handler

import com.v2ray.ang.dto.entities.SubscriptionItem
import com.v2ray.ang.util.InvitationApi
import com.v2ray.ang.util.InvitationError
import com.v2ray.ang.util.InvitationException
import com.v2ray.ang.util.InvitationProtocol
import java.util.UUID

object InvitationManager {
    private const val ATTEMPT_PREFIX = "gateway_invitation_attempt_"

    data class Result(val subscriptionId: String, val name: String, val nodeCount: Int)

    suspend fun redeem(service: String, rawCode: String): Result {
        val origin = InvitationProtocol.origin(service)
        val code = InvitationProtocol.code(rawCode)
        val attemptKey = ATTEMPT_PREFIX + InvitationProtocol.fingerprint(origin, code)
        val requestId = MmkvManager.decodeSettingsString(attemptKey) ?: UUID.randomUUID().toString().also {
            if (!MmkvManager.encodeSettings(attemptKey, it)) throw InvitationException(InvitationError.STORAGE)
        }
        val api = InvitationApi()
        val grant = api.redeem(origin.toString(), code, requestId)
        val text = api.subscription(origin.toString(), grant.subscriptionUrl)
        val existing = MmkvManager.decodeSubscriptions().firstOrNull { it.subscription.url == grant.subscriptionUrl }
        val id = existing?.guid ?: "invite-" + InvitationProtocol.fingerprint(origin, grant.subscriptionUrl).take(32)
        // Validate and parse every node before changing an existing group.
        val count = AngConfigManager.importInvitationNodes(text, id)
        val subscription = existing?.subscription ?: SubscriptionItem()
        subscription.remarks = grant.name
        subscription.url = grant.subscriptionUrl
        subscription.invitationOrigin = origin.toString()
        subscription.lastUpdated = System.currentTimeMillis()
        MmkvManager.encodeSubscription(id, subscription)
        if (MmkvManager.decodeSubscription(id)?.url != grant.subscriptionUrl) {
            throw InvitationException(InvitationError.STORAGE)
        }
        SettingsChangeManager.makeSetupGroupTab()
        return Result(id, grant.name, count)
    }
}
