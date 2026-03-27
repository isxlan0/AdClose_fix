package com.close.hook.ads.rule

import android.content.Context
import android.util.Log
import com.close.hook.ads.data.dao.CloudRuleEntryDao
import com.close.hook.ads.data.dao.UrlDao
import com.close.hook.ads.data.database.UrlDatabase
import com.close.hook.ads.data.model.CloudRuleEntry
import com.close.hook.ads.data.model.Url
import com.close.hook.ads.util.RuleUtils
import kotlinx.serialization.encodeToString
import java.nio.charset.StandardCharsets

class RuleSnapshotBuilder private constructor(
    context: Context
) {

    private val appContext = context.applicationContext
    private val database = UrlDatabase.getDatabase(appContext)
    private val urlDao: UrlDao = database.urlDao
    private val cloudRuleEntryDao: CloudRuleEntryDao = database.cloudRuleEntryDao

    fun rebuild(): Boolean {
        return runCatching {
            val payload = RuleSnapshotPayload(
                generatedAtMillis = System.currentTimeMillis(),
                manual = buildBucketFromUrls(urlDao.findAllList()),
                cloud = buildBucketFromEntries(cloudRuleEntryDao.findEnabledEntries())
            )
            writeSnapshot(payload)
            true
        }.onFailure {
            Log.w(TAG, "Failed to rebuild rule snapshot", it)
        }.getOrDefault(false)
    }

    fun invalidate() {
        RuleSnapshotStore.currentSnapshotFile(appContext).delete()
        RuleSnapshotStore.nextSnapshotFile(appContext).delete()
    }

    private fun buildBucketFromUrls(rules: List<Url>): RuleSnapshotBucket {
        val domainExactRules = LinkedHashSet<String>()
        val domainWildcardRules = LinkedHashSet<String>()
        val urlPrefixRules = LinkedHashSet<String>()
        val keywordRules = LinkedHashSet<String>()

        rules.mapNotNull { RuleUtils.normalizeRule(it) }
            .forEach { normalized ->
                when (normalized.type) {
                    RuleUtils.TYPE_DOMAIN -> {
                        if (normalized.url.startsWith("*.")) {
                            domainWildcardRules.add(normalized.url)
                        } else {
                            domainExactRules.add(normalized.url)
                        }
                    }
                    RuleUtils.TYPE_URL -> urlPrefixRules.add(normalized.url)
                    RuleUtils.TYPE_KEYWORD -> keywordRules.add(normalized.url)
                }
            }

        return RuleSnapshotBucket(
            domainExactRules = domainExactRules.sorted(),
            domainWildcardRules = domainWildcardRules.sortedByLengthThenValueDesc(),
            urlPrefixRules = urlPrefixRules.sortedByLengthThenValueDesc(),
            keywordRules = keywordRules.sortedByLengthThenValueDesc()
        )
    }

    private fun buildBucketFromEntries(entries: List<CloudRuleEntry>): RuleSnapshotBucket {
        return buildBucketFromUrls(entries.map { Url(type = it.type, url = it.url) })
    }

    private fun writeSnapshot(payload: RuleSnapshotPayload) {
        val currentFile = RuleSnapshotStore.currentSnapshotFile(appContext)
        val nextFile = RuleSnapshotStore.nextSnapshotFile(appContext)
        val serialized = RuleSnapshotPayload.JSON.encodeToString(
            RuleSnapshotPayload.serializer(),
            payload
        )

        nextFile.writeText(serialized, StandardCharsets.UTF_8)
        if (currentFile.exists() && !currentFile.delete()) {
            throw IllegalStateException("Failed to replace current rule snapshot")
        }
        if (!nextFile.renameTo(currentFile)) {
            throw IllegalStateException("Failed to promote next rule snapshot")
        }
    }

    companion object {
        private const val TAG = "RuleSnapshotBuilder"

        @Volatile
        private var instance: RuleSnapshotBuilder? = null

        fun getInstance(context: Context): RuleSnapshotBuilder {
            return instance ?: synchronized(this) {
                instance ?: RuleSnapshotBuilder(context).also { instance = it }
            }
        }
    }
}

private fun Iterable<String>.sortedByLengthThenValueDesc(): List<String> {
    return sortedWith(compareByDescending<String> { it.length }.thenBy { it })
}
