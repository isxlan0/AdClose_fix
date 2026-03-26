package com.close.hook.ads.data

import android.content.Context
import com.close.hook.ads.data.database.UrlDatabase
import com.close.hook.ads.data.model.Url
import com.close.hook.ads.util.RuleUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class DataSource(context: Context) {

    private val urlDao = UrlDatabase.getDatabase(context).urlDao

    fun searchUrls(searchText: String): Flow<List<Url>> =
        if (searchText.isBlank()) urlDao.loadAllList() else urlDao.searchUrls(searchText)

    suspend fun addUrl(url: Url) {
        val normalizedUrl = RuleUtils.normalizeRule(url) ?: return
        if (!urlDao.isExist(normalizedUrl.type, normalizedUrl.url)) {
            urlDao.insert(normalizedUrl)
        }
    }

    suspend fun removeList(list: List<Url>) {
        if (list.isNotEmpty()) {
            urlDao.deleteList(list)
        }
    }

    suspend fun removeUrl(url: Url) {
        urlDao.deleteUrl(url)
    }

    suspend fun removeAll() {
        urlDao.deleteAll()
    }

    suspend fun addListUrl(list: List<Url>) {
        val normalizedUrls = list.mapNotNull { RuleUtils.normalizeRule(it) }
            .distinctBy { RuleUtils.canonicalKey(it.type, it.url) }
        if (normalizedUrls.isNotEmpty()) {
            urlDao.insertAll(normalizedUrls)
        }
    }

    suspend fun updateUrl(url: Url) {
        RuleUtils.normalizeRule(url)?.let { urlDao.update(it) }
    }

    suspend fun removeUrlString(type: String, url: String) {
        val normalizedType = RuleUtils.normalizeType(type) ?: return
        val normalizedUrl = RuleUtils.normalizeValue(normalizedType, url) ?: return
        urlDao.deleteUrlString(normalizedType, normalizedUrl)
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
            if (normalizedUrls.isEmpty()) emptyList() else urlDao.insertAll(normalizedUrls)
        }

    suspend fun deleteAll(): Int =
        withContext(Dispatchers.IO) { urlDao.deleteAll() }

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
}
