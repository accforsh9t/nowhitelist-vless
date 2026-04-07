package com.nowlhitelist.vpn.vpn

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.util.Log
import com.nowlhitelist.vpn.data.TunnelConfig

sealed interface VpnConnectResult {
    object Started : VpnConnectResult
    object PermissionRequired : VpnConnectResult
    object Failed : VpnConnectResult
}

class VpnTunnelController(
    private val context: Context,
    private val onPermissionRequest: (Intent) -> Unit = { intent -> context.startActivity(intent) }
) {
    private var activeTunnelId: String? = null
    private var pendingTunnel: TunnelConfig? = null
    private val tag = "NowhitelistVpnCtrl"

    private fun buildAndStart(
        config: TunnelConfig,
        whitelistDomains: List<String> = emptyList(),
        telegramIpCidrs: List<String> = emptyList()
    ): Boolean {
        val rawConfig = VlessConfigFactory.buildRawConfig(config, whitelistDomains, telegramIpCidrs)
        return runCatching {
            val started = NowhitelistVpnService.start(
                context = context,
                rawConfig = rawConfig,
                serverAddress = config.serverAddress,
                serverPort = config.serverPort
            )
            if (!started) {
                throw IllegalStateException("Xray service returned false on start")
            }
            activeTunnelId = config.id
        }
            .onSuccess {
                Log.i(tag, "Tunnel started: ${config.id} (${config.serverAddress}:${config.serverPort})")
            }
            .onFailure { throwable ->
                activeTunnelId = null
                Log.e(tag, "Tunnel start failed: ${throwable.message}", throwable)
            }
            .isSuccess
    }

    fun connect(
        config: TunnelConfig,
        whitelistDomains: List<String> = emptyList(),
        telegramIpCidrs: List<String> = emptyList()
    ): VpnConnectResult {
        if (activeTunnelId != null && activeTunnelId != config.id) {
            Log.i(tag, "Stopping previous active tunnel before start: $activeTunnelId")
            NowhitelistVpnService.stop(context)
            activeTunnelId = null
        }

        val prepareIntent = VpnService.prepare(context)
        if (prepareIntent != null) {
            pendingTunnel = config
            return try {
                Log.i(tag, "VPN permission required for ${config.name}")
                onPermissionRequest(prepareIntent)
                VpnConnectResult.PermissionRequired
            } catch (_: Exception) {
                Log.e(tag, "Failed to open VPN permission screen")
                pendingTunnel = null
                VpnConnectResult.Failed
            }
        }

        return if (buildAndStart(config, whitelistDomains, telegramIpCidrs)) {
            VpnConnectResult.Started
        } else {
            VpnConnectResult.Failed
        }
    }

    fun retryPendingAfterPermission(
        whitelistDomains: List<String> = emptyList(),
        telegramIpCidrs: List<String> = emptyList()
    ): VpnConnectResult {
        val pending = pendingTunnel ?: return VpnConnectResult.Failed
        pendingTunnel = null

        return if (buildAndStart(pending, whitelistDomains, telegramIpCidrs)) {
            VpnConnectResult.Started
        } else {
            VpnConnectResult.Failed
        }
    }

    fun isPermissionGranted(): Boolean {
        return VpnService.prepare(context) == null
    }

    fun requestPendingPermission(): Boolean {
        val pending = pendingTunnel ?: return false
        val prepareIntent = VpnService.prepare(context) ?: return false
        return try {
            Log.i(tag, "Reopening VPN permission screen for ${pending.id}")
            onPermissionRequest(prepareIntent)
            true
        } catch (_: Exception) {
            Log.e(tag, "Failed to reopen VPN permission screen")
            false
        }
    }

    fun clearPendingConnect() {
        pendingTunnel = null
    }

    fun getPendingTunnelId(): String? = pendingTunnel?.id

    fun disconnect(config: TunnelConfig): Boolean {
        if (activeTunnelId != null && activeTunnelId != config.id) return false

        val stopped = runCatching { NowhitelistVpnService.stop(context) }.getOrDefault(false)
        if (stopped) {
            activeTunnelId = null
        } else if (activeTunnelId == null) {
            activeTunnelId = null
        }
        return stopped
    }

    fun isActiveTunnel(configId: String): Boolean {
        return activeTunnelId == configId
    }

    fun getActiveTunnelId(): String? {
        return activeTunnelId
    }
}
