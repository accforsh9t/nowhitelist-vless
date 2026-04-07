package com.nowlhitelist.vpn.data

import android.net.Uri
import org.json.JSONObject
import java.util.UUID

data class TunnelConfig(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val serverAddress: String,
    val serverPort: Int = 443,
    val uuid: String,
    val publicKey: String,
    val shortId: String,
    val sni: String,
    val flow: String = "xtls-rprx-vision",
    val fp: String = "chrome",
    val enabled: Boolean = false,
    val whitelistBypassEnabled: Boolean = false
) {
    val isValid: Boolean
        get() = name.isNotBlank() &&
            serverAddress.isNotBlank() &&
            serverPort in 1..65535 &&
            uuid.isNotBlank() &&
            publicKey.isNotBlank() &&
            shortId.isNotBlank() &&
            sni.isNotBlank() &&
            flow.isNotBlank() &&
            fp.isNotBlank()

    fun sanitized(): TunnelConfig = copy(
        shortId = shortId.ifBlank { "0" },
        sni = sni.ifBlank { serverAddress },
        flow = flow.ifBlank { "xtls-rprx-vision" },
        fp = fp.ifBlank { "chrome" }
    )

    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("id", id)
            put("name", name)
            put("serverAddress", serverAddress)
            put("serverPort", serverPort)
            put("uuid", uuid)
            put("publicKey", publicKey)
            put("shortId", shortId)
            put("sni", sni)
            put("flow", flow)
            put("fp", fp)
            put("enabled", enabled)
            put("whitelistBypassEnabled", whitelistBypassEnabled)
        }
    }

    companion object {
        fun fromJson(obj: JSONObject): TunnelConfig {
            return TunnelConfig(
                id = obj.optString("id"),
                name = obj.optString("name"),
                serverAddress = obj.optString("serverAddress"),
                serverPort = obj.optInt("serverPort", 443),
                uuid = obj.optString("uuid"),
                publicKey = obj.optString("publicKey"),
                shortId = obj.optString("shortId"),
                sni = obj.optString("sni"),
                flow = obj.optString("flow", "xtls-rprx-vision"),
                fp = obj.optString("fp", "chrome"),
                enabled = obj.optBoolean("enabled"),
                whitelistBypassEnabled = obj.optBoolean("whitelistBypassEnabled", false)
            )
        }
    }

    val vlessUri: String
        get() {
            val hostPort = "$serverAddress:$serverPort"
            val safeSni = Uri.encode(sni)
            val safeKey = Uri.encode(publicKey)
            val safeSid = Uri.encode(shortId)
            val safeFp = Uri.encode(fp)
            val safeFlow = Uri.encode(flow)
            return "vless://$uuid@$hostPort?security=reality&encryption=none&flow=$safeFlow&fp=$safeFp&pbk=$safeKey&sni=$safeSni&sid=$safeSid&type=tcp#$name"
        }
}
