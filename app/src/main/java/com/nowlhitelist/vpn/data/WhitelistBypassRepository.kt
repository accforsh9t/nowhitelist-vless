package com.nowlhitelist.vpn.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

class WhitelistBypassRepository(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getCachedDomains(): List<String> {
        val raw = preferences.getString(KEY_DOMAINS, null).orEmpty()
        return parseDomains(raw)
    }

    fun getAvailableDomainsCount(): Int {
        val cached = getCachedDomains()
        return if (cached.isNotEmpty()) cached.size else getBundledDomains().size
    }

    fun getEditableText(): String {
        val cached = getCachedDomains()
        val source = if (cached.isNotEmpty()) cached else getBundledDomains()
        return source.joinToString("\n")
    }

    fun saveDomainsText(raw: String): Int {
        val parsed = parseDomains(raw)
        if (parsed.isEmpty()) return 0
        preferences.edit().putString(KEY_DOMAINS, parsed.joinToString("\n")).apply()
        return parsed.size
    }

    fun getTelegramIpCidrs(): List<String> {
        val cached = parseCidrs(preferences.getString(KEY_TELEGRAM_IP_CIDRS, null).orEmpty())
        return if (cached.isNotEmpty()) cached else getBundledTelegramIpCidrs()
    }

    fun getTelegramIpCidrsCount(): Int = getTelegramIpCidrs().size

    fun getTelegramIpCidrsText(): String = getTelegramIpCidrs().joinToString("\n")

    fun saveTelegramIpCidrsText(raw: String): Int {
        val parsed = parseCidrs(raw)
        preferences.edit().putString(KEY_TELEGRAM_IP_CIDRS, parsed.joinToString("\n")).apply()
        return parsed.size
    }

    suspend fun getDomains(forceRefresh: Boolean = false): List<String> = withContext(Dispatchers.IO) {
        val cached = getCachedDomains()
        val bundled = getBundledDomains()
        val fallback = if (cached.isNotEmpty()) cached else bundled

        if (!forceRefresh && fallback.isNotEmpty()) {
            return@withContext fallback
        }

        runCatching {
            val connection = (URL(DEFAULT_WHITELIST_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                requestMethod = "GET"
                instanceFollowRedirects = true
            }

            connection.inputStream.bufferedReader().use { reader ->
                val parsed = parseDomains(reader.readText())
                if (parsed.isNotEmpty()) {
                    preferences.edit().putString(KEY_DOMAINS, parsed.joinToString("\n")).apply()
                    parsed
                } else {
                    fallback
                }
            }
        }.getOrElse {
            fallback
        }
    }

    private fun getBundledDomains(): List<String> {
        return runCatching {
            appContext.assets.open(BUNDLED_ASSET_NAME).bufferedReader().use { reader ->
                parseDomains(reader.readText())
            }
        }.getOrDefault(emptyList())
    }

    private fun getBundledTelegramIpCidrs(): List<String> {
        return runCatching {
            appContext.assets.open(TELEGRAM_CIDRS_ASSET_NAME).bufferedReader().use { reader ->
                parseCidrs(reader.readText())
            }
        }.getOrDefault(emptyList())
    }

    private fun parseDomains(raw: String): List<String> {
        return raw.lineSequence()
            .map { it.substringBefore('#').trim() }
            .filter { it.isNotEmpty() }
            .map { it.substringBefore(':').trim() }
            .filter { it.matches(DOMAIN_REGEX) && !it.startsWith('.') }
            .distinct()
            .toList()
    }

    private fun parseCidrs(raw: String): List<String> {
        return raw.lineSequence()
            .map { it.substringBefore('#').trim() }
            .filter { it.isNotEmpty() }
            .filter { it.matches(CIDR_REGEX) }
            .distinct()
            .toList()
    }

    private companion object {
        private const val PREFS_NAME = "nowhitelist_vpn"
        private const val KEY_DOMAINS = "whitelist_bypass_domains"
        private const val KEY_TELEGRAM_IP_CIDRS = "telegram_bypass_ip_cidrs"
        private const val CONNECT_TIMEOUT_MS = 8000
        private const val READ_TIMEOUT_MS = 8000
        private const val BUNDLED_ASSET_NAME = "whitelist_bypass_domains.txt"
        private const val TELEGRAM_CIDRS_ASSET_NAME = "telegram_bypass_cidrs.txt"
        private val DOMAIN_REGEX = Regex("^([A-Za-z0-9.-]+\\.)+[A-Za-z]{2,}$")
        private val CIDR_REGEX = Regex("^((25[0-5]|2[0-4]\\d|1?\\d?\\d)\\.){3}(25[0-5]|2[0-4]\\d|1?\\d?\\d)/(3[0-2]|[12]?\\d)$")

        const val DEFAULT_WHITELIST_URL =
            "https://raw.githubusercontent.com/kulikov0/whitelist-bypass/main/whitelist.txt"
    }
}
