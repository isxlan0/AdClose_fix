package com.close.hook.ads.data.repository

import android.content.Context
import androidx.room.withTransaction
import com.close.hook.ads.data.dao.CloudRuleEntryDao
import com.close.hook.ads.data.dao.CloudRuleSourceDao
import com.close.hook.ads.data.database.UrlDatabase
import com.close.hook.ads.data.model.CloudRuleEntry
import com.close.hook.ads.data.model.CloudRuleSource
import com.close.hook.ads.data.model.CloudRuleSourceSummary
import com.close.hook.ads.preference.HookPrefs
import com.close.hook.ads.util.RuleUtils
import kotlinx.coroutines.flow.Flow
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.Locale

data class CloudRuleOperationResult(
    val success: Boolean,
    val message: String? = null
)

class CloudRuleRepository private constructor(context: Context) {

    private val database = UrlDatabase.getDatabase(context.applicationContext)
    private val sourceDao: CloudRuleSourceDao = database.cloudRuleSourceDao
    private val entryDao: CloudRuleEntryDao = database.cloudRuleEntryDao

    fun observeSourceSummaries(searchText: String): Flow<List<CloudRuleSourceSummary>> {
        return sourceDao.observeSummaries(searchText.trim())
    }

    suspend fun ensureDefaultSourceInitialized() {
        if (HookPrefs.getBoolean(KEY_DEFAULT_SOURCE_INITIALIZED, false)) return

        database.withTransaction {
            if (sourceDao.count() == 0 && sourceDao.findByUrl(DEFAULT_SOURCE_URL) == null) {
                sourceDao.insert(
                    CloudRuleSource(
                        url = DEFAULT_SOURCE_URL,
                        parseType = RuleUtils.TYPE_DOMAIN,
                        enabled = false,
                        autoUpdateEnabled = false,
                        updateIntervalHours = DEFAULT_UPDATE_INTERVAL_HOURS
                    )
                )
            }
        }
        HookPrefs.setBoolean(KEY_DEFAULT_SOURCE_INITIALIZED, true)
    }

    suspend fun addSource(
        rawUrl: String,
        parseType: String,
        enabled: Boolean,
        autoUpdateEnabled: Boolean,
        updateIntervalHours: Long
    ): CloudRuleOperationResult {
        val normalizedUrl = normalizeSourceUrl(rawUrl)
            ?: return CloudRuleOperationResult(false, "invalid_url")
        val normalizedParseType = RuleUtils.normalizeType(parseType)
            ?: return CloudRuleOperationResult(false, "invalid_parse_type")
        val normalizedInterval = normalizeIntervalHours(updateIntervalHours)
            ?: return CloudRuleOperationResult(false, "invalid_interval")

        return database.withTransaction {
            if (sourceDao.findByUrl(normalizedUrl) != null) {
                CloudRuleOperationResult(false, "duplicate_url")
            } else {
                sourceDao.insert(
                    CloudRuleSource(
                        url = normalizedUrl,
                        parseType = normalizedParseType,
                        enabled = enabled,
                        autoUpdateEnabled = autoUpdateEnabled,
                        updateIntervalHours = normalizedInterval
                    )
                )
                CloudRuleOperationResult(true)
            }
        }
    }

    suspend fun updateSource(
        sourceId: Long,
        rawUrl: String,
        parseType: String,
        enabled: Boolean,
        autoUpdateEnabled: Boolean,
        updateIntervalHours: Long
    ): CloudRuleOperationResult {
        val normalizedUrl = normalizeSourceUrl(rawUrl)
            ?: return CloudRuleOperationResult(false, "invalid_url")
        val normalizedParseType = RuleUtils.normalizeType(parseType)
            ?: return CloudRuleOperationResult(false, "invalid_parse_type")
        val normalizedInterval = normalizeIntervalHours(updateIntervalHours)
            ?: return CloudRuleOperationResult(false, "invalid_interval")

        return database.withTransaction {
            val current = sourceDao.findById(sourceId)
                ?: return@withTransaction CloudRuleOperationResult(false, "not_found")

            val duplicate = sourceDao.findByUrl(normalizedUrl)
            if (duplicate != null && duplicate.id != sourceId) {
                return@withTransaction CloudRuleOperationResult(false, "duplicate_url")
            }

            sourceDao.update(
                current.copy(
                    url = normalizedUrl,
                    parseType = normalizedParseType,
                    enabled = enabled,
                    autoUpdateEnabled = autoUpdateEnabled,
                    updateIntervalHours = normalizedInterval,
                    lastErrorMessage = null
                )
            )
            CloudRuleOperationResult(true)
        }
    }

    suspend fun deleteSource(sourceId: Long): CloudRuleOperationResult {
        val deleted = sourceDao.deleteById(sourceId)
        return CloudRuleOperationResult(deleted > 0)
    }

    suspend fun setSourceEnabled(sourceId: Long, enabled: Boolean): CloudRuleOperationResult {
        return database.withTransaction {
            val source = sourceDao.findById(sourceId)
                ?: return@withTransaction CloudRuleOperationResult(false, "not_found")
            sourceDao.update(source.copy(enabled = enabled))
            CloudRuleOperationResult(true)
        }
    }

    suspend fun setAutoUpdateEnabled(sourceId: Long, enabled: Boolean): CloudRuleOperationResult {
        return database.withTransaction {
            val source = sourceDao.findById(sourceId)
                ?: return@withTransaction CloudRuleOperationResult(false, "not_found")
            sourceDao.update(source.copy(autoUpdateEnabled = enabled))
            CloudRuleOperationResult(true)
        }
    }

    suspend fun syncSourceNow(sourceId: Long): CloudRuleOperationResult {
        val source = sourceDao.findById(sourceId)
            ?: return CloudRuleOperationResult(false, "not_found")
        return syncSource(source)
    }

    suspend fun syncDueSources() {
        val now = System.currentTimeMillis()
        sourceDao.findDueAutoUpdateSources(now).forEach { source ->
            syncSource(source)
        }
    }

    fun findEnabledUrlMatch(fullUrl: String): CloudRuleEntry? = entryDao.findEnabledUrlMatch(fullUrl)

    fun findEnabledDomainMatch(host: String): CloudRuleEntry? = entryDao.findEnabledDomainMatch(host)

    fun findEnabledKeywordMatch(value: String): CloudRuleEntry? = entryDao.findEnabledKeywordMatch(value)

    private suspend fun syncSource(source: CloudRuleSource): CloudRuleOperationResult {
        val now = System.currentTimeMillis()
        return try {
            val content = downloadSourceContent(source.url)
            val parsedRules = parseRuleContent(source, content)
            if (parsedRules.entries.isEmpty()) {
                throw IllegalArgumentException("no_valid_rules")
            }

            database.withTransaction {
                entryDao.deleteBySourceId(source.id)
                if (parsedRules.entries.isNotEmpty()) {
                    entryDao.insertAll(parsedRules.entries)
                }
                sourceDao.update(
                    source.copy(
                        lastCheckAt = now,
                        lastSuccessAt = now,
                        lastErrorMessage = null
                    )
                )
            }

            CloudRuleOperationResult(true)
        } catch (e: Exception) {
            val errorMessage = e.message?.trim()?.takeIf { it.isNotEmpty() } ?: "Unknown error"
            database.withTransaction {
                sourceDao.findById(source.id)?.let { latest ->
                    sourceDao.update(
                        latest.copy(
                            lastCheckAt = now,
                            lastErrorMessage = errorMessage.take(MAX_ERROR_MESSAGE_LENGTH)
                        )
                    )
                }
            }
            CloudRuleOperationResult(false, errorMessage)
        }
    }

    private fun downloadSourceContent(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = NETWORK_TIMEOUT_MILLIS
            readTimeout = NETWORK_TIMEOUT_MILLIS
            instanceFollowRedirects = true
        }

        try {
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                throw IllegalStateException("HTTP $responseCode")
            }

            return BufferedReader(
                InputStreamReader(connection.inputStream, StandardCharsets.UTF_8)
            ).use { reader ->
                buildString {
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        appendLine(line)
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun parseRuleContent(source: CloudRuleSource, content: String): ParseRuleContentResult {
        val entries = content.lineSequence()
            .map(String::trim)
            .filterNot(::shouldSkipRuleLine)
            .mapNotNull { line ->
                parseRuleLine(source.id, source.parseType, line)
            }
            .distinctBy { RuleUtils.canonicalKey(it.type, it.url) }
            .toList()
        return ParseRuleContentResult(entries)
    }

    private fun shouldSkipRuleLine(line: String): Boolean {
        return line.isBlank() ||
            line.startsWith("#") ||
            line.startsWith("!") ||
            line.startsWith("[")
    }

    private fun parseRuleLine(sourceId: Long, parseType: String, line: String): CloudRuleEntry? {
        parseExplicitRuleLine(sourceId, line)?.let { return it }

        return when (RuleUtils.normalizeType(parseType)) {
            RuleUtils.TYPE_DOMAIN -> parseDomainRuleLine(sourceId, line)
            RuleUtils.TYPE_URL -> parseNormalizedRuleLine(sourceId, RuleUtils.TYPE_URL, line)
            RuleUtils.TYPE_KEYWORD -> parseNormalizedRuleLine(sourceId, RuleUtils.TYPE_KEYWORD, line)
            else -> null
        }
    }

    private fun parseExplicitRuleLine(sourceId: Long, line: String): CloudRuleEntry? {
        val parts = line.split(",\\s*".toRegex(), 2).map(String::trim)
        if (parts.size != 2) return null
        return RuleUtils.normalizeRule(parts[0], parts[1])?.toCloudRuleEntry(sourceId)
    }

    private fun parseDomainRuleLine(sourceId: Long, line: String): CloudRuleEntry? {
        val candidates = buildList {
            add(line)
            extractHostsDomain(line)?.let(::add)
            extractAdblockDomain(line)?.let(::add)
            extractPlainDomain(line)?.let(::add)
        }

        return candidates.firstNotNullOfOrNull { candidate ->
            RuleUtils.normalizeRule(RuleUtils.TYPE_DOMAIN, candidate)?.toCloudRuleEntry(sourceId)
        }
    }

    private fun parseNormalizedRuleLine(sourceId: Long, type: String, value: String): CloudRuleEntry? {
        return RuleUtils.normalizeRule(type, value)?.toCloudRuleEntry(sourceId)
    }

    private fun extractHostsDomain(line: String): String? {
        val parts = line.split(WHITESPACE_REGEX)
        if (parts.size < 2) return null
        if (!isHostsAddressToken(parts[0])) return null
        return parts.drop(1).firstOrNull { token ->
            token.isNotBlank() &&
                !token.startsWith("#") &&
                !token.startsWith("!")
        }
    }

    private fun extractPlainDomain(line: String): String? {
        val candidate = line.substringBefore('#').trim()
        if (candidate.isEmpty() || candidate.any(Char::isWhitespace)) return null
        return candidate
    }

    private fun extractAdblockDomain(line: String): String? {
        val normalized = line.trim()
        if (!normalized.startsWith("||") || normalized.startsWith("@@||")) return null
        val body = normalized.removePrefix("||")
        val stopIndex = body.indexOfAny(charArrayOf('^', '/', '$', '|'))
        val candidate = if (stopIndex >= 0) body.substring(0, stopIndex) else body
        return candidate.takeIf { it.isNotBlank() }
    }

    private fun isHostsAddressToken(token: String): Boolean {
        val normalized = token.trim().lowercase(Locale.ROOT)
        return normalized == "0" ||
            HOSTS_IPV4_REGEX.matches(normalized) ||
            HOSTS_IPV6_REGEX.matches(normalized)
    }

    private fun com.close.hook.ads.data.model.Url.toCloudRuleEntry(sourceId: Long): CloudRuleEntry {
        return CloudRuleEntry(sourceId = sourceId, type = type, url = url)
    }

    private fun normalizeSourceUrl(rawUrl: String): String? {
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty()) return null

        return runCatching {
            val uri = URI(trimmed)
            val scheme = uri.scheme?.lowercase(Locale.ROOT)
            if (scheme !in setOf("http", "https")) return null
            if (uri.host.isNullOrBlank()) return null
            trimmed
        }.getOrNull()
    }

    private fun normalizeIntervalHours(rawValue: Long): Long? {
        return rawValue.takeIf { it >= MIN_UPDATE_INTERVAL_HOURS }
    }

    companion object {
        private const val KEY_DEFAULT_SOURCE_INITIALIZED = "cloud_rule_default_source_initialized"
        private const val NETWORK_TIMEOUT_MILLIS = 15_000
        private const val MAX_ERROR_MESSAGE_LENGTH = 300
        private val WHITESPACE_REGEX = "\\s+".toRegex()
        private val HOSTS_IPV4_REGEX = Regex("""\d{1,3}(?:\.\d{1,3}){3}""")
        private val HOSTS_IPV6_REGEX = Regex("""[0-9a-f:.]+""")
        const val DEFAULT_SOURCE_URL =
            "https://raw.githubusercontent.com/TG-Twilight/AWAvenue-Ads-Rule/main/Filters/AWAvenue-Ads-Rule-AdClose.rule"
        const val DEFAULT_UPDATE_INTERVAL_HOURS = 24L
        private const val MIN_UPDATE_INTERVAL_HOURS = 1L

        @Volatile
        private var instance: CloudRuleRepository? = null

        fun getInstance(context: Context): CloudRuleRepository {
            return instance ?: synchronized(this) {
                instance ?: CloudRuleRepository(context.applicationContext).also { instance = it }
            }
        }
    }
}

private data class ParseRuleContentResult(
    val entries: List<CloudRuleEntry>
)
