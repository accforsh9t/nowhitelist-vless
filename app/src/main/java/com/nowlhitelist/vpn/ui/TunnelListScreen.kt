package com.nowlhitelist.vpn.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.nowlhitelist.vpn.data.TunnelConfig
import com.nowlhitelist.vpn.data.TunnelRuntimeStatus

@Composable
fun TunnelListScreen(
    tunnels: List<TunnelConfig>,
    tunnelStatuses: Map<String, TunnelRuntimeStatus>,
    onAdd: () -> Unit,
    onEdit: (TunnelConfig) -> Unit,
    onRemove: (String) -> Unit,
    onToggle: (String, Boolean) -> Unit,
    onToggleBypass: (String, Boolean) -> Unit,
    pendingPermissionTunnel: String?,
    onRequestVpnPermission: () -> Unit
) {
    Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Tunnels")
            Button(onClick = onAdd) {
                Text("Add")
            }
        }

        if (pendingPermissionTunnel != null) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
                shape = RoundedCornerShape(10.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Text(
                        text = "VPN permission required for \"$pendingPermissionTunnel\"",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Button(
                        onClick = onRequestVpnPermission,
                        modifier = Modifier.padding(top = 8.dp)
                    ) {
                        Text("Grant VPN access on phone")
                    }
                }
            }
        }

        if (tunnels.isEmpty()) {
            Text(
                text = "No tunnels yet. Add one to start protected traffic.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 12.dp)
            )
            return
        }

        LazyColumn(
            modifier = Modifier.padding(top = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(tunnels, key = { it.id }) { tunnel ->
                TunnelRow(
                    tunnel = tunnel,
                    status = tunnelStatuses[tunnel.id],
                    onEdit = { onEdit(tunnel) },
                    onRemove = { onRemove(tunnel.id) },
                    onToggle = { checked -> onToggle(tunnel.id, checked) },
                    onToggleBypass = { checked -> onToggleBypass(tunnel.id, checked) }
                )
            }
        }
    }
}

@Composable
private fun TunnelRow(
    tunnel: TunnelConfig,
    status: TunnelRuntimeStatus?,
    onEdit: () -> Unit,
    onRemove: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onToggleBypass: (Boolean) -> Unit
) {
    val runtime = status ?: TunnelRuntimeStatus()
    val connectionState = if (runtime.isChecking) "Checking..." else if (runtime.isConnected) "Connected" else "Disconnected"
    val statusColor = if (runtime.isConnected) Color(0xFF2ECC71) else Color(0xFFFF6B6B)
    val pingText = runtime.pingMs?.let { "${it}ms" } ?: "—"

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = tunnel.name,
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        text = "${tunnel.serverAddress}:${tunnel.serverPort}",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Switch(
                    checked = tunnel.enabled,
                    onCheckedChange = onToggle
                )
            }

            Text(
                text = "Status: $connectionState",
                color = statusColor,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 6.dp)
            )
            Text(
                text = "Public IP: ${runtime.ipAddress} | Ping: $pingText",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 2.dp)
            )
            Text(
                text = "Traffic: RX ${toReadableBytes(runtime.rxBytes)} (${toReadableBytes(runtime.rxRateBps)}/s), TX ${toReadableBytes(runtime.txBytes)} (${toReadableBytes(runtime.txRateBps)}/s)",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 2.dp)
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Whitelist bypass: ${if (tunnel.whitelistBypassEnabled) "On" else "Off"}",
                    style = MaterialTheme.typography.bodySmall
                )
                TextButton(onClick = { onToggleBypass(!tunnel.whitelistBypassEnabled) }) {
                    Text(if (tunnel.whitelistBypassEnabled) "Disable bypass" else "Enable bypass")
                }
            }

            Text(
                text = "Traffic log",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(top = 4.dp)
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp)
            ) {
                runtime.logLines.takeLast(6).forEach { line ->
                    Text(
                        text = line,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 1.dp)
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onEdit) {
                    Text("Edit")
                }
                Spacer(modifier = Modifier.width(4.dp))
                TextButton(onClick = onRemove) {
                    Text("Delete")
                }
            }
        }
    }
}

private fun toReadableBytes(value: Long): String {
    if (value < 0) return "0 B"
    if (value < 1024) return "$value B"

    val unit = 1024.0
    var size = value.toDouble()
    val labels = arrayOf("KB", "MB", "GB", "TB")
    var index = -1

    while (size >= unit && index < labels.size - 1) {
        size /= unit
        index++
    }

    return if (index < 0) {
        String.format("%.1f B", size)
    } else {
        String.format("%.1f ${labels[index]}", size)
    }
}
