package com.close.hook.ads.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
}
