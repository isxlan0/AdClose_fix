package com.close.hook.ads.rule

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class RuleSnapshotPayload(
    val version: Int = CURRENT_VERSION,
    val generatedAtMillis: Long,
    val manual: RuleSnapshotBucket,
    val cloud: RuleSnapshotBucket
) {
    companion object {
        const val CURRENT_VERSION = 1

        val JSON: Json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }
}

@Serializable
data class RuleSnapshotBucket(
    val domainExactRules: List<String> = emptyList(),
    val domainWildcardRules: List<String> = emptyList(),
    val urlPrefixRules: List<String> = emptyList(),
    val keywordRules: List<String> = emptyList()
)

data class RuleMatchResult(
    val type: String,
    val ruleValue: String
)

interface RuleMatcher {
    fun matchUrl(fullUrl: String): RuleMatchResult?
    fun matchDomain(host: String): RuleMatchResult?
    fun matchKeyword(value: String): RuleMatchResult?
}
