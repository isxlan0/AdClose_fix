package com.close.hook.ads.util

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class LocaleUtilsTest {

    @Test
    fun `normalizeLanguageTag converts android region qualifiers to bcp47`() {
        assertEquals("zh-CN", LocaleUtils.normalizeLanguageTag("zh-rCN"))
        assertEquals("zh-HK", LocaleUtils.normalizeLanguageTag("zh-rHK"))
        assertEquals("zh-TW", LocaleUtils.normalizeLanguageTag("zh-rTW"))
    }

    @Test
    fun `normalizeLanguageTag keeps plain locale tags unchanged`() {
        assertEquals("tr", LocaleUtils.normalizeLanguageTag("tr"))
        assertEquals("ja", LocaleUtils.normalizeLanguageTag("ja"))
        assertEquals("ko", LocaleUtils.normalizeLanguageTag("ko"))
        assertEquals("ru", LocaleUtils.normalizeLanguageTag("ru"))
        assertEquals("zh-CN", LocaleUtils.normalizeLanguageTag("zh-CN"))
    }

    @Test
    fun `normalizeLanguageTag preserves system sentinel`() {
        assertEquals("SYSTEM", LocaleUtils.normalizeLanguageTag("SYSTEM"))
    }

    @Test
    fun `normalized chinese qualifier resolves expected locale region`() {
        val locale = Locale.forLanguageTag(LocaleUtils.normalizeLanguageTag("zh-rCN"))

        assertEquals("zh", locale.language)
        assertEquals("CN", locale.country)
    }
}
