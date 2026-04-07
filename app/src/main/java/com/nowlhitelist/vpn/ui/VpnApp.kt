package com.nowlhitelist.vpn.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.nowlhitelist.vpn.data.TunnelConfig
import com.nowlhitelist.vpn.data.TunnelViewModel
import com.nowlhitelist.vpn.data.VlessUriParser

private enum class Screen {
    Home,
    Add,
    BypassList
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VpnShellApp(viewModel: TunnelViewModel) {
    val snackbarHostState = remember { SnackbarHostState() }
    var screen by remember { mutableStateOf(Screen.Home) }

    LaunchedEffect(viewModel.lastMessage) {
        viewModel.lastMessage?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.clearMessage()
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Nowhitelist") },
                actions = {
                    if (screen == Screen.Home) {
                        TextButton(
                            onClick = {
                                viewModel.openBypassListEditor()
                                screen = Screen.BypassList
                            }
                        ) {
                            Text("Bypass")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
    ) { contentPadding ->
        Box(
            modifier = Modifier
                .padding(contentPadding)
                .fillMaxSize()
        ) {
            when (screen) {
                Screen.Home -> TunnelListScreen(
                    tunnels = viewModel.tunnels,
                    tunnelStatuses = viewModel.tunnelStatuses,
                    onAdd = { screen = Screen.Add },
                    onEdit = { tunnel ->
                        viewModel.selectTunnelForEdit(tunnel)
                        screen = Screen.Add
                    },
                    onRemove = viewModel::removeTunnel,
                    onToggle = viewModel::toggleTunnel,
                    onToggleBypass = viewModel::toggleWhitelistBypass,
                    pendingPermissionTunnel = viewModel.pendingPermissionTunnelId()?.let { id ->
                        viewModel.tunnels.firstOrNull { it.id == id }?.name
                    },
                    onRequestVpnPermission = viewModel::requestPendingVpnPermission
                )
                Screen.Add -> AddTunnelScreen(
                    initial = viewModel.editingTunnel,
                    onBack = {
                        viewModel.clearEditing()
                        screen = Screen.Home
                    },
                    onSave = {
                        viewModel.addTunnel(it)
                        if (viewModel.lastMessage?.startsWith("Tunnel") == true) {
                            screen = Screen.Home
                        }
                    },
                    onParseUri = { input ->
                        val parsed = VlessUriParser.parse(input)
                        if (input.isNotBlank() && parsed == null) {
                            viewModel.postMessage("Invalid VLESS link")
                        }
                        parsed
                    },
                    onApplySeed = { parsed ->
                        TunnelConfig(
                            name = parsed.name,
                            serverAddress = parsed.serverAddress,
                            serverPort = parsed.serverPort,
                            uuid = parsed.uuid,
                            publicKey = parsed.publicKey,
                            shortId = parsed.shortId,
                            sni = parsed.sni,
                            flow = parsed.flow,
                            fp = parsed.fp
                        )
                    }
                )
                Screen.BypassList -> BypassListScreen(
                    draft = viewModel.bypassListDraft,
                    domainCount = viewModel.bypassDomainsCount,
                    telegramIpCidrsDraft = viewModel.telegramIpCidrsDraft,
                    telegramIpCidrsCount = viewModel.telegramIpCidrsCount,
                    onValueChange = viewModel::updateBypassListDraft,
                    onTelegramIpCidrsChange = viewModel::updateTelegramIpCidrsDraft,
                    onBack = { screen = Screen.Home },
                    onSave = {
                        if (viewModel.saveBypassList()) {
                            screen = Screen.Home
                        }
                    }
                )
            }
        }
    }
}
