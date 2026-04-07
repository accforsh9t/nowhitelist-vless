package com.nowlhitelist.vpn.data

import android.content.Context
import android.app.ActivityManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.TrafficStats
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.nowlhitelist.vpn.vpn.VpnConnectResult
import com.nowlhitelist.vpn.vpn.VpnTunnelController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.HttpURLConnection
import java.net.NetworkInterface
import java.net.Socket
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.max

private data class HealthSnapshot(
    val pingMs: Long?,
    val hasVpnTransport: Boolean,
    val localIpAddress: String,
    val publicIpAddress: String,
    val rxBytes: Long,
    val txBytes: Long
)

class TunnelViewModel(
    private val context: Context,
    private val vpnController: VpnTunnelController
) {
    val tunnels = mutableStateListOf<TunnelConfig>()
    private val appContext = context.applicationContext
    val tunnelStatuses = mutableStateMapOf<String, TunnelRuntimeStatus>()
    private val activityManager = appContext.getSystemService(ActivityManager::class.java)
    private val preferences = appContext.getSharedPreferences("nowhitelist_vpn", Context.MODE_PRIVATE)
    private val whitelistBypassRepository = WhitelistBypassRepository(appContext)

    var editingTunnel by mutableStateOf<TunnelConfig?>(null)
        private set

    var lastMessage by mutableStateOf<String?>(null)
        private set

    var bypassListDraft by mutableStateOf(whitelistBypassRepository.getEditableText())
        private set

    var bypassDomainsCount by mutableStateOf(whitelistBypassRepository.getAvailableDomainsCount())
        private set

    var telegramIpCidrsDraft by mutableStateOf(whitelistBypassRepository.getTelegramIpCidrsText())
        private set

    var telegramIpCidrsCount by mutableStateOf(whitelistBypassRepository.getTelegramIpCidrsCount())
        private set

    private val monitorScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var monitorJob: Job? = null
    private var monitoringTunnelId: String? = null
    private val reconnectingTunnelIds = mutableSetOf<String>()
    private val reconnectAttempts = mutableMapOf<String, Int>()
    private val appUid = Process.myUid()
    private val connectivityManager = appContext.getSystemService(ConnectivityManager::class.java)

    private var pendingPermissionTunnelId: String? = null
    private var lastTrafficBytesByTunnel = mutableMapOf<String, Pair<Long, Long>>()
    private var tunnelLastStartedAt = mutableMapOf<String, Long>()
    private var lastTrafficLogAt = mutableMapOf<String, Long>()
    private var lastPublicIpCheckAt = mutableMapOf<String, Long>()
    private var publicIpCache = mutableMapOf<String, String>()

    private var isStateRestored = false

    companion object {
        private const val MONITOR_INTERVAL_MS = 4500L
        private const val RECONNECT_WAIT_MS = 2500L
        private const val FAILURE_THRESHOLD = 3
        private const val MAX_RECONNECT_ATTEMPTS = 3
        private const val LOG_LINES_LIMIT = 40
        private const val STARTUP_GRACE_MS = 12000L
        private const val TRAFFIC_LOG_INTERVAL_MS = 5000L
        private const val PING_TIMEOUT_MS = 1800
        private const val IP_CHECK_INTERVAL_MS = 15000L
        private val PUBLIC_IP_ENDPOINTS = listOf(
            "https://api.ipify.org?format=text",
            "https://ifconfig.me/ip"
        )
        private const val LOG_TAG = "NowhitelistVpnVM"
        private const val PREFS_KEY_TUNNELS = "tunnels_json"
    }

    init {
        restoreFromStorage()
        isStateRestored = true
        resumeEnabledTunnelsIfPossible()
    }

    fun selectTunnelForEdit(tunnel: TunnelConfig) {
        editingTunnel = tunnel
    }

    fun clearEditing() {
        editingTunnel = null
    }

    fun addTunnel(config: TunnelConfig) {
        val normalized = config.sanitized()
        if (!normalized.isValid) {
            lastMessage = "Fill all required fields"
            return
        }

        val withId = if (normalized.id.isBlank()) normalized.copy(id = UUID.randomUUID().toString()) else normalized
        val existing = tunnels.indexOfFirst { it.id == withId.id }

        if (existing >= 0) {
            val wasEnabled = tunnels[existing].enabled
            val wasBypassEnabled = tunnels[existing].whitelistBypassEnabled
            val previous = tunnels[existing]
            tunnels[existing] = withId.copy(
                enabled = wasEnabled,
                whitelistBypassEnabled = wasBypassEnabled
            )
            lastMessage = "Tunnel updated: ${withId.name}"

            if (previous.sni != withId.sni || previous.shortId != withId.shortId || previous.serverAddress != withId.serverAddress || previous.serverPort != withId.serverPort || previous.publicKey != withId.publicKey || previous.fp != withId.fp || previous.flow != withId.flow || previous.uuid != withId.uuid) {
                if (wasEnabled) {
                    log(withId.id, "Config changed for active tunnel")
                }
            }
            if (!wasEnabled) {
                tunnelStatuses[withId.id] = TunnelRuntimeStatus()
            }
        } else {
            val unique = withId.copy(name = if (withId.name.isBlank()) "Tunnel ${tunnels.size + 1}" else withId.name)
            tunnels.add(unique)
            tunnelStatuses[unique.id] = TunnelRuntimeStatus()
            lastMessage = "Tunnel saved: ${unique.name}"
        }

        editingTunnel = null
        persistState()
    }

    fun removeTunnel(id: String) {
        val idx = tunnels.indexOfFirst { it.id == id }
        if (idx == -1) return

        val tunnel = tunnels[idx]
        if (tunnel.enabled) {
            vpnController.disconnect(tunnel)
            stopMonitoring(id)
        }

        tunnels.removeAt(idx)
        tunnelStatuses.remove(id)
        if (pendingPermissionTunnelId == id) {
            pendingPermissionTunnelId = null
            vpnController.clearPendingConnect()
        }
        reconnectAttempts.remove(id)
        reconnectingTunnelIds.remove(id)
        log(id, "Tunnel removed")
        lastMessage = "Tunnel deleted"
        persistState()
    }

    fun toggleTunnel(id: String, enabled: Boolean) {
        val idx = tunnels.indexOfFirst { it.id == id }
        if (idx == -1) return

        val updated = tunnels[idx].copy(enabled = enabled)

        if (enabled) {
            if (!hasInternetNetwork()) {
                tunnels[idx] = updated.copy(enabled = false)
                log(updated.id, "Blocked start: no active internet-capable network")
                lastMessage = "ÐÐµÑ‚ Ð¸Ð½Ñ‚ÐµÑ€Ð½ÐµÑ‚Ð°. ÐŸÑ€Ð¾Ð²ÐµÑ€ÑŒ Wi-Fi/mobile ÑÐµÑ‚ÑŒ."
                return
            }

            monitorScope.launch {
                val whitelistDomains = resolveWhitelistDomains(updated)
                val telegramIpCidrs = resolveTelegramIpCidrs(updated)
                if (updated.whitelistBypassEnabled && whitelistDomains.isEmpty()) {
                    tunnels[idx] = updated.copy(enabled = false)
                    log(updated.id, "Whitelist bypass list is empty")
                    lastMessage = "Failed to load whitelist bypass list"
                    persistState()
                    return@launch
                }

                connectTunnel(updated, whitelistDomains, telegramIpCidrs)
            }
            return
        }

        val stopped = vpnController.disconnect(updated)
        if (!stopped) {
            tunnels[idx] = updated.copy(enabled = true)
            log(updated.id, "Failed to stop tunnel")
            lastMessage = "Failed to stop tunnel"
            return
        }

        stopMonitoring(updated.id)
        log(updated.id, "Tunnel stopped")
        reconnectAttempts.remove(updated.id)
        reconnectingTunnelIds.remove(updated.id)
        tunnels[idx] = updated
        tunnelStatuses[updated.id] = (tunnelStatuses[updated.id] ?: TunnelRuntimeStatus()).copy(
            isConnected = false,
            isChecking = false,
            pingMs = null
        )
        if (pendingPermissionTunnelId == updated.id) {
            pendingPermissionTunnelId = null
            vpnController.clearPendingConnect()
        }
        lastMessage = "Tunnel disabled: ${updated.name}"
        persistState()
    }

    fun toggleWhitelistBypass(id: String, enabled: Boolean) {
        val idx = tunnels.indexOfFirst { it.id == id }
        if (idx == -1) return

        val updated = tunnels[idx].copy(whitelistBypassEnabled = enabled)
        tunnels[idx] = updated
        persistState()

        monitorScope.launch {
            val whitelistDomains = if (enabled) whitelistBypassRepository.getDomains(forceRefresh = true) else emptyList()
            val telegramIpCidrs = if (enabled) whitelistBypassRepository.refreshTelegramIpCidrs(forceRefresh = true) else emptyList()
            val currentIdx = tunnels.indexOfFirst { it.id == id }
            if (currentIdx == -1) return@launch

            if (enabled && whitelistDomains.isEmpty()) {
                tunnels[currentIdx] = tunnels[currentIdx].copy(whitelistBypassEnabled = false)
                log(updated.id, "Failed to load whitelist bypass rules")
                lastMessage = "Failed to load whitelist bypass rules"
                persistState()
                return@launch
            }

            val latest = tunnels[currentIdx].copy(whitelistBypassEnabled = enabled)
            tunnels[currentIdx] = latest
            log(
                latest.id,
                if (enabled) "Whitelist bypass enabled (${whitelistDomains.size} domains)" else "Whitelist bypass disabled"
            )
            lastMessage =
                if (enabled) "Whitelist bypass enabled: ${latest.name}" else "Whitelist bypass disabled: ${latest.name}"
            persistState()

            if (latest.enabled) {
                restartTunnelWithRouting(latest, whitelistDomains, telegramIpCidrs)
            }
        }
    }

    fun openBypassListEditor() {
        bypassListDraft = whitelistBypassRepository.getEditableText()
        bypassDomainsCount = whitelistBypassRepository.getAvailableDomainsCount()
        telegramIpCidrsDraft = whitelistBypassRepository.getTelegramIpCidrsText()
        telegramIpCidrsCount = whitelistBypassRepository.getTelegramIpCidrsCount()
    }

    fun updateBypassListDraft(value: String) {
        bypassListDraft = value
    }

    fun updateTelegramIpCidrsDraft(value: String) {
        telegramIpCidrsDraft = value
    }

    fun saveBypassList(): Boolean {
        val savedCount = whitelistBypassRepository.saveDomainsText(bypassListDraft)
        if (savedCount == 0) {
            lastMessage = "Whitelist list is empty"
            return false
        }
        val savedTelegramCidrs = whitelistBypassRepository.saveTelegramIpCidrsText(telegramIpCidrsDraft)

        bypassListDraft = whitelistBypassRepository.getEditableText()
        bypassDomainsCount = savedCount
        telegramIpCidrsDraft = whitelistBypassRepository.getTelegramIpCidrsText()
        telegramIpCidrsCount = whitelistBypassRepository.getTelegramIpCidrsCount()
        lastMessage = "Whitelist list saved: $savedCount domains, ${telegramIpCidrsCount} Telegram CIDRs"

        tunnels.firstOrNull { it.enabled && it.whitelistBypassEnabled }?.let { activeTunnel ->
            log(activeTunnel.id, "Whitelist list updated ($savedCount domains, $savedTelegramCidrs Telegram CIDRs)")
            restartTunnelWithRouting(
                activeTunnel,
                whitelistBypassRepository.getCachedDomains(),
                whitelistBypassRepository.getTelegramIpCidrs()
            )
        }
        return true
    }

    fun onVpnPermissionResult(granted: Boolean) {
        val pendingId = pendingPermissionTunnelId
        if (!granted || pendingId == null) {
            pendingPermissionTunnelId = null
            vpnController.clearPendingConnect()
            if (pendingId != null) {
                withStateUpdate(pendingId) { current ->
                    current.copy(
                        isConnected = false,
                        isChecking = false,
                        ipAddress = "—",
                        pingMs = null,
                        rxRateBps = 0,
                        txRateBps = 0
                    )
                }
            }
            lastMessage = "VPN permission denied"
            return
        }

        monitorScope.launch {
            val idx = tunnels.indexOfFirst { it.id == pendingId }
            if (idx == -1) {
                pendingPermissionTunnelId = null
                lastMessage = "Tunnel was removed before permission result"
                return@launch
            }

            val tunnel = tunnels[idx]
            val whitelistDomains = resolveWhitelistDomains(tunnel)
            val telegramIpCidrs = resolveTelegramIpCidrs(tunnel)
            if (tunnel.whitelistBypassEnabled && whitelistDomains.isEmpty()) {
                pendingPermissionTunnelId = null
                lastMessage = "Failed to load whitelist bypass list"
                log(pendingId, "Whitelist bypass list unavailable after permission grant")
                return@launch
            }

            when (vpnController.retryPendingAfterPermission(whitelistDomains, telegramIpCidrs)) {
                is VpnConnectResult.Started -> {
                    val updated = tunnel.copy(enabled = true)
                    tunnels[idx] = updated
                    pendingPermissionTunnelId = null
                    startMonitoring(updated)
                    lastMessage = "Tunnel enabled: ${updated.name}"
                    log(updated.id, "VPN permission granted")
                }

                is VpnConnectResult.PermissionRequired, is VpnConnectResult.Failed -> {
                    pendingPermissionTunnelId = null
                    lastMessage = "Failed to start tunnel after permission granted"
                    log(pendingId, "Failed after permission grant")
                }
            }
        }
    }

    fun pendingPermissionTunnelId(): String? = pendingPermissionTunnelId

    fun requestPendingVpnPermission() {
        val pendingId = pendingPermissionTunnelId ?: return
        if (vpnController.requestPendingPermission()) {
            log(pendingId, "Opening Android VPN permission screen")
            lastMessage = "Grant VPN permission on phone"
            return
        }

        if (vpnController.isPermissionGranted()) {
            onVpnPermissionResult(true)
            return
        }

        lastMessage = "No pending VPN permission request"
    }

    private fun persistState() {
        if (!isStateRestored) return

        val array = JSONArray()
        tunnels.forEach { tunnel ->
            array.put(tunnel.toJson())
        }

        preferences.edit()
            .putString(PREFS_KEY_TUNNELS, array.toString())
            .apply()
    }

    private fun restoreFromStorage() {
        val stored = preferences.getString(PREFS_KEY_TUNNELS, null) ?: return

        runCatching {
            val array = JSONArray(stored)
            for (i in 0 until array.length()) {
                val json = array.getJSONObject(i)
                val restored = TunnelConfig.fromJson(json)
                tunnels.add(restored)
                tunnelStatuses[restored.id] = tunnelStatuses[restored.id] ?: TunnelRuntimeStatus()
            }
        }.onFailure { throwable ->
            Log.w(LOG_TAG, "Failed to restore tunnels from storage: ${throwable.message}", throwable)
            tunnels.clear()
            tunnelStatuses.clear()
        }
    }

    private fun resumeEnabledTunnelsIfPossible() {
        val enabledTunnels = tunnels.filter { it.enabled }
        if (enabledTunnels.isEmpty()) return

        if (!vpnController.isPermissionGranted()) {
            enabledTunnels.forEach { tunnel ->
                val idx = tunnels.indexOfFirst { it.id == tunnel.id }
                if (idx != -1) {
                    tunnels[idx] = tunnel.copy(enabled = false)
                    tunnelStatuses[tunnel.id] = tunnelStatuses[tunnel.id]?.copy(isChecking = false, isConnected = false) ?: TunnelRuntimeStatus()
                }
            }
            lastMessage = "VPN permission revoked. Re-approve to resume last tunnels."
            pendingPermissionTunnelId = null
            vpnController.clearPendingConnect()
            persistState()
            return
        }

        if (!hasInternetNetwork()) {
            enabledTunnels.forEach { tunnel ->
                tunnelStatuses[tunnel.id] = tunnelStatuses[tunnel.id]?.copy(isChecking = false, isConnected = false) ?: TunnelRuntimeStatus()
            }
            lastMessage = "No internet network. Will reconnect when network is available."
            return
        }

        enabledTunnels.firstOrNull()?.let { tunnel ->
            monitorScope.launch {
                val whitelistDomains = resolveWhitelistDomains(tunnel)
                val telegramIpCidrs = resolveTelegramIpCidrs(tunnel)
                if (tunnel.whitelistBypassEnabled && whitelistDomains.isEmpty()) {
                    val idx = tunnels.indexOfFirst { it.id == tunnel.id }
                    if (idx != -1) tunnels[idx] = tunnel.copy(enabled = false)
                    tunnelStatuses[tunnel.id] =
                        tunnelStatuses[tunnel.id]?.copy(isChecking = false, isConnected = false) ?: TunnelRuntimeStatus()
                    lastMessage = "Failed to load whitelist bypass list for ${tunnel.name}"
                    persistState()
                    return@launch
                }

                when (vpnController.connect(tunnel, whitelistDomains, telegramIpCidrs)) {
                    is VpnConnectResult.Started -> {
                        startMonitoring(tunnel)
                        log(tunnel.id, "Tunnel resumed after app restore")
                    }
                    is VpnConnectResult.PermissionRequired -> {
                        pendingPermissionTunnelId = tunnel.id
                        lastMessage = "VPN permission needed to resume ${tunnel.name}"
                    }
                    is VpnConnectResult.Failed -> {
                        val idx = tunnels.indexOfFirst { it.id == tunnel.id }
                        if (idx != -1) tunnels[idx] = tunnel.copy(enabled = false)
                        tunnelStatuses[tunnel.id] =
                            tunnelStatuses[tunnel.id]?.copy(isChecking = false, isConnected = false) ?: TunnelRuntimeStatus()
                        lastMessage = "Failed to resume ${tunnel.name}"
                        persistState()
                    }
                }
            }
        }
    }

    private fun startMonitoring(config: TunnelConfig) {
        stopMonitoring(config.id)

        val statusBase = tunnelStatuses[config.id] ?: TunnelRuntimeStatus()
        tunnelStatuses[config.id] = statusBase.copy(
            isConnected = false,
            isChecking = true,
            pingMs = null,
            rxRateBps = 0,
            txRateBps = 0
        )
        lastTrafficBytesByTunnel[config.id] = currentUidBytes()
        tunnelLastStartedAt[config.id] = SystemClock.elapsedRealtime()
        lastTrafficLogAt[config.id] = 0L
        reconnectAttempts[config.id] = 0
        monitoringTunnelId = config.id
        log(config.id, "Monitoring started")

        monitorJob = monitorScope.launch {
            var failureCount = 0
            var previousSampleAt = SystemClock.elapsedRealtime()
            var previousBytes = lastTrafficBytesByTunnel[config.id] ?: currentUidBytes()

            while (isTunnelActive(config.id)) {
                val snapshot = collectHealthSnapshot(config.serverAddress, config.serverPort, config.id)
                val now = SystemClock.elapsedRealtime()
                val deltaMs = max(1000L, now - previousSampleAt)

                val rxRate = calculateRate(snapshot.rxBytes, previousBytes.first, deltaMs)
                val txRate = calculateRate(snapshot.txBytes, previousBytes.second, deltaMs)

                previousBytes = Pair(snapshot.rxBytes, snapshot.txBytes)
                lastTrafficBytesByTunnel[config.id] = previousBytes
                previousSampleAt = now
                val hasVpnAddress = snapshot.localIpAddress.isNotBlank() && snapshot.localIpAddress != "â€”"
                val hasVpnRuntime = snapshot.hasVpnTransport || hasVpnAddress
                val hasPublicIp = snapshot.publicIpAddress.isNotBlank() && snapshot.publicIpAddress != "â€”"
                val startupMs = SystemClock.elapsedRealtime() - (tunnelLastStartedAt[config.id] ?: now)
                val isHealthy = hasVpnRuntime || (startupMs < STARTUP_GRACE_MS && isVpnServiceAlive())
                val prevStatusIp = tunnelStatuses[config.id]?.ipAddress

                val wasConnected = tunnelStatuses[config.id]?.isConnected ?: false

                withStateUpdate(config.id) { current ->
                    current.copy(
                        isConnected = isHealthy,
                        isChecking = false,
                        ipAddress = snapshot.publicIpAddress.ifBlank { current.ipAddress },
                        pingMs = snapshot.pingMs,
                        rxBytes = snapshot.rxBytes,
                        txBytes = snapshot.txBytes,
                        rxRateBps = rxRate,
                        txRateBps = txRate
                    )
                }

                if (isHealthy) {
                    if (!wasConnected) {
                        log(config.id, "Tunnel restored (publicIp=${snapshot.publicIpAddress.ifBlank { "n/a" }}, ping=${snapshot.pingMs?.let { "${it}ms" } ?: "n/a"})")
                    } else if (hasPublicIp && prevStatusIp != snapshot.publicIpAddress) {
                        log(config.id, "Public IP updated: ${snapshot.publicIpAddress}")
                    }

                    if ((SystemClock.elapsedRealtime() - (lastTrafficLogAt[config.id] ?: 0L)) >= TRAFFIC_LOG_INTERVAL_MS) {
                        log(
                            config.id,
                            "Traffic [rx=${formatBytes(snapshot.rxBytes)} (${formatBytes(rxRate)}/s), tx=${formatBytes(snapshot.txBytes)} (${formatBytes(txRate)}/s)]"
                        )
                        lastTrafficLogAt[config.id] = SystemClock.elapsedRealtime()
                    }
                    failureCount = 0
                    reconnectAttempts[config.id] = 0
                    delay(MONITOR_INTERVAL_MS)
                    continue
                }

                failureCount++
                if (snapshot.pingMs == null) {
                    log(config.id, "Health check failed ($failureCount/$FAILURE_THRESHOLD)")
                } else {
                    log(config.id, "VPN transport check failed ($failureCount/$FAILURE_THRESHOLD)")
                }

                if (failureCount >= FAILURE_THRESHOLD && shouldAutoReconnect(config.id)) {
                    failureCount = 0
                    launchReconnection(config)
                }

                withStateUpdate(config.id) { it.copy(isConnected = false, isChecking = false) }
                delay(MONITOR_INTERVAL_MS)
            }

            withStateUpdate(config.id) { it.copy(isChecking = false) }
            tunnelLastStartedAt.remove(config.id)
            lastTrafficLogAt.remove(config.id)
            monitoringTunnelId = null
        }
    }

    private fun stopMonitoring(id: String? = null) {
        val target = id ?: monitoringTunnelId
        if (target == null) return

        monitorJob?.cancel()
        monitorJob = null
        reconnectingTunnelIds.remove(target)
        tunnelLastStartedAt.remove(target)
        lastTrafficLogAt.remove(target)
        lastPublicIpCheckAt.remove(target)
        publicIpCache.remove(target)

        withStateUpdate(target) { current ->
            current.copy(isChecking = false)
        }

        if (monitoringTunnelId == target) {
            monitoringTunnelId = null
        }
        reconnectAttempts.remove(target)
        lastTrafficBytesByTunnel.remove(target)
    }

    private fun launchReconnection(config: TunnelConfig) {
        if (!shouldAutoReconnect(config.id)) return

        if (!reconnectingTunnelIds.add(config.id)) return

        monitorScope.launch {
            try {
                val attempts = (reconnectAttempts[config.id] ?: 0) + 1
                reconnectAttempts[config.id] = attempts

                if (attempts > MAX_RECONNECT_ATTEMPTS) {
                    log(config.id, "Max reconnect attempts reached")
                    return@launch
                }

                if (!isTunnelActive(config.id)) return@launch

                withStateUpdate(config.id) {
                    it.copy(isChecking = true)
                }
                log(config.id, "Reconnecting (${attempts}/$MAX_RECONNECT_ATTEMPTS)")

                vpnController.disconnect(config)
                delay(RECONNECT_WAIT_MS)
                val whitelistDomains = resolveWhitelistDomains(config)
                val telegramIpCidrs = resolveTelegramIpCidrs(config)
                if (config.whitelistBypassEnabled && whitelistDomains.isEmpty()) {
                    log(config.id, "Reconnect blocked: whitelist bypass list unavailable")
                    reconnectAttempts.remove(config.id)
                    return@launch
                }

                when (vpnController.connect(config, whitelistDomains, telegramIpCidrs)) {
                    is VpnConnectResult.Started -> {
                        log(config.id, "Reconnect started")
                        reconnectAttempts[config.id] = 0
                    }

                    is VpnConnectResult.PermissionRequired -> {
                        log(config.id, "Reconnect blocked: permission required")
                        reconnectAttempts.remove(config.id)
                    }

                    is VpnConnectResult.Failed -> {
                        log(config.id, "Reconnect attempt failed")
                    }
                }
            } finally {
                reconnectingTunnelIds.remove(config.id)
                withStateUpdate(config.id) {
                    it.copy(isChecking = false)
                }
            }
        }
    }

    private fun shouldAutoReconnect(tunnelId: String): Boolean {
        val isEnabled = tunnels.any { it.id == tunnelId && it.enabled }
        val currentlyReconnecting = tunnelId in reconnectingTunnelIds
        val attempts = reconnectAttempts[tunnelId] ?: 0
        return isEnabled && !currentlyReconnecting && attempts < MAX_RECONNECT_ATTEMPTS
    }

    private suspend fun resolveWhitelistDomains(config: TunnelConfig): List<String> {
        if (!config.whitelistBypassEnabled) return emptyList()
        return whitelistBypassRepository.getDomains(forceRefresh = false)
    }

    private suspend fun resolveTelegramIpCidrs(config: TunnelConfig): List<String> {
        if (!config.whitelistBypassEnabled) return emptyList()
        return whitelistBypassRepository.refreshTelegramIpCidrs(forceRefresh = false)
    }

    private fun connectTunnel(
        config: TunnelConfig,
        whitelistDomains: List<String>,
        telegramIpCidrs: List<String>
    ) {
        val idx = tunnels.indexOfFirst { it.id == config.id }
        if (idx == -1) return

        log(config.id, "Start requested")
        when (vpnController.connect(config, whitelistDomains, telegramIpCidrs)) {
            is VpnConnectResult.Started -> {
                tunnels[idx] = config
                pendingPermissionTunnelId = null
                startMonitoring(config)
                lastMessage = "Tunnel enabled: ${config.name}"
            }

            is VpnConnectResult.PermissionRequired -> {
                tunnels[idx] = config.copy(enabled = false)
                pendingPermissionTunnelId = config.id
                log(config.id, "VPN permission request sent")
                lastMessage = "VPN permission needed. Grant permission to continue."
            }

            is VpnConnectResult.Failed -> {
                tunnels[idx] = config.copy(enabled = false)
                pendingPermissionTunnelId = null
                stopMonitoring(config.id)
                log(config.id, "Failed to start tunnel")
                lastMessage = "Failed to start tunnel"
            }
        }
        persistState()
    }

    private fun restartTunnelWithRouting(
        config: TunnelConfig,
        whitelistDomains: List<String>,
        telegramIpCidrs: List<String>
    ) {
        if (!reconnectingTunnelIds.add(config.id)) return

        monitorScope.launch {
            try {
                withStateUpdate(config.id) {
                    it.copy(isChecking = true)
                }
                log(
                    config.id,
                    if (config.whitelistBypassEnabled) "Applying whitelist bypass routing" else "Applying full tunnel routing"
                )

                vpnController.disconnect(config)
                delay(RECONNECT_WAIT_MS)

                val idx = tunnels.indexOfFirst { it.id == config.id }
                if (idx == -1) return@launch

                val latest = tunnels[idx]
                if (!latest.enabled) return@launch

                connectTunnel(latest, whitelistDomains, telegramIpCidrs)
            } finally {
                reconnectingTunnelIds.remove(config.id)
                withStateUpdate(config.id) {
                    it.copy(isChecking = false)
                }
            }
        }
    }

    private suspend fun collectHealthSnapshot(host: String, port: Int, tunnelId: String): HealthSnapshot {
        return withContext(Dispatchers.IO) {
            val now = SystemClock.elapsedRealtime()
            val traffic = currentUidBytes()
            val publicIp = resolvePublicIpAddress(tunnelId, now)
            HealthSnapshot(
                pingMs = pingHost(host, port),
                hasVpnTransport = hasVpnTransport() || isVpnServiceAlive(),
                localIpAddress = currentVpnIpAddress(),
                publicIpAddress = publicIp,
                rxBytes = traffic.first,
                txBytes = traffic.second
            )
        }
    }

    private fun resolvePublicIpAddress(tunnelId: String, nowMs: Long): String {
        val canRefresh = publicIpCache[tunnelId].isNullOrBlank() ||
                nowMs - (lastPublicIpCheckAt[tunnelId] ?: 0L) >= IP_CHECK_INTERVAL_MS
        if (!canRefresh) {
            return publicIpCache[tunnelId] ?: "â€”"
        }

        lastPublicIpCheckAt[tunnelId] = nowMs
        val result = runCatching { fetchPublicIp() }.getOrNull()
        if (!result.isNullOrBlank()) {
            publicIpCache[tunnelId] = result
        }

        return publicIpCache[tunnelId] ?: "â€”"
    }

    private fun fetchPublicIp(): String? {
        for (endpoint in PUBLIC_IP_ENDPOINTS) {
            val endpointResult = runCatching {
                val connection = URL(endpoint).openConnection() as HttpURLConnection
                connection.connectTimeout = 2000
                connection.readTimeout = 2000
                connection.requestMethod = "GET"
                connection.connect()

                runCatching { connection.inputStream }.getOrNull()?.use { stream ->
                    BufferedReader(InputStreamReader(stream)).readLine()?.trim()
                }
            }.getOrNull()

            if (!endpointResult.isNullOrBlank()) {
                return endpointResult
            }
        }
        return null
    }

    private fun calculateRate(current: Long, previous: Long, deltaMs: Long): Long {
        return if (current < previous) 0L else (current - previous) * 1000L / deltaMs
    }

    private fun currentUidBytes(): Pair<Long, Long> {
        return Pair(max(0L, TrafficStats.getUidRxBytes(appUid)), max(0L, TrafficStats.getUidTxBytes(appUid)))
    }

    private fun pingHost(host: String, port: Int): Long? {
        return runCatching {
            val socket = Socket()
            val start = SystemClock.elapsedRealtime()
            try {
                socket.connect(InetSocketAddress(host, port), PING_TIMEOUT_MS)
                SystemClock.elapsedRealtime() - start
            } finally {
                runCatching { socket.close() }
            }
        }.getOrNull()
    }

    private fun hasInternetNetwork(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun hasVpnTransport(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    }

    private fun isVpnServiceAlive(): Boolean {
        val services = activityManager?.getRunningServices(Int.MAX_VALUE) ?: return false
        return services.any {
            it.service.className.contains("xrayngservice", ignoreCase = true) && it.pid > 0
        }
    }

    private fun currentVpnIpAddress(): String {
        val interfaces = runCatching { Collections.list(NetworkInterface.getNetworkInterfaces()) }.getOrDefault(emptyList())

        return interfaces
            .firstOrNull { it.isUp && !it.isLoopback && it.name.startsWith("tun") }
            ?.let { iface ->
                Collections.list(iface.inetAddresses)
                    .firstOrNull { address ->
                        address is Inet4Address && !address.isLoopbackAddress
                    }?.hostAddress
            } ?: "â€”"
    }

    private fun isTunnelActive(tunnelId: String): Boolean {
        return monitoringTunnelId == tunnelId && tunnels.any { it.id == tunnelId && it.enabled }
    }

    private fun formatBytes(value: Long): String {
        if (value < 0) return "0 B"
        if (value < 1024) return "$value B"

        val unit = 1024.0
        var size = value.toDouble()
        val labels = arrayOf("KB", "MB", "GB", "TB")
        var index = 0

        while (size >= unit && index < labels.size - 1) {
            size /= unit
            index++
        }

        return if (index == 0) {
            String.format("%.1f B", size)
        } else {
            String.format("%.1f ${labels[index - 1]}", size)
        }
    }

    private fun withStateUpdate(tunnelId: String, update: (TunnelRuntimeStatus) -> TunnelRuntimeStatus) {
        val current = tunnelStatuses[tunnelId] ?: TunnelRuntimeStatus()
        tunnelStatuses[tunnelId] = update(current)
    }

    private fun log(tunnelId: String, message: String) {
        val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        Log.d(LOG_TAG, "[$tunnelId] $message")
        withStateUpdate(tunnelId) { current ->
            val updatedLogs = (current.logLines + "[$timestamp] $message").takeLast(LOG_LINES_LIMIT)
            current.copy(logLines = updatedLogs)
        }
    }

    fun clearMessage() {
        lastMessage = null
    }

    fun postMessage(message: String) {
        lastMessage = message
    }

    fun stopMonitoringAll() {
        stopMonitoring()
        monitorScope.cancel()
    }
}


