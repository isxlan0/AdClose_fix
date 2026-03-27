package com.close.hook.ads.util

import java.util.Locale

object LocaleUtils {

    private val androidRegionQualifierPattern = Regex("^([a-zA-Z]{2,3})-r([a-zA-Z]{2}|\\d{3})$")

    fun normalizeLanguageTag(tag: String): String {
        val trimmed = tag.trim()
        if (trimmed == "SYSTEM") {
            return trimmed
        }

        val match = androidRegionQualifierPattern.matchEntire(trimmed) ?: return trimmed
        val language = match.groupValues[1].lowercase(Locale.ROOT)
        val region = match.groupValues[2].uppercase(Locale.ROOT)
        return "$language-$region"
    }
}
