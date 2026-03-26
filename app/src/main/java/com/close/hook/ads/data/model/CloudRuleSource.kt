package com.close.hook.ads.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "cloud_rule_source",
    indices = [
        Index(value = ["url"], unique = true),
        Index(value = ["enabled"]),
        Index(value = ["auto_update_enabled"])
    ]
)
data class CloudRuleSource(
    @PrimaryKey(autoGenerate = true)
    var id: Long = 0L,

    @ColumnInfo(name = "url")
    val url: String,

    @ColumnInfo(name = "parse_type")
    val parseType: String = "Domain",

    @ColumnInfo(name = "enabled")
    val enabled: Boolean = false,

    @ColumnInfo(name = "auto_update_enabled")
    val autoUpdateEnabled: Boolean = false,

    @ColumnInfo(name = "update_interval_hours")
    val updateIntervalHours: Long = 24L,

    @ColumnInfo(name = "last_check_at")
    val lastCheckAt: Long? = null,

    @ColumnInfo(name = "last_success_at")
    val lastSuccessAt: Long? = null,

    @ColumnInfo(name = "last_error_message")
    val lastErrorMessage: String? = null
)
