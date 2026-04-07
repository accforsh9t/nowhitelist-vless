package com.nowlhitelist.vpn.data

data class TunnelRuntimeStatus(
    val isConnected: Boolean = false,
    val isChecking: Boolean = false,
    val ipAddress: String = "—",
    val pingMs: Long? = null,
    val rxBytes: Long = 0L,
    val txBytes: Long = 0L,
    val rxRateBps: Long = 0L,
    val txRateBps: Long = 0L,
    val logLines: List<String> = emptyList()
)
