package com.nowlhitelist.vpn

import android.app.Application
import android.util.Log
import com.tim.basevpn.refactor.initVpnDependencies

class NowhitelistApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        try {
            initVpnDependencies(
                notification = { service -> NowhitelistNotificationManager(service) }
            )
        } catch (throwable: Throwable) {
            Log.e("NowhitelistApplication", "Failed to init VPN dependencies", throwable)
        }
    }
}
