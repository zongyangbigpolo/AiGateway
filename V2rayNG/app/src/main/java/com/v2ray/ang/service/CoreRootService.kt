package com.v2ray.ang.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import com.v2ray.ang.AppConfig
import com.v2ray.ang.contracts.ServiceControl
import com.v2ray.ang.core.CoreServiceManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.root.RootProxyManager
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.MyContextWrapper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch

/**
 * Foreground service for the root (system-wide) run modes. Unlike [CoreVpnService] it
 * does not use Android VpnService — traffic is routed by iptables instead
 * (see [RootProxyManager]).
 *
 * The in-process core is started first (so its listener is up and the foreground
 * notification is posted promptly), then the root routing rules are installed off the
 * main thread. On teardown the rules are removed before the core stops.
 */
class CoreRootService : Service(), ServiceControl {

    private var setupJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        LogUtil.i(AppConfig.TAG, "StartCore-Root: Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        LogUtil.i(AppConfig.TAG, "StartCore-Root: command received")
        val accepted = CoreServiceManager.startAuthorized(this) {
            if (!CoreServiceManager.startCoreLoop(null)) {
                stopService()
            } else {
                setupJob = CoroutineScope(Dispatchers.IO).launch {
                    if (!RootProxyManager.start(this@CoreRootService)) {
                        LogUtil.e(AppConfig.TAG, "StartCore-Root: failed to start root mode, stopping")
                        stopService()
                    }
                }
            }
        }
        return if (accepted) START_STICKY else START_NOT_STICKY
    }

    override fun onDestroy() {
        stopService()
        super.onDestroy()
        CoreServiceManager.onServiceDestroyed(this)
    }

    override fun getService(): Service = this

    override fun startService() {
        // do nothing
    }

    override fun stopService() {
        CoreServiceManager.stopCoreLoop(this, beforeCoreStop = {
            // Setup must finish before removing its rules; neither may overlap a restart.
            if (setupJob != null) {
                setupJob?.cancelAndJoin()
                RootProxyManager.stop(this)
            }
        })
    }

    override fun vpnProtect(socket: Int): Boolean = true

    override fun onBind(intent: Intent?): IBinder? = null

    override fun attachBaseContext(newBase: Context?) {
        val context = newBase?.let {
            MyContextWrapper.wrap(newBase, SettingsManager.getLocale())
        }
        super.attachBaseContext(context)
    }
}
