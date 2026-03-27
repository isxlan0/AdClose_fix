package com.close.hook.ads.rule

import com.close.hook.ads.util.RuleUtils
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SnapshotRuleMatcherTest {

    @Test
    fun `manual domain rule wins over cloud domain rule`() {
        val matcher = SnapshotRuleMatcher(
            RuleSnapshotPayload(
                generatedAtMillis = 1L,
                manual = RuleSnapshotBucket(domainWildcardRules = listOf("*.example.com")),
                cloud = RuleSnapshotBucket(domainExactRules = listOf("api.example.com"))
            )
        )

        val result = matcher.matchDomain("api.example.com")

        assertEquals(RuleUtils.TYPE_DOMAIN, result?.type)
        assertEquals("*.example.com", result?.ruleValue)
    }

    @Test
    fun `url prefix match prefers longest manual prefix`() {
        val matcher = SnapshotRuleMatcher(
            RuleSnapshotPayload(
                generatedAtMillis = 1L,
                manual = RuleSnapshotBucket(
                    urlPrefixRules = listOf(
                        "https://example.com",
                        "https://example.com/path"
                    )
                ),
                cloud = RuleSnapshotBucket(
                    urlPrefixRules = listOf("https://example.com/path/deeper")
                )
            )
        )

        val result = matcher.matchUrl("https://example.com/path/deeper?x=1")

        assertEquals(RuleUtils.TYPE_URL, result?.type)
        assertEquals("https://example.com/path", result?.ruleValue)
    }

    @Test
    fun `keyword match stays case sensitive and manual first`() {
        val matcher = SnapshotRuleMatcher(
            RuleSnapshotPayload(
                generatedAtMillis = 1L,
                manual = RuleSnapshotBucket(keywordRules = listOf("Promo")),
                cloud = RuleSnapshotBucket(keywordRules = listOf("promo"))
            )
        )

        val manualResult = matcher.matchKeyword("xxPromoyy")
        val cloudResult = matcher.matchKeyword("xxpromoyy")

        assertEquals("Promo", manualResult?.ruleValue)
        assertEquals("promo", cloudResult?.ruleValue)
    }

    @Test
    fun `keyword matcher prefers the longest match in the same bucket`() {
        val matcher = SnapshotRuleMatcher(
            RuleSnapshotPayload(
                generatedAtMillis = 1L,
                manual = RuleSnapshotBucket(keywordRules = listOf("ad", "adservice", "service")),
                cloud = RuleSnapshotBucket()
            )
        )

        val result = matcher.matchKeyword("xxadserviceyy")

        assertEquals("adservice", result?.ruleValue)
    }

    @Test
    fun `snapshot payload serialization round trips`() {
        val payload = RuleSnapshotPayload(
            generatedAtMillis = 123L,
            manual = RuleSnapshotBucket(
                domainExactRules = listOf("example.com"),
                domainWildcardRules = listOf("*.example.com"),
                urlPrefixRules = listOf("https://example.com"),
                keywordRules = listOf("promo")
            ),
            cloud = RuleSnapshotBucket(
                domainExactRules = listOf("cdn.example.com")
            )
        )

        val encoded = RuleSnapshotPayload.JSON.encodeToString(RuleSnapshotPayload.serializer(), payload)
        val decoded = RuleSnapshotPayload.JSON.decodeFromString(RuleSnapshotPayload.serializer(), encoded)

        assertEquals(payload, decoded)
    }

    @Test
    fun `no match returns null`() {
        val matcher = SnapshotRuleMatcher(
            RuleSnapshotPayload(
                generatedAtMillis = 1L,
                manual = RuleSnapshotBucket(),
                cloud = RuleSnapshotBucket()
            )
        )

        assertNull(matcher.matchDomain("example.com"))
        assertNull(matcher.matchUrl("https://example.com"))
        assertNull(matcher.matchKeyword("promo"))
    }
}
