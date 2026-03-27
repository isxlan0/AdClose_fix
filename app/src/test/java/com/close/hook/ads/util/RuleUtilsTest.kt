package com.close.hook.ads.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleUtilsTest {

    @Test
    fun `normalizeRule canonicalizes imported lowercase domain rules`() {
        val rule = RuleUtils.normalizeRule("domain", "Example.COM")

        assertNotNull(rule)
        assertEquals(RuleUtils.TYPE_DOMAIN, rule?.type)
        assertEquals("example.com", rule?.url)
    }

    @Test
    fun `normalizeValue keeps wildcard domains in canonical form`() {
        val normalized = RuleUtils.normalizeValue(RuleUtils.TYPE_DOMAIN, "*.Example.COM.")

        assertEquals("*.example.com", normalized)
    }

    @Test
    fun `normalizeValue rejects unsupported wildcard syntax`() {
        assertNull(RuleUtils.normalizeValue(RuleUtils.TYPE_DOMAIN, "*example.com"))
        assertNull(RuleUtils.normalizeValue(RuleUtils.TYPE_DOMAIN, ".example.com"))
        assertNull(RuleUtils.normalizeValue(RuleUtils.TYPE_DOMAIN, "*."))
        assertNull(RuleUtils.normalizeValue(RuleUtils.TYPE_DOMAIN, "*..example.com"))
    }

    @Test
    fun `normalizeValue extracts host from domain urls`() {
        val normalized = RuleUtils.normalizeValue(
            RuleUtils.TYPE_DOMAIN,
            "https://Sub.Example.com/path?query=1"
        )

        assertEquals("sub.example.com", normalized)
    }

    @Test
    fun `buildDomainMatchCandidates returns host first then wildcard suffixes`() {
        val candidates = RuleUtils.buildDomainMatchCandidates("https://a.b.Example.com/path")

        assertEquals(
            listOf(
                "a.b.example.com",
                "*.b.example.com",
                "*.example.com",
                "*.com"
            ),
            candidates
        )
    }

    @Test
    fun `buildDomainMatchCandidates handles bare host and adds suffix wildcards`() {
        val candidates = RuleUtils.buildDomainMatchCandidates("Sub.Example.com")

        assertEquals(listOf("sub.example.com", "*.example.com", "*.com"), candidates)
    }

    @Test
    fun `buildDomainMatchCandidates ignores invalid inputs`() {
        assertTrue(RuleUtils.buildDomainMatchCandidates("   ").isEmpty())
        assertTrue(RuleUtils.buildDomainMatchCandidates(null).isEmpty())
    }

    @Test
    fun `buildDomainMatchCandidates does not duplicate exact host among wildcards`() {
        val candidates = RuleUtils.buildDomainMatchCandidates("A.B.Example.com")

        assertEquals("a.b.example.com", candidates.first())
        assertFalse(candidates.drop(1).contains("a.b.example.com"))
    }

    @Test
    fun `buildDomainMatchCandidates preserves wildcard literal input`() {
        val candidates = RuleUtils.buildDomainMatchCandidates("*.Example.com")

        assertEquals(listOf("*.example.com"), candidates)
    }
}
