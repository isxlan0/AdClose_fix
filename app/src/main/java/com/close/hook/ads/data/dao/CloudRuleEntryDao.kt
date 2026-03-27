package com.close.hook.ads.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.close.hook.ads.data.model.CloudRuleEntry

@Dao
interface CloudRuleEntryDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertAll(entries: List<CloudRuleEntry>)

    @Query("DELETE FROM cloud_rule_entry WHERE source_id = :sourceId")
    fun deleteBySourceId(sourceId: Long): Int

    @Query(
        """
        SELECT e.* FROM cloud_rule_entry e
        INNER JOIN cloud_rule_source s ON s.id = e.source_id
        WHERE s.enabled = 1
        """
    )
    fun findEnabledEntries(): List<CloudRuleEntry>

    @Query(
        """
        SELECT e.* FROM cloud_rule_entry e
        INNER JOIN cloud_rule_source s ON s.id = e.source_id
        WHERE s.enabled = 1
          AND LOWER(e.type) = 'url'
          AND :fullUrl LIKE e.url || '%'
        ORDER BY LENGTH(e.url) DESC
        LIMIT 1
        """
    )
    fun findEnabledUrlMatch(fullUrl: String): CloudRuleEntry?

    @Query(
        """
        SELECT e.* FROM cloud_rule_entry e
        INNER JOIN cloud_rule_source s ON s.id = e.source_id
        WHERE s.enabled = 1
          AND e.type = 'URL'
          AND e.url IN (:candidates)
        ORDER BY LENGTH(e.url) DESC
        LIMIT 1
        """
    )
    fun findEnabledUrlMatchByCandidates(candidates: List<String>): CloudRuleEntry?

    @Query(
        """
        SELECT e.* FROM cloud_rule_entry e
        INNER JOIN cloud_rule_source s ON s.id = e.source_id
        WHERE s.enabled = 1
          AND LOWER(e.type) = 'domain'
          AND (
            LOWER(e.url) = LOWER(:host)
            OR (
              SUBSTR(LOWER(e.url), 1, 2) = '*.'
              AND LOWER(:host) LIKE '%.' || SUBSTR(LOWER(e.url), 3)
            )
          )
        ORDER BY CASE WHEN LOWER(e.url) = LOWER(:host) THEN 0 ELSE 1 END, LENGTH(e.url) DESC
        LIMIT 1
        """
    )
    fun findEnabledDomainMatch(host: String): CloudRuleEntry?

    @Query(
        """
        SELECT e.* FROM cloud_rule_entry e
        INNER JOIN cloud_rule_source s ON s.id = e.source_id
        WHERE s.enabled = 1
          AND e.type = 'Domain'
          AND e.url IN (:candidates)
        ORDER BY CASE WHEN e.url = :host THEN 0 ELSE 1 END, LENGTH(e.url) DESC
        LIMIT 1
        """
    )
    fun findEnabledDomainMatchByCandidates(host: String, candidates: List<String>): CloudRuleEntry?

    @Query(
        """
        SELECT e.* FROM cloud_rule_entry e
        INNER JOIN cloud_rule_source s ON s.id = e.source_id
        WHERE s.enabled = 1
          AND LOWER(e.type) = 'keyword'
          AND INSTR(:value, e.url) > 0
        ORDER BY LENGTH(e.url) DESC
        LIMIT 1
        """
    )
    fun findEnabledKeywordMatch(value: String): CloudRuleEntry?
}
