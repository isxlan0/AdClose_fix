package com.close.hook.ads.rule

import com.close.hook.ads.util.RuleUtils

class SnapshotRuleMatcher(
    payload: RuleSnapshotPayload
) : RuleMatcher {

    private val manualBucket = PreparedBucket(payload.manual)
    private val cloudBucket = PreparedBucket(payload.cloud)

    override fun matchUrl(fullUrl: String): RuleMatchResult? {
        val normalizedUrl = RuleUtils.normalizeValue(RuleUtils.TYPE_URL, fullUrl) ?: return null
        return manualBucket.matchUrl(normalizedUrl)
            ?: cloudBucket.matchUrl(normalizedUrl)
    }

    override fun matchDomain(host: String): RuleMatchResult? {
        val candidates = RuleUtils.buildDomainMatchCandidates(host)
        if (candidates.isEmpty()) return null
        return manualBucket.matchDomain(candidates)
            ?: cloudBucket.matchDomain(candidates)
    }

    override fun matchKeyword(value: String): RuleMatchResult? {
        val normalizedValue = RuleUtils.normalizeValue(RuleUtils.TYPE_KEYWORD, value) ?: return null
        return manualBucket.matchKeyword(normalizedValue)
            ?: cloudBucket.matchKeyword(normalizedValue)
    }

    private class PreparedBucket(bucket: RuleSnapshotBucket) {
        private val domainExact = bucket.domainExactRules.toHashSet()
        private val domainWildcard = bucket.domainWildcardRules.toHashSet()
        private val urlPrefixes = bucket.urlPrefixRules.toHashSet()
        private val keywordAutomaton = bucket.keywordRules
            .takeIf { it.isNotEmpty() }
            ?.let(::KeywordAutomaton)

        fun matchUrl(fullUrl: String): RuleMatchResult? {
            val candidates = RuleUtils.buildUrlPrefixCandidates(fullUrl)
            return candidates.firstOrNull(urlPrefixes::contains)?.let {
                RuleMatchResult(RuleUtils.TYPE_URL, it)
            }
        }

        fun matchDomain(candidates: List<String>): RuleMatchResult? {
            return candidates.firstNotNullOfOrNull { candidate ->
                when {
                    candidate.startsWith("*.") && domainWildcard.contains(candidate) ->
                        RuleMatchResult(RuleUtils.TYPE_DOMAIN, candidate)
                    !candidate.startsWith("*.") && domainExact.contains(candidate) ->
                        RuleMatchResult(RuleUtils.TYPE_DOMAIN, candidate)
                    else -> null
                }
            }
        }

        fun matchKeyword(value: String): RuleMatchResult? {
            return keywordAutomaton?.findLongestMatch(value)?.let {
                RuleMatchResult(RuleUtils.TYPE_KEYWORD, it)
            }
        }
    }
}
