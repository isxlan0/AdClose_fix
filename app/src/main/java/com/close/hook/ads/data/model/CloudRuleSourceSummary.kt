package com.close.hook.ads.data.model

data class CloudRuleSourceSummary(
    val id: Long,
    val url: String,
    val parseType: String,
    val enabled: Boolean,
    val autoUpdateEnabled: Boolean,
    val updateIntervalHours: Long,
    val lastCheckAt: Long?,
    val lastSuccessAt: Long?,
    val lastErrorMessage: String?,
    val totalCount: Int,
    val domainCount: Int,
    val urlCount: Int,
    val keywordCount: Int
)
