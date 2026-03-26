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
        enabled: Boolean,
        autoUpdateEnabled: Boolean,
        updateIntervalHours: Long
    ): CloudRuleOperationResult {
        val normalizedUrl = normalizeSourceUrl(rawUrl)
            ?: return CloudRuleOperationResult(false, "invalid_url")
        val normalizedInterval = normalizeIntervalHours(updateIntervalHours)
            ?: return CloudRuleOperationResult(false, "invalid_interval")

        return database.withTransaction {
            if (sourceDao.findByUrl(normalizedUrl) != null) {
                CloudRuleOperationResult(false, "duplicate_url")
            } else {
                sourceDao.insert(
                    CloudRuleSource(
                        url = normalizedUrl,
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
        enabled: Boolean,
        autoUpdateEnabled: Boolean,
        updateIntervalHours: Long
    ): CloudRuleOperationResult {
        val normalizedUrl = normalizeSourceUrl(rawUrl)
            ?: return CloudRuleOperationResult(false, "invalid_url")
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
            val parsedRules = parseRuleContent(source.id, content)

            database.withTransaction {
                entryDao.deleteBySourceId(source.id)
                if (parsedRules.isNotEmpty()) {
                    entryDao.insertAll(parsedRules)
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

    private fun parseRuleContent(sourceId: Long, content: String): List<CloudRuleEntry> {
        return content.lineSequence()
            .map(String::trim)
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .mapNotNull { line ->
                val parts = line.split(",\\s*".toRegex(), 2).map(String::trim)
                if (parts.size != 2) return@mapNotNull null
                RuleUtils.normalizeRule(parts[0], parts[1])?.let {
                    CloudRuleEntry(sourceId = sourceId, type = it.type, url = it.url)
                }
            }
            .distinctBy { RuleUtils.canonicalKey(it.type, it.url) }
            .toList()
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
