package com.nowlhitelist.vpn.data

import android.net.Uri

data class ParsedVless(
    val serverAddress: String,
    val serverPort: Int,
    val uuid: String,
    val publicKey: String,
    val shortId: String,
    val sni: String,
    val flow: String,
    val fp: String,
    val name: String
)

object VlessUriParser {
    fun parse(input: String): ParsedVless? {
        val source = input.trim()
        if (!source.startsWith("vless://", ignoreCase = true)) return null

        val uri = Uri.parse(source)
        val host = uri.host ?: return null
        val port = uri.port.takeIf { it in 1..65535 } ?: 443
        val uuid = uri.userInfo?.substringBefore("@")?.trim().orEmpty()
        val pbk = uri.getQueryParameter("pbk")
            ?.trim()
            ?.ifBlank { null }
            ?: uri.getQueryParameter("public-key")?.trim()?.ifBlank { null }
            ?: uri.getQueryParameter("publicKey")?.trim()?.ifBlank { null }
            ?: uri.getQueryParameter("pk")?.trim()?.ifBlank { null }
            ?: return null

        val sid = uri.getQueryParameter("sid")
            ?.trim()
            ?.ifBlank { null }
            ?: uri.getQueryParameter("short-id")?.trim()?.ifBlank { null }
            ?: "0"

        val sni = uri.getQueryParameter("sni")?.trim()
            ?.ifBlank { null }
            ?: host

        val flow = uri.getQueryParameter("flow")?.trim()?.ifBlank { "xtls-rprx-vision" } ?: "xtls-rprx-vision"
        val fp = uri.getQueryParameter("fp")?.trim()?.ifBlank { "chrome" } ?: "chrome"

        val name = uri.fragment?.trim()
            ?.ifBlank { "Tunnel-${host}" }
            ?: "Tunnel-${host}"

        if (uuid.isBlank()) {
            return null
        }

        return ParsedVless(
            serverAddress = host,
            serverPort = port,
            uuid = uuid,
            publicKey = pbk,
            shortId = sid,
            sni = sni,
            flow = flow,
            fp = fp,
            name = name
        )
    }
}
