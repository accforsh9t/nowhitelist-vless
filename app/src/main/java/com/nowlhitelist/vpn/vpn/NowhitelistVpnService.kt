package com.nowlhitelist.vpn.vpn

import android.content.Context
import android.util.Log
import com.tim.vpnprotocols.xrayNg.XRayNgService

object NowhitelistVpnService {
    private const val TAG = "NowhitelistXray"

    fun start(
        context: Context,
        rawConfig: String,
        serverAddress: String = "google.com",
        serverPort: Int = 443
    ): Boolean {
        val serverEndpoint = formatServerEndpoint(serverAddress, serverPort)
        return runCatching {
            Log.i(TAG, "Requesting XRay service start for $serverEndpoint")
            XRayNgService.Companion.startService(
                context = context.applicationContext,
                config = rawConfig,
                domain = serverEndpoint,
                allowedApplications = emptyArray()
            )
            Log.i(TAG, "XRay service start requested for $serverEndpoint")
            true
        }.getOrElse {
            Log.e(TAG, "Failed to start XRay service", it)
            false
        }
    }

    private fun formatServerEndpoint(serverAddress: String, serverPort: Int): String {
        val needsIpv6Brackets =
            serverAddress.contains(':') && !serverAddress.startsWith('[') && !serverAddress.endsWith(']')
        return if (needsIpv6Brackets) {
            "[$serverAddress]:$serverPort"
        } else {
            "$serverAddress:$serverPort"
        }
    }

    fun stop(context: Context): Boolean {
        return runCatching {
            XRayNgService.Companion.stopService(context.applicationContext)
            Log.i(TAG, "XRay service stop requested")
            true
        }.getOrElse {
            Log.e(TAG, "Failed to stop XRay service", it)
            false
        }
    }
}
