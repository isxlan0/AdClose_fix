package com.close.hook.ads.data

import android.content.Context
import com.close.hook.ads.data.database.UrlDatabase
import com.close.hook.ads.data.model.Url
import com.close.hook.ads.provider.UrlContentProvider
import com.close.hook.ads.rule.RuleSnapshotBuilder
import com.close.hook.ads.util.RuleUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class DataSource(context: Context) {

    private val appContext = context.applicationContext
    private val urlDao = UrlDatabase.getDatabase(appContext).urlDao
    private val snapshotBuilder = RuleSnapshotBuilder.getInstance(appContext)

    fun searchUrls(searchText: String): Flow<List<Url>> =
        if (searchText.isBlank()) urlDao.loadAllList() else urlDao.searchUrls(searchText)

    suspend fun addUrl(url: Url) {
        val normalizedUrl = RuleUtils.normalizeRule(url) ?: return
        if (!urlDao.isExist(normalizedUrl.type, normalizedUrl.url)) {
            if (urlDao.insert(normalizedUrl) > 0) {
                notifyRulesChanged()
            }
        }
    }

    suspend fun removeList(list: List<Url>) {
        if (list.isNotEmpty()) {
            if (urlDao.deleteList(list) > 0) {
                notifyRulesChanged()
            }
        }
    }

    suspend fun removeUrl(url: Url) {
        if (urlDao.deleteUrl(url) > 0) {
            notifyRulesChanged()
        }
    }

    suspend fun removeAll() {
        if (urlDao.deleteAll() > 0) {
            notifyRulesChanged()
        }
    }

    suspend fun addListUrl(list: List<Url>) {
        val normalizedUrls = list.mapNotNull { RuleUtils.normalizeRule(it) }
            .distinctBy { RuleUtils.canonicalKey(it.type, it.url) }
        if (normalizedUrls.isNotEmpty()) {
            if (urlDao.insertAll(normalizedUrls).any { it > 0 }) {
                notifyRulesChanged()
            }
        }
    }

    suspend fun updateUrl(url: Url) {
        RuleUtils.normalizeRule(url)?.let {
            if (urlDao.update(it) > 0) {
                notifyRulesChanged()
            }
        }
    }

    suspend fun removeUrlString(type: String, url: String) {
        val normalizedType = RuleUtils.normalizeType(type) ?: return
        val normalizedUrl = RuleUtils.normalizeValue(normalizedType, url) ?: return
        if (urlDao.deleteUrlString(normalizedType, normalizedUrl) > 0) {
            notifyRulesChanged()
        }
    }

    suspend fun isExist(type: String, url: String): Boolean =
        withContext(Dispatchers.IO) {
            val normalizedType = RuleUtils.normalizeType(type) ?: return@withContext false
            val normalizedUrl = RuleUtils.normalizeValue(normalizedType, url) ?: return@withContext false
            urlDao.isExist(normalizedType, normalizedUrl)
        }

    suspend fun insertAll(urls: List<Url>): List<Long> =
        withContext(Dispatchers.IO) {
            val normalizedUrls = urls.mapNotNull { RuleUtils.normalizeRule(it) }
                .distinctBy { RuleUtils.canonicalKey(it.type, it.url) }
            if (normalizedUrls.isEmpty()) {
                emptyList()
            } else {
                urlDao.insertAll(normalizedUrls).also {
                    if (it.any { id -> id > 0 }) {
                        notifyRulesChanged()
                    }
                }
            }
        }

    suspend fun deleteAll(): Int =
        withContext(Dispatchers.IO) {
            urlDao.deleteAll().also {
                if (it > 0) {
                    notifyRulesChanged()
                }
            }
        }

    fun getAllUrls(): List<Url> {
        return urlDao.findAllList()
    }

    companion object {
        @Volatile
        private var INSTANCE: DataSource? = null

        fun getDataSource(context: Context): DataSource {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: DataSource(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private fun notifyRulesChanged() {
        if (!snapshotBuilder.rebuild()) {
            snapshotBuilder.invalidate()
        }
        UrlContentProvider.notifyRulesChanged(appContext)
    }
}
