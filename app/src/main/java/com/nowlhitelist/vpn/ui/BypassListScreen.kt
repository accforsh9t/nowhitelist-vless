package com.nowlhitelist.vpn.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

@Composable
fun BypassListScreen(
    draft: String,
    domainCount: Int,
    telegramIpCidrsDraft: String,
    telegramIpCidrsCount: Int,
    onValueChange: (String) -> Unit,
    onTelegramIpCidrsChange: (String) -> Unit,
    onBack: () -> Unit,
    onSave: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Text(
            text = "Whitelist bypass domains",
            style = MaterialTheme.typography.titleMedium
        )
        Text(
            text = "One domain per line. These domains go direct, everything else stays on VLESS. Saved: $domainCount",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 6.dp)
        )
        OutlinedTextField(
            value = draft,
            onValueChange = onValueChange,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp)
                .heightIn(min = 220.dp, max = 320.dp),
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            minLines = 14,
            maxLines = 24,
            placeholder = {
                Text(
                    text = "github.com\nraw.githubusercontent.com\ngoogle.com",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        )
        Text(
            text = "Telegram direct IPv4 CIDRs",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(top = 14.dp)
        )
        Text(
            text = "Optional. Telegram says DC IP/port can change frequently, so keep this list current. Saved: $telegramIpCidrsCount",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp)
        )
        OutlinedTextField(
            value = telegramIpCidrsDraft,
            onValueChange = onTelegramIpCidrsChange,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp)
                .heightIn(min = 140.dp, max = 220.dp),
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            minLines = 6,
            maxLines = 12,
            placeholder = {
                Text(
                    text = "149.154.160.0/20\n91.108.56.0/22",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp),
            horizontalArrangement = Arrangement.End
        ) {
            TextButton(onClick = onBack) {
                Text("Back")
            }
            Button(
                onClick = onSave,
                modifier = Modifier.padding(start = 8.dp)
            ) {
                Text("Save")
            }
        }
    }
}
