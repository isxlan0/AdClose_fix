package com.close.hook.ads.util

import com.close.hook.ads.data.model.Url
import java.net.URI
import java.util.Locale

object RuleUtils {

    const val TYPE_DOMAIN = "Domain"
    const val TYPE_URL = "URL"
    const val TYPE_KEYWORD = "KeyWord"

    private val invalidDomainChars = setOf('/', '\\', '?', '#', ',', ':')

    fun normalizeType(type: String?): String? = when (type?.trim()?.lowercase(Locale.ROOT)) {
        "domain" -> TYPE_DOMAIN
        "url" -> TYPE_URL
        "keyword" -> TYPE_KEYWORD
        else -> null
    }

    fun normalizeRule(type: String?, value: String?): Url? {
        val normalizedType = normalizeType(type) ?: return null
        val normalizedValue = normalizeValue(normalizedType, value) ?: return null
        return Url(type = normalizedType, url = normalizedValue)
    }

    fun normalizeRule(rule: Url): Url? =
        normalizeRule(rule.type, rule.url)?.also { it.id = rule.id }

    fun normalizeValue(type: String?, value: String?): String? {
        val normalizedType = normalizeType(type) ?: return null
        val trimmedValue = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return when (normalizedType) {
            TYPE_DOMAIN -> normalizeDomainValue(trimmedValue)
            TYPE_URL, TYPE_KEYWORD -> trimmedValue
            else -> null
        }
    }

    fun formatRuleLine(type: String?, value: String?): String? {
        val normalizedType = normalizeType(type) ?: type?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val normalizedValue = when (normalizedType) {
            TYPE_DOMAIN -> normalizeValue(TYPE_DOMAIN, value) ?: value?.trim()?.takeIf { it.isNotEmpty() }
            TYPE_URL, TYPE_KEYWORD -> normalizeValue(normalizedType, value)
            else -> value?.trim()?.takeIf { it.isNotEmpty() }
        } ?: return null
        return "$normalizedType, $normalizedValue"
    }

    fun formatRuleLine(rule: Url): String? = formatRuleLine(rule.type, rule.url)

    fun canonicalKey(type: String?, value: String?): String? {
        val normalizedType = normalizeType(type) ?: type?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() } ?: return null
        val normalizedValue = when (normalizedType) {
            TYPE_DOMAIN -> normalizeValue(TYPE_DOMAIN, value)
                ?: value?.trim()?.trimEnd('.')
                    ?.lowercase(Locale.ROOT)
                    ?.takeIf { it.isNotEmpty() }
            TYPE_URL, TYPE_KEYWORD -> value?.trim()?.takeIf { it.isNotEmpty() }
            else -> value?.trim()?.takeIf { it.isNotEmpty() }
        } ?: return null
        return "${normalizedType.lowercase(Locale.ROOT)}|$normalizedValue"
    }

    fun displayType(type: String): String = normalizeType(type) ?: type.replaceFirstChar {
        if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString()
    }

    private fun normalizeDomainValue(rawValue: String): String? {
        val trimmedValue = rawValue.trim()
        if (trimmedValue.isEmpty()) return null

        return if (trimmedValue.startsWith("*.")) {
            normalizeWildcardDomainValue(trimmedValue)
        } else {
            normalizeExactDomainValue(trimmedValue)
        }
    }

    private fun normalizeWildcardDomainValue(rawValue: String): String? {
        val suffix = rawValue.removePrefix("*.")
            .trim()
            .trimEnd('.')
            .lowercase(Locale.ROOT)
            .takeIf { it.isNotEmpty() }
            ?: return null

        if (suffix.startsWith(".") || suffix.contains('*') || suffix.any { it.isWhitespace() || it in invalidDomainChars }) {
            return null
        }

        return "*.$suffix"
    }

    private fun normalizeExactDomainValue(rawValue: String): String? {
        if (rawValue.contains('*')) return null

        val normalizedValue = if (rawValue.contains("://")) {
            extractHostFromUrl(rawValue)
        } else {
            rawValue.trim().trimEnd('.').lowercase(Locale.ROOT)
        }?.takeIf { it.isNotEmpty() } ?: return null

        if (normalizedValue.startsWith(".") || normalizedValue.any { it.isWhitespace() || it in invalidDomainChars || it == '*' }) {
            return null
        }

        return normalizedValue
    }

    private fun extractHostFromUrl(rawValue: String): String? =
        runCatching {
            URI(rawValue).host
                ?.trim()
                ?.trimEnd('.')
                ?.lowercase(Locale.ROOT)
                ?.takeIf { it.isNotEmpty() }
        }.getOrNull()
}
