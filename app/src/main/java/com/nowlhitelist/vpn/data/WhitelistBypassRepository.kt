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
        val manual = getManualTelegramIpCidrs()
        if (manual.isNotEmpty()) return manual

        val cached = getCachedTelegramIpCidrs()
        return if (cached.isNotEmpty()) cached else getBundledTelegramIpCidrs()
    }

    fun getTelegramIpCidrsCount(): Int = getTelegramIpCidrs().size

    fun getTelegramIpCidrsText(): String = getTelegramIpCidrs().joinToString("\n")

    fun saveTelegramIpCidrsText(raw: String): Int {
        val parsed = parseCidrs(raw)
        if (parsed.isEmpty()) {
            preferences.edit().remove(KEY_TELEGRAM_IP_CIDRS_OVERRIDE).apply()
            return 0
        }
        preferences.edit().putString(KEY_TELEGRAM_IP_CIDRS_OVERRIDE, parsed.joinToString("\n")).apply()
        return parsed.size
    }

    suspend fun refreshTelegramIpCidrs(forceRefresh: Boolean = false): List<String> = withContext(Dispatchers.IO) {
        val manual = getManualTelegramIpCidrs()
        if (manual.isNotEmpty()) {
            return@withContext manual
        }

        val cached = getCachedTelegramIpCidrs()
        val bundled = getBundledTelegramIpCidrs()
        val fallback = if (cached.isNotEmpty()) cached else bundled
        val lastRefreshAtMs = preferences.getLong(KEY_TELEGRAM_IP_CIDRS_LAST_REFRESH_AT_MS, 0L)
        val refreshDue = forceRefresh ||
            fallback.isEmpty() ||
            System.currentTimeMillis() - lastRefreshAtMs >= TELEGRAM_CIDRS_REFRESH_INTERVAL_MS

        if (!refreshDue) {
            return@withContext fallback
        }

        runCatching {
            val parsed = parseCidrs(fetchText(DEFAULT_TELEGRAM_CIDRS_URL))
            if (parsed.isNotEmpty()) {
                preferences.edit()
                    .putString(KEY_TELEGRAM_IP_CIDRS_CACHE, parsed.joinToString("\n"))
                    .putLong(KEY_TELEGRAM_IP_CIDRS_LAST_REFRESH_AT_MS, System.currentTimeMillis())
                    .apply()
                parsed
            } else {
                fallback
            }
        }.getOrElse {
            fallback
        }
    }

    suspend fun getDomains(forceRefresh: Boolean = false): List<String> = withContext(Dispatchers.IO) {
        val cached = getCachedDomains()
        val bundled = getBundledDomains()
        val fallback = if (cached.isNotEmpty()) cached else bundled

        if (!forceRefresh && fallback.isNotEmpty()) {
            return@withContext fallback
        }

        runCatching {
            val parsed = parseDomains(fetchText(DEFAULT_WHITELIST_URL))
            if (parsed.isNotEmpty()) {
                preferences.edit().putString(KEY_DOMAINS, parsed.joinToString("\n")).apply()
                parsed
            } else {
                fallback
            }
        }.getOrElse {
            fallback
        }
    }

    private fun getManualTelegramIpCidrs(): List<String> {
        return parseCidrs(preferences.getString(KEY_TELEGRAM_IP_CIDRS_OVERRIDE, null).orEmpty())
    }

    private fun getCachedTelegramIpCidrs(): List<String> {
        return parseCidrs(preferences.getString(KEY_TELEGRAM_IP_CIDRS_CACHE, null).orEmpty())
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

    private fun fetchText(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            requestMethod = "GET"
            instanceFollowRedirects = true
        }

        return try {
            connection.inputStream.bufferedReader().use { reader -> reader.readText() }
        } finally {
            connection.disconnect()
        }
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
        private const val KEY_TELEGRAM_IP_CIDRS_OVERRIDE = "telegram_bypass_ip_cidrs_override"
        private const val KEY_TELEGRAM_IP_CIDRS_CACHE = "telegram_bypass_ip_cidrs_cache"
        private const val KEY_TELEGRAM_IP_CIDRS_LAST_REFRESH_AT_MS = "telegram_bypass_ip_cidrs_last_refresh_at_ms"
        private const val CONNECT_TIMEOUT_MS = 8000
        private const val READ_TIMEOUT_MS = 8000
        private const val TELEGRAM_CIDRS_REFRESH_INTERVAL_MS = 6 * 60 * 60 * 1000L
        private const val BUNDLED_ASSET_NAME = "whitelist_bypass_domains.txt"
        private const val TELEGRAM_CIDRS_ASSET_NAME = "telegram_bypass_cidrs.txt"
        private val DOMAIN_REGEX = Regex("^([A-Za-z0-9.-]+\\.)+[A-Za-z]{2,}$")
        private val CIDR_REGEX = Regex("^((25[0-5]|2[0-4]\\d|1?\\d?\\d)\\.){3}(25[0-5]|2[0-4]\\d|1?\\d?\\d)/(3[0-2]|[12]?\\d)$")

        const val DEFAULT_WHITELIST_URL =
            "https://raw.githubusercontent.com/kulikov0/whitelist-bypass/main/whitelist.txt"
        const val DEFAULT_TELEGRAM_CIDRS_URL =
            "https://raw.githubusercontent.com/accforsh9t/nowhitelist-vless/main/app/src/main/assets/telegram_bypass_cidrs.txt"
    }
}
