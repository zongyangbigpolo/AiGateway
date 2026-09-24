package com.v2ray.ang.core

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.system.OsConstants
import androidx.core.content.ContextCompat
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.contracts.ServiceControl
import com.v2ray.ang.dto.OutboundTrafficStat
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.extension.isComplexType
import com.v2ray.ang.extension.toast
import com.v2ray.ang.extension.toastError
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.InvitationAccess
import com.v2ray.ang.handler.NotificationManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.handler.SpeedtestManager
import com.v2ray.ang.root.RootManager
import com.v2ray.ang.service.CoreProxyOnlyService
import com.v2ray.ang.service.CoreRootService
import com.v2ray.ang.service.CoreVpnService
import com.v2ray.ang.service.DialerNativeService
import com.v2ray.ang.service.DialerWebviewService
import com.v2ray.ang.service.IDialerService
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.MessageUtil
import com.v2ray.ang.util.Utils
import com.v2ray.ang.util.InvitationException
import com.v2ray.ang.util.InvitationError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import java.io.IOException
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.ProcessFinder
import java.lang.ref.SoftReference
import java.net.InetSocketAddress

object CoreServiceManager {

    private var coreController: CoreController? = null
    private val lifecycle = ServiceLifecycle<ServiceControl>()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var receiverService: Service? = null
    private val mMsgReceive = ReceiveMessageHandler()
    private var currentConfig: ProfileItem? = null
    private var processFinder: XrayProcessFinder? = null
    private var browserDialer: IDialerService? = null
    private val authorization = InvitationAuthorization<ServiceControl>()
    private var authorizationJob: Job? = null
    private var expirationJob: Job? = null
    private var authorizedSubject: InvitationAccess.Subject? = null

    var serviceControl: SoftReference<ServiceControl>? = null
        private set

    /** Called before allocating a VPN or starting any native work. */
    private fun prepareServiceStart(owner: ServiceControl): Boolean {
        if (!lifecycle.start(owner)) {
            if (lifecycle.failed) {
                val service = owner.getService()
                val message = service.getString(R.string.gateway_shutdown_failed)
                LogUtil.e(AppConfig.TAG, message)
                MessageUtil.sendMsg2UI(service, AppConfig.MSG_STATE_START_FAILURE, message)
            }
            if (lifecycle.stopping) lifecycle.requestRestart()
            if (lifecycle.owner !== owner) owner.getService().stopSelf()
            return false
        }
        serviceControl = SoftReference(owner)
        return true
    }

    fun startAuthorized(owner: ServiceControl, start: () -> Unit): Boolean {
        if (!prepareServiceStart(owner)) return false
        val service = owner.getService()
        NotificationManager.showNotification(null)
        val filter = IntentFilter(AppConfig.BROADCAST_ACTION_SERVICE).apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        ContextCompat.registerReceiver(service, mMsgReceive, filter, Utils.receiverFlags())
        receiverService = service
        authorizationJob = serviceScope.launch {
            try {
                val subject = InvitationAccess.selected()
                val deadline = withContext(Dispatchers.IO) {
                    withTimeout(25_000) { InvitationAccess.validate(subject) }
                }
                if (!lifecycle.acceptsCallback(owner)) return@launch
                if (InvitationAccess.selected() != subject) throw InvitationException(InvitationError.REJECTED)
                authorizedSubject = subject
                authorization.grant(owner, subject.key, deadline)
                expirationJob = serviceScope.launch expiry@{
                    while (isActive && lifecycle.acceptsCallback(owner)) {
                        val remaining = authorization.remaining(owner, SystemClock.elapsedRealtime())
                        if (remaining == 0L || !InvitationAccess.stillAssigned(subject)) {
                            rejectAuthorization(owner)
                            return@expiry
                        }
                        // Recheck elapsed time on wake; wall-clock changes cannot extend a lease.
                        delay(minOf(1000L, remaining))
                    }
                }
                start()
                while (isActive && lifecycle.acceptsCallback(owner)) {
                    delay(60_000)
                    val renewed = withContext(Dispatchers.IO) {
                        withTimeout(25_000) { InvitationAccess.validate(subject) }
                    }
                    if (!lifecycle.acceptsCallback(owner)) return@launch
                    if (!InvitationAccess.stillAssigned(subject)) throw InvitationException(InvitationError.REJECTED)
                    authorization.grant(owner, subject.key, renewed)
                }
            } catch (_: TimeoutCancellationException) {
                rejectAuthorization(owner)
            } catch (_: IOException) {
                rejectAuthorization(owner)
            }
        }
        return true
    }

    private fun rejectAuthorization(owner: ServiceControl) {
        if (!lifecycle.acceptsCallback(owner)) return
        lifecycle.cancelRestart()
        authorization.clear(owner)
        val service = owner.getService()
        val message = service.getString(R.string.invitation_authorization_failed)
        LogUtil.w(AppConfig.TAG, "Gateway stopped: invitation authorization unavailable or expired")
        MessageUtil.sendMsg2UI(service, AppConfig.MSG_STATE_START_FAILURE, message)
        service.toastError(message)
        owner.stopService()
    }

    fun restartVService(context: Context) {
        // The activity and core services live in different Android processes.
        MessageUtil.sendMsg2Service(context, AppConfig.MSG_STATE_RESTART, "")
    }

    /**
     * Starts the V2Ray service from a toggle action.
     * @param context The context from which the service is started.
     * @return True if the service was started successfully, false otherwise.
     */
    fun startVServiceFromToggle(context: Context): Boolean {
        if (MmkvManager.getSelectServer().isNullOrEmpty()) {
            context.toast(R.string.app_tile_first_use)
            return false
        }
        try {
            startContextService(context)
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "StartCore-Manager: ${e.message}", e)
            context.toast(e.message ?: e.javaClass.simpleName)
            return false
        }
        return true
    }

    /**
     * Starts the V2Ray service.
     * @param context The context from which the service is started.
     * @param guid The GUID of the server configuration to use (optional).
     */
    fun startVService(context: Context, guid: String? = null) {
        LogUtil.i(AppConfig.TAG, "StartCore-Manager: startVService from ${context::class.java.simpleName}")

        if (guid != null) {
            MmkvManager.setSelectServer(guid)
        }

        try {
            startContextService(context)
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "StartCore-Manager: ${e.message}", e)
            context.toast(e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * Stops the V2Ray service.
     * @param context The context from which the service is stopped.
     */
    fun stopVService(context: Context) {
        lifecycle.cancelRestart()
        //context.toast(R.string.toast_services_stop)
        MessageUtil.sendMsg2Service(context, AppConfig.MSG_STATE_STOP, "")
    }

    /**
     * Checks if the V2Ray service is running.
     * @return True if the service is running, false otherwise.
     */
    fun isRunning() = !lifecycle.stopping && coreController?.isRunning == true

    /**
     * Gets the name of the currently running server.
     * @return The name of the running server.
     */
    fun getRunningServerName() = currentConfig?.remarks.orEmpty()

    /**
     * Starts the context service for V2Ray.
     * Chooses between VPN service or Proxy-only service based on user settings.
     * @param context The context from which the service is started.
     * @throws IllegalStateException if the core is already running, no server is selected,
     *   server config cannot be decoded, or server configuration is invalid.
     * @throws Exception if the foreground service fails to start.
     */
    @Throws(Exception::class)
    private fun startContextService(context: Context) {
        check(!lifecycle.failed) { context.getString(R.string.gateway_shutdown_failed) }
        if (lifecycle.stopping) {
            lifecycle.requestRestart()
            return
        }
        if (lifecycle.owner != null) {
            LogUtil.w(AppConfig.TAG, "StartCore-Manager: Core already running")
            return
        }

        val guid = MmkvManager.getSelectServer()
            ?: run {
                LogUtil.e(AppConfig.TAG, "StartCore-Manager: No server selected")
                error(context.getString(R.string.app_tile_first_use))
            }

        val config = MmkvManager.decodeServerConfig(guid)
            ?: run {
                LogUtil.e(AppConfig.TAG, "StartCore-Manager: Failed to decode server config")
                error(context.getString(R.string.toast_config_file_invalid))
            }

        if (!config.configType.isComplexType()
            && !Utils.isValidUrl(config.server)
            && !Utils.isPureIpAddress(config.server.orEmpty())
        ) {
            LogUtil.e(AppConfig.TAG, "StartCore-Manager: Invalid server configuration")
            error(context.getString(R.string.toast_config_file_invalid))
        }

        // refresh socks port when enabled dynamic socks port
        SettingsManager.refreshRuntimeSocksPort()

//        val result = V2rayConfigUtil.getV2rayConfig(context, guid)
//        if (!result.status) error(result.errorMessage.ifBlank { "Failed to get V2Ray config" })

        if (config.insecure == true) {
            context.toastError(R.string.toast_allow_insecure_deprecated)
            context.toastError(R.string.toast_allow_insecure_deprecated)
        }

        if (MmkvManager.decodeSettingsBool(AppConfig.PREF_PROXY_SHARING)) {
            context.toast(R.string.toast_warning_pref_proxysharing_short)
        } else {
            context.toast(R.string.toast_services_start)
        }

        val isRootMode = SettingsManager.isRootMode()
        if (isRootMode && !RootManager.isRootAvailable()) {
            LogUtil.e(AppConfig.TAG, "StartCore-Manager: root mode requires root but none available")
            error(context.getString(R.string.toast_root_required))
        }

        val intent = if (isRootMode) {
            LogUtil.i(AppConfig.TAG, "StartCore-Manager: Starting Root service")
            Intent(context.applicationContext, CoreRootService::class.java)
        } else if (SettingsManager.isVpnMode()) {
            LogUtil.i(AppConfig.TAG, "StartCore-Manager: Starting VPN service")
            Intent(context.applicationContext, CoreVpnService::class.java)
        } else {
            LogUtil.i(AppConfig.TAG, "StartCore-Manager: Starting Proxy service")
            Intent(context.applicationContext, CoreProxyOnlyService::class.java)
        }

        try {
            ContextCompat.startForegroundService(context, intent)
        } catch (e: SecurityException) {
            LogUtil.e(AppConfig.TAG, "StartCore-Manager: Missing permission to start foreground service", e)
            throw IllegalStateException(e.message ?: e.javaClass.simpleName, e)
        } catch (e: RuntimeException) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                e.javaClass.name == "android.app.ForegroundServiceStartNotAllowedException"
            ) {
                LogUtil.e(AppConfig.TAG, "StartCore-Manager: Foreground service start not allowed", e)
                throw IllegalStateException(e.message ?: e.javaClass.simpleName, e)
            }
            throw e
        }
    }

    /**
     * Refer to the official documentation for [registerReceiver](https://developer.android.com/reference/androidx/core/content/ContextCompat#registerReceiver(android.content.Context,android.content.BroadcastReceiver,android.content.IntentFilter,int):
     * `registerReceiver(Context, BroadcastReceiver, IntentFilter, int)`.
     * Starts the V2Ray core service.
     */
    fun startCoreLoop(vpnInterface: ParcelFileDescriptor?): Boolean {
        if (coreController?.isRunning == true || lifecycle.stopping) {
            LogUtil.w(AppConfig.TAG, "StartCore-Manager: Core already running")
            return false
        }

        val service = getService()
        if (service == null) {
            LogUtil.e(AppConfig.TAG, "StartCore-Manager: Service is null")
            return false
        }

        try {
            val owner = lifecycle.owner ?: throw InvitationException(InvitationError.REJECTED)
            val subject = InvitationAccess.selected()
            if (!authorization.permits(owner, subject.key, SystemClock.elapsedRealtime())) {
                throw InvitationException(InvitationError.REJECTED)
            }
            doStartCoreLoop(service, vpnInterface)
            return true
        } catch (_: InvitationException) {
            lifecycle.owner?.let(::rejectAuthorization)
            return false
        } catch (e: Exception) {
            val message = e.message?.takeUnless { it.isBlank() } ?: e.javaClass.simpleName
            LogUtil.e(AppConfig.TAG, "StartCore-Manager: $message", e)
            MessageUtil.sendMsg2UI(service, AppConfig.MSG_STATE_START_FAILURE, message)
            NotificationManager.cancelNotification()
            return false
        }
    }

    @Throws(Exception::class)
    private fun doStartCoreLoop(service: Service, vpnInterface: ParcelFileDescriptor?) {
        val owner = lifecycle.owner ?: error("No service session")
        CoreNativeManager.initCoreEnv(service)
        val coreController = CoreNativeManager.newCoreController(CoreCallback(owner))
        this.coreController = coreController
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            processFinder = XrayProcessFinder(service)
            coreController.registerProcessFinder(processFinder)
        }
        val guid = MmkvManager.getSelectServer() ?: error("No server selected")
        val config = MmkvManager.decodeServerConfig(guid) ?: error("Failed to decode server config")

        LogUtil.i(AppConfig.TAG, "StartCore-Manager: Starting core loop for ${config.remarks}")
        val result = CoreConfigManager.getV2rayConfig(service, guid)
        LogUtil.d(AppConfig.TAG, result.content)
        if (!result.status) {
            error(result.errorMessage.ifBlank { "Failed to get V2Ray config" })
        }

        currentConfig = config
        var tunFd = vpnInterface?.fd ?: 0
        val dialerAddr = if (currentConfig?.browserDialerMode.isNullOrEmpty()) {
            ""
        } else {
            "127.0.0.1:${Utils.findRandomFreePort()}"
        }
        if (SettingsManager.isUsingHevTun()) {
            tunFd = 0
        }

        NotificationManager.showNotification(currentConfig)
        CoreNativeManager.reconcileBrowserDialer(dialerAddr)
        coreController.startLoop(result.content, tunFd)
        val subject = authorizedSubject ?: throw InvitationException(InvitationError.REJECTED)
        if (!authorization.permits(owner, subject.key, SystemClock.elapsedRealtime())) {
            throw InvitationException(InvitationError.REJECTED)
        }

        if (!coreController.isRunning) {
            error("Core failed to start")
        }

        if (browserDialer != null) {
            browserDialer!!.stop()
            browserDialer = null
        }
        if (config.browserDialerMode == "OkHttp") {
            browserDialer = DialerNativeService()
            browserDialer!!.start(service, dialerAddr)
        } else if (config.browserDialerMode == "WebView") {
            browserDialer = DialerWebviewService()
            browserDialer!!.start(service, dialerAddr)
        }

        MessageUtil.sendMsg2UI(service, AppConfig.MSG_STATE_START_SUCCESS, "")
        NotificationManager.startSpeedNotification()
        LogUtil.i(AppConfig.TAG, "StartCore-Manager: Core started successfully")
    }

    /**
     * Stops exactly this session. Native teardown is awaited off-main; descriptors and
     * Android service state are cleaned up on main, before stop success is published.
     */
    fun stopCoreLoop(
        owner: ServiceControl,
        beforeCoreStop: suspend () -> Unit = {},
        afterCoreStop: () -> Unit = {}
    ) {
        serviceScope.launch {
            if (!lifecycle.beginStop(owner)) return@launch
            authorizationJob?.cancel()
            authorizationJob = null
            expirationJob?.cancel()
            expirationJob = null
            authorization.clear(owner)
            authorizedSubject = null
            val service = owner.getService()
            val controller = coreController
            var successful = true
            fun cleanup(label: String, action: () -> Unit) {
                try {
                    action()
                } catch (e: Exception) {
                    successful = false
                    LogUtil.e(AppConfig.TAG, "StartCore-Manager: $label", e)
                }
            }
            cleanup("Failed to stop traffic notification") { NotificationManager.stopSpeedNotification() }
            withContext(Dispatchers.IO) {
                try {
                    beforeCoreStop()
                } catch (e: Exception) {
                    successful = false
                    LogUtil.e(AppConfig.TAG, "StartCore-Manager: Failed to stop routing", e)
                }
                cleanup("Failed to stop V2Ray loop") {
                    controller?.stopLoop()
                    check(controller?.isRunning != true) { "Native core is still running" }
                }
            }
            cleanup("Failed to release service resources", afterCoreStop)
            cleanup("Failed to stop browser dialer") {
                CoreNativeManager.reconcileBrowserDialer("")
                browserDialer?.stop()
            }
            browserDialer = null
            if (receiverService === service) {
                cleanup("Failed to unregister receiver") { service.unregisterReceiver(mMsgReceive) }
                receiverService = null
            }
            cleanup("Failed to cancel notification") { NotificationManager.cancelNotification() }
            service.stopSelf()
            completeStop(service, lifecycle.onStopped(owner, successful))
        }
    }

    fun onServiceDestroyed(owner: ServiceControl) {
        completeStop(owner.getService(), lifecycle.onDestroyed(owner))
    }

    private fun completeStop(service: Service, completion: ServiceLifecycle.Completion?) {
        if (completion == null) return
        coreController = null
        processFinder = null
        currentConfig = null
        serviceControl = null
        if (completion.successful) {
            MessageUtil.sendMsg2UI(service, AppConfig.MSG_STATE_STOP_SUCCESS, "")
        } else {
            MessageUtil.sendMsg2UI(service, AppConfig.MSG_STATE_START_FAILURE,
                service.getString(R.string.gateway_shutdown_failed))
        }
        if (completion.restart) startVService(service.applicationContext)
    }

    /**
     * Queries and resets all outbound traffic counters in one core call.
     * Go side format: tag,direction,value;tag,direction,value;
     */
    fun queryAllOutboundTrafficStats(): List<OutboundTrafficStat> {
        val payload = coreController?.queryAllOutboundTrafficStats() ?: return emptyList()

        val result = ArrayList<OutboundTrafficStat>()

        payload.split(';').forEach { entry ->
            if (entry.isBlank()) return@forEach

            val parts = entry.split(',', limit = 3)
            if (parts.size != 3) return@forEach

            val value = parts[2].toLongOrNull() ?: return@forEach

            result.add(
                OutboundTrafficStat(
                    tag = parts[0],
                    direction = parts[1],
                    value = value,
                )
            )
        }
//        LogUtil.d(AppConfig.TAG, "Queried outbound traffic stats: $result")
        return result
    }

    /**
     * Measures the connection delay for the current V2Ray configuration.
     * Tests with primary URL first, then falls back to alternative URL if needed.
     * Also fetches remote IP information if the delay test was successful.
     */
    private fun measureV2rayDelay() {
        val coreController = coreController ?: return
        if (!isRunning()) {
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            val service = getService() ?: return@launch
            var time = -1L
            var errorStr = ""

            try {
                time = coreController.measureDelay(SettingsManager.getDelayTestUrl())
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "StartCore-Manager: Failed to measure delay", e)
                errorStr = e.message?.substringAfter("\":") ?: "empty message"
            }
            if (time == -1L) {
                try {
                    time = coreController.measureDelay(SettingsManager.getDelayTestUrl(true))
                } catch (e: Exception) {
                    LogUtil.e(AppConfig.TAG, "StartCore-Manager: Failed to measure delay", e)
                    errorStr = e.message?.substringAfter("\":") ?: "empty message"
                }
            }

            val result = if (time >= 0) {
                service.getString(R.string.connection_test_available, time)
            } else {
                service.getString(R.string.connection_test_error, errorStr)
            }
            MessageUtil.sendMsg2UI(service, AppConfig.MSG_MEASURE_DELAY_SUCCESS, result)

            // Only fetch IP info if the delay test was successful
            if (time >= 0) {
                SpeedtestManager.getRemoteIPInfo()?.let { ip ->
                    MessageUtil.sendMsg2UI(service, AppConfig.MSG_MEASURE_DELAY_SUCCESS, "$result\n$ip")
                }
            }
        }
    }

    /**
     * Gets the current service instance.
     * @return The current service instance, or null if not available.
     */
    private fun getService(): Service? {
        return serviceControl?.get()?.getService()
    }

    /**
     * Core callback handler implementation for handling V2Ray core events.
     * Handles startup, shutdown, socket protection, and status emission.
     */
    private class CoreCallback(private val owner: ServiceControl) : CoreCallbackHandler {
        /**
         * Called when V2Ray core starts up.
         * @return 0 for success, any other value for failure.
         */
        override fun startup(): Long {
            return 0
        }

        /**
         * Called when V2Ray core shuts down.
         * @return 0 for success, any other value for failure.
         */
        override fun shutdown(): Long {
            // Native callbacks may run while the core mutex is held. Never re-enter
            // stopLoop or resolve the target through mutable global serviceControl.
            mainHandler.post {
                if (lifecycle.acceptsCallback(owner)) owner.stopService()
            }
            return 0
        }

        /**
         * Called when V2Ray core emits status information.
         * @param l Status code.
         * @param s Status message.
         * @return Always returns 0.
         */
        override fun onEmitStatus(l: Long, s: String?): Long {
            return 0
        }
    }

    /**
     * Process finder implementation for Xray core.
     * Uses ConnectivityManager to find the owning UID of a connection based on network parameters.
     */
    private class XrayProcessFinder(context: Context) : ProcessFinder {
        private val cm: ConnectivityManager? = context.getSystemService(ConnectivityManager::class.java)

        override fun findProcessByConnection(network: String, srcIP: String, srcPort: Long, destIP: String, destPort: Long): Long {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return -1L
            if (cm == null) return -1L
            val proto = when (network) {
                "tcp" -> OsConstants.IPPROTO_TCP
                "udp" -> OsConstants.IPPROTO_UDP
                else -> return -1L
            }

            if (destIP.isBlank() || destPort == 0L) {
                LogUtil.d(AppConfig.TAG, "ProcessFinder: Find $network connection from $srcIP:$srcPort to :$destPort, (no dest)")
                return -1L
            }

            return try {
                val uid = cm.getConnectionOwnerUid(
                    proto,
                    InetSocketAddress(srcIP, srcPort.toInt()),
                    InetSocketAddress(destIP, destPort.toInt())
                ).toLong()
                LogUtil.d(AppConfig.TAG, "ProcessFinder: Find $network connection from $srcIP:$srcPort to $destIP:$destPort, uid=$uid")
                //LogUtil.d(AppConfig.TAG, "ProcessFinder: Find $network connection from $srcIP:$srcPort to $destIP:$destPort, uid=$uid,${PackageUidResolver.uidToPackageName(uid.toString())}")

                uid
            } catch (_: Exception) {
                -1L
            }
        }
    }

    /**
     * Broadcast receiver for handling messages sent to the service.
     * Handles registration, service control, and screen events.
     */
    private class ReceiveMessageHandler : BroadcastReceiver() {
        /**
         * Handles received broadcast messages.
         * Processes service control messages and screen state changes.
         * @param ctx The context in which the receiver is running.
         * @param intent The intent being received.
         */
        override fun onReceive(ctx: Context?, intent: Intent?) {
            val serviceControl = serviceControl?.get() ?: return
            when (intent?.getIntExtra("key", 0)) {
                AppConfig.MSG_REGISTER_CLIENT -> {
                    if (isRunning()) {
                        MessageUtil.sendMsg2UI(serviceControl.getService(), AppConfig.MSG_STATE_RUNNING, "")
                    } else {
                        MessageUtil.sendMsg2UI(serviceControl.getService(), AppConfig.MSG_STATE_NOT_RUNNING, "")
                    }
                }

                AppConfig.MSG_UNREGISTER_CLIENT -> {
                    // nothing to do
                }

                AppConfig.MSG_STATE_START -> {
                    // nothing to do
                }

                AppConfig.MSG_STATE_STOP -> {
                    LogUtil.i(AppConfig.TAG, "StartCore-Manager: Stop service")
                    lifecycle.cancelRestart()
                    serviceControl.stopService()
                }

                AppConfig.MSG_STATE_RESTART -> {
                    LogUtil.i(AppConfig.TAG, "StartCore-Manager: Restart service")
                    if (lifecycle.requestRestart()) serviceControl.stopService()
                }

                AppConfig.MSG_INVITATION_AUTHORIZATION_FAILED -> {
                    if (authorizedSubject?.subscriptionId == intent.getStringExtra("content")) {
                        rejectAuthorization(serviceControl)
                    }
                }

                AppConfig.MSG_MEASURE_DELAY -> {
                    measureV2rayDelay()
                }
            }

            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    LogUtil.i(AppConfig.TAG, "StartCore-Manager: Screen off")
                    NotificationManager.stopSpeedNotification()
                }

                Intent.ACTION_SCREEN_ON -> {
                    LogUtil.i(AppConfig.TAG, "StartCore-Manager: Screen on")
                    NotificationManager.startSpeedNotification()
                }
            }
        }
    }
}