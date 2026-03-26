package com.close.hook.ads.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.close.hook.ads.data.model.CloudRuleSource
import com.close.hook.ads.data.model.CloudRuleSourceSummary
import kotlinx.coroutines.flow.Flow

@Dao
interface CloudRuleSourceDao {

    @Insert
    fun insert(source: CloudRuleSource): Long

    @Update
    fun update(source: CloudRuleSource): Int

    @Query("DELETE FROM cloud_rule_source WHERE id = :sourceId")
    fun deleteById(sourceId: Long): Int

    @Query("SELECT * FROM cloud_rule_source WHERE id = :sourceId LIMIT 1")
    fun findById(sourceId: Long): CloudRuleSource?

    @Query("SELECT * FROM cloud_rule_source WHERE url = :url LIMIT 1")
    fun findByUrl(url: String): CloudRuleSource?

    @Query("SELECT COUNT(*) FROM cloud_rule_source")
    fun count(): Int

    @Query(
        """
        SELECT
            s.id AS id,
            s.url AS url,
            s.enabled AS enabled,
            s.auto_update_enabled AS autoUpdateEnabled,
            s.update_interval_hours AS updateIntervalHours,
            s.last_check_at AS lastCheckAt,
            s.last_success_at AS lastSuccessAt,
            s.last_error_message AS lastErrorMessage,
            COUNT(e.id) AS totalCount,
            COALESCE(SUM(CASE WHEN LOWER(e.type) = 'domain' THEN 1 ELSE 0 END), 0) AS domainCount,
            COALESCE(SUM(CASE WHEN LOWER(e.type) = 'url' THEN 1 ELSE 0 END), 0) AS urlCount,
            COALESCE(SUM(CASE WHEN LOWER(e.type) = 'keyword' THEN 1 ELSE 0 END), 0) AS keywordCount
        FROM cloud_rule_source s
        LEFT JOIN cloud_rule_entry e ON e.source_id = s.id
        WHERE :searchText = '' OR LOWER(s.url) LIKE '%' || LOWER(:searchText) || '%'
        GROUP BY s.id
        ORDER BY s.id DESC
        """
    )
    fun observeSummaries(searchText: String): Flow<List<CloudRuleSourceSummary>>

    @Query(
        """
        SELECT * FROM cloud_rule_source
        WHERE enabled = 1
          AND auto_update_enabled = 1
          AND (
            last_check_at IS NULL
            OR last_check_at + (update_interval_hours * 3600000) <= :nowMillis
          )
        ORDER BY id ASC
        """
    )
    fun findDueAutoUpdateSources(nowMillis: Long): List<CloudRuleSource>
}
