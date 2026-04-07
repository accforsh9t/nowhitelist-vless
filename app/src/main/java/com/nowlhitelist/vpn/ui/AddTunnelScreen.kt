package com.nowlhitelist.vpn.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.nowlhitelist.vpn.data.ParsedVless
import com.nowlhitelist.vpn.data.TunnelConfig

@Composable
fun AddTunnelScreen(
    initial: TunnelConfig?,
    onBack: () -> Unit,
    onSave: (TunnelConfig) -> Unit,
    onParseUri: (String) -> ParsedVless?,
    onApplySeed: (ParsedVless) -> TunnelConfig
) {
    var name by remember(initial) { mutableStateOf(initial?.name.orEmpty()) }
    var host by remember(initial) { mutableStateOf(initial?.serverAddress.orEmpty()) }
    var port by remember(initial) { mutableStateOf(initial?.serverPort ?: 443) }
    var uuid by remember(initial) { mutableStateOf(initial?.uuid.orEmpty()) }
    var publicKey by remember(initial) { mutableStateOf(initial?.publicKey.orEmpty()) }
    var shortId by remember(initial) { mutableStateOf(initial?.shortId.orEmpty()) }
    var sni by remember(initial) { mutableStateOf(initial?.sni.orEmpty()) }
    var flow by remember(initial) { mutableStateOf(initial?.flow.orEmpty()) }
    var fp by remember(initial) { mutableStateOf(initial?.fp.orEmpty()) }
    var uriInput by remember { mutableStateOf("") }
    var validationError by remember { mutableStateOf<String?>(null) }
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(if (initial == null) "Create tunnel" else "Edit tunnel")

        OutlinedTextField(
            modifier = Modifier.fillMaxWidth(),
            value = uriInput,
            onValueChange = { uriInput = it },
            label = { Text("VLESS link") },
            placeholder = { Text("vless://...") }
        )

        Button(
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                val parsed = onParseUri(uriInput.trim())
                if (parsed == null) {
                    validationError = "Invalid VLESS link"
                    return@Button
                }

                val seed = onApplySeed(parsed)
                name = seed.name
                host = seed.serverAddress
                port = seed.serverPort
                uuid = seed.uuid
                publicKey = seed.publicKey
                shortId = seed.shortId
                sni = seed.sni
                flow = seed.flow
                fp = seed.fp
                validationError = "Values loaded from link"
            }
        ) {
            Text("Import from link")
        }

        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Name") },
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = host,
            onValueChange = { host = it },
            label = { Text("Server / Host") },
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = port.toString(),
            onValueChange = { port = it.toIntOrNull() ?: port },
            label = { Text("Port") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = uuid,
            onValueChange = { uuid = it },
            label = { Text("UUID") },
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = publicKey,
            onValueChange = { publicKey = it },
            label = { Text("Public key") },
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = shortId,
            onValueChange = { shortId = it },
            label = { Text("Short ID") },
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = sni,
            onValueChange = { sni = it },
            label = { Text("SNI") },
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = flow,
            onValueChange = { flow = it },
            label = { Text("Flow") },
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = fp,
            onValueChange = { fp = it },
            label = { Text("Fingerprint") },
            modifier = Modifier.fillMaxWidth()
        )

        validationError?.let { error ->
            Text(error, color = if (error == "Invalid VLESS link") Color(0xFFFF6B6B) else Color(0xFF9ACB58))
        }

        Button(
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                onSave(
                    TunnelConfig(
                        id = initial?.id.orEmpty(),
                        name = name.trim(),
                        serverAddress = host.trim(),
                        serverPort = port.coerceIn(1, 65535),
                        uuid = uuid.trim(),
                        publicKey = publicKey.trim(),
                        shortId = shortId.trim(),
                        sni = sni.trim(),
                        flow = flow.trim().ifBlank { "xtls-rprx-vision" },
                        fp = fp.trim().ifBlank { "chrome" }
                    )
                )
                validationError = null
            }
        ) {
            Text("Save")
        }

        TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
            Text("Cancel")
        }
    }
}

