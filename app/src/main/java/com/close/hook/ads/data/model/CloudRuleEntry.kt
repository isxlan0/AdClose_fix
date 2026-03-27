package com.close.hook.ads.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "cloud_rule_entry",
    foreignKeys = [
        ForeignKey(
            entity = CloudRuleSource::class,
            parentColumns = ["id"],
            childColumns = ["source_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["source_id"]),
        Index(value = ["type"]),
        Index(value = ["url"]),
        Index(value = ["type", "url", "source_id"]),
        Index(value = ["source_id", "type", "url"], unique = true)
    ]
)
data class CloudRuleEntry(
    @PrimaryKey(autoGenerate = true)
    var id: Long = 0L,

    @ColumnInfo(name = "source_id")
    val sourceId: Long,

    @ColumnInfo(name = "type")
    val type: String,

    @ColumnInfo(name = "url")
    val url: String
)
