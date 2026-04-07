package com.nowlhitelist.vpn

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import android.util.Log
import androidx.core.content.ContextCompat
import com.nowlhitelist.vpn.data.TunnelViewModel
import com.nowlhitelist.vpn.ui.VpnShellApp
import com.nowlhitelist.vpn.ui.theme.NowlhitelistVpnTheme
import com.nowlhitelist.vpn.vpn.VpnTunnelController

class MainActivity : ComponentActivity() {
    private val tag = "NowhitelistMain"

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        Log.d(tag, "VPN permission resultCode=${result.resultCode}, data=${result.data}")
        if (::viewModel.isInitialized) {
            viewModel.onVpnPermissionResult(result.resultCode == RESULT_OK)
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            Log.i(tag, "POST_NOTIFICATIONS permission granted")
        } else {
            Log.w(tag, "POST_NOTIFICATIONS permission denied")
            if (::viewModel.isInitialized) {
                viewModel.postMessage("Enable notification permission for background VPN status updates.")
            }
        }
    }

    private lateinit var viewModel: TunnelViewModel

    private val vpnController by lazy {
        VpnTunnelController(this) { intent -> vpnPermissionLauncher.launch(intent) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel = TunnelViewModel(applicationContext, vpnController)

        requestNotificationPermissionIfNeeded()

        setContent {
            NowlhitelistVpnTheme {
                VpnShellApp(viewModel)
            }
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onResume() {
        super.onResume()
        if (!::viewModel.isInitialized) return
        if (vpnController.isPermissionGranted() && viewModel.pendingPermissionTunnelId() != null) {
            viewModel.onVpnPermissionResult(true)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::viewModel.isInitialized) {
            viewModel.stopMonitoringAll()
        }
    }
}
