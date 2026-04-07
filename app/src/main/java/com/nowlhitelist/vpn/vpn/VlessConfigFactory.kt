package com.nowlhitelist.vpn.vpn

import com.nowlhitelist.vpn.data.TunnelConfig
import org.json.JSONArray
import org.json.JSONObject

object VlessConfigFactory {
    private const val SOCKS_INBOUND_PORT = 10808

    fun buildRawConfig(
        config: TunnelConfig,
        whitelistDomains: List<String> = emptyList(),
        telegramIpCidrs: List<String> = emptyList()
    ): String {
        val raw = JSONObject()

        val inbound = JSONObject().apply {
            put("listen", "127.0.0.1")
            put("port", SOCKS_INBOUND_PORT)
            put("protocol", "socks")
            put("tag", "socks-in")
            put("settings", JSONObject().put("udp", true))
            put(
                "sniffing",
                JSONObject().apply {
                    put("enabled", true)
                    put("destOverride", JSONArray().put("http").put("tls").put("quic"))
                    put("routeOnly", true)
                }
            )
        }

        val vnextUser = JSONObject().apply {
            put("id", config.uuid)
            put("encryption", "none")
            if (config.flow.isNotBlank()) {
                put("flow", config.flow)
            }
            put("level", 0)
        }

        val vnext = JSONObject().apply {
            put("address", config.serverAddress)
            put("port", config.serverPort)
            put("users", JSONArray().put(vnextUser))
        }

        val vlessOutbound = JSONObject().apply {
            put("protocol", "vless")
            put("tag", "proxy")
            put(
                "settings",
                JSONObject().apply {
                    put("vnext", JSONArray().put(vnext))
                }
            )
            put(
                "streamSettings",
                JSONObject().apply {
                    put("network", "tcp")
                    put("security", "reality")
                    val realitySettings = JSONObject()
                    if (config.sni.isNotBlank()) {
                        realitySettings.put("serverName", config.sni)
                    }
                    if (config.fp.isNotBlank()) {
                        realitySettings.put("fingerprint", config.fp)
                    }
                    if (config.publicKey.isNotBlank()) {
                        realitySettings.put("publicKey", config.publicKey)
                    }
                    if (config.shortId.isNotBlank()) {
                        realitySettings.put("shortId", config.shortId)
                    }
                    put(
                        "realitySettings",
                        realitySettings
                    )
                    if (config.fp.isNotBlank()) {
                        put("tlsSettings", JSONObject().apply { put("allowInsecure", false) })
                    }
                }
            )
        }

        val direct = JSONObject().apply {
            put("protocol", "freedom")
            put("tag", "direct")
            put("settings", JSONObject())
        }

        val routing = if (config.whitelistBypassEnabled && (whitelistDomains.isNotEmpty() || telegramIpCidrs.isNotEmpty())) {
            JSONObject().apply {
                put("domainStrategy", "AsIs")
                val rules = JSONArray()
                if (whitelistDomains.isNotEmpty()) {
                    rules.put(
                        JSONObject().apply {
                            put("type", "field")
                            put("outboundTag", "direct")
                            put("domain", JSONArray().apply {
                                whitelistDomains.forEach { put(it) }
                            })
                        }
                    )
                }
                if (telegramIpCidrs.isNotEmpty()) {
                    rules.put(
                        JSONObject().apply {
                            put("type", "field")
                            put("outboundTag", "direct")
                            put("ip", JSONArray().apply {
                                telegramIpCidrs.forEach { put(it) }
                            })
                        }
                    )
                }
                rules.put(
                    JSONObject().apply {
                        put("type", "field")
                        put("outboundTag", "proxy")
                        put("network", "tcp")
                    }
                )
                rules.put(
                    JSONObject().apply {
                        put("type", "field")
                        put("outboundTag", "proxy")
                        put("network", "udp")
                    }
                )
                put("rules", rules)
            }
        } else {
            JSONObject().apply {
                put(
                    "rules",
                    JSONArray().put(
                        JSONObject().apply {
                            put("type", "field")
                            put("outboundTag", "proxy")
                            put("ip", JSONArray().put("0.0.0.0/0").put("::/0"))
                        }
                    )
                )
            }
        }

        raw.put("log", JSONObject().put("loglevel", "warning"))
        raw.put("inbounds", JSONArray().put(inbound))
        raw.put("outbounds", JSONArray().put(vlessOutbound).put(direct))
        raw.put("routing", routing)

        return raw.toString()
    }
}
