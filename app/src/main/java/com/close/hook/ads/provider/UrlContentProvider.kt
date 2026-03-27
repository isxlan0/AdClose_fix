package com.close.hook.ads.provider

import android.content.Context
import android.content.ContentProvider
import android.content.ContentUris
import android.content.ContentValues
import android.content.UriMatcher
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.close.hook.ads.data.dao.CloudRuleEntryDao
import com.close.hook.ads.data.dao.UrlDao
import com.close.hook.ads.data.database.UrlDatabase
import com.close.hook.ads.data.model.Url
import com.close.hook.ads.rule.RuleSnapshotBuilder
import com.close.hook.ads.rule.RuleSnapshotPayload
import com.close.hook.ads.rule.RuleSnapshotStore
import com.close.hook.ads.util.RuleUtils
import kotlinx.serialization.decodeFromString
import java.io.FileNotFoundException
import java.nio.charset.StandardCharsets

class UrlContentProvider : ContentProvider() {

    private lateinit var urlDao: UrlDao
    private lateinit var cloudRuleEntryDao: CloudRuleEntryDao
    private lateinit var snapshotBuilder: RuleSnapshotBuilder

    override fun onCreate(): Boolean = context?.let {
        val database = UrlDatabase.getDatabase(it)
        urlDao = database.urlDao
        cloudRuleEntryDao = database.cloudRuleEntryDao
        snapshotBuilder = RuleSnapshotBuilder.getInstance(it)
        true
    } ?: false

    override fun query(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?
    ): Cursor? = when (uriMatcher.match(uri)) {
        ID_URL_DATA -> handleQueryData(selectionArgs)
        ID_URL_DATA_ITEM -> null
        else -> null
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? = when (uriMatcher.match(uri)) {
        ID_RULE_SNAPSHOT -> {
            val providerContext = context ?: throw FileNotFoundException("Provider context unavailable")
            ensureSnapshotReady()
            RuleSnapshotStore.openCurrentSnapshot(providerContext)
        }
        else -> throw FileNotFoundException("Unsupported URI: $uri")
    }

    private fun handleQueryData(selectionArgs: Array<String>?): Cursor? {
        val urls: List<Url> = if (selectionArgs == null || selectionArgs.size != 2) {
            urlDao.findAllList()
        } else {
            val (queryType, queryValue) = selectionArgs
            val result = findMatch(queryType, queryValue)
            listOfNotNull(result)
        }
        return urlsToCursor(urls)
    }

    private fun findMatch(queryType: String, queryValue: String): Url? {
        val normalizedType = RuleUtils.normalizeType(queryType) ?: return null
        val manualMatch = when (normalizedType) {
            RuleUtils.TYPE_URL -> findManualUrlMatch(queryValue)
            RuleUtils.TYPE_DOMAIN -> findManualDomainMatch(queryValue)
            RuleUtils.TYPE_KEYWORD -> urlDao.findKeywordMatch(queryValue)
            else -> null
        }
        if (manualMatch != null) return manualMatch

        val cloudMatch = when (normalizedType) {
            RuleUtils.TYPE_URL -> findCloudUrlMatch(queryValue)
            RuleUtils.TYPE_DOMAIN -> findCloudDomainMatch(queryValue)
            RuleUtils.TYPE_KEYWORD -> cloudRuleEntryDao.findEnabledKeywordMatch(queryValue)
            else -> null
        } ?: return null

        return Url(type = cloudMatch.type, url = cloudMatch.url)
    }

    private fun findManualUrlMatch(queryValue: String): Url? {
        val candidates = RuleUtils.buildUrlPrefixCandidates(queryValue)
        if (candidates.isEmpty()) return null
        return candidates
            .asSequence()
            .chunked(MAX_CANDIDATES_PER_QUERY)
            .mapNotNull(urlDao::findUrlMatchByCandidates)
            .firstOrNull()
    }

    private fun findCloudUrlMatch(queryValue: String): com.close.hook.ads.data.model.CloudRuleEntry? {
        val candidates = RuleUtils.buildUrlPrefixCandidates(queryValue)
        if (candidates.isEmpty()) return null
        return candidates
            .asSequence()
            .chunked(MAX_CANDIDATES_PER_QUERY)
            .mapNotNull(cloudRuleEntryDao::findEnabledUrlMatchByCandidates)
            .firstOrNull()
    }

    private fun findManualDomainMatch(queryValue: String): Url? {
        val candidates = RuleUtils.buildDomainMatchCandidates(queryValue)
        if (candidates.isEmpty()) return null
        return urlDao.findDomainMatchByCandidates(candidates.first(), candidates)
    }

    private fun findCloudDomainMatch(queryValue: String): com.close.hook.ads.data.model.CloudRuleEntry? {
        val candidates = RuleUtils.buildDomainMatchCandidates(queryValue)
        if (candidates.isEmpty()) return null
        return cloudRuleEntryDao.findEnabledDomainMatchByCandidates(candidates.first(), candidates)
    }

    private fun urlsToCursor(urls: List<Url>): MatrixCursor {
        val cursor = MatrixCursor(arrayOf(Url.URL_TYPE, Url.URL_ADDRESS))
        urls.forEach { url ->
            val displayType = RuleUtils.normalizeType(url.type) ?: url.type
            val displayValue = RuleUtils.normalizeValue(displayType, url.url) ?: url.url.trim()
            cursor.addRow(arrayOf(displayType, displayValue))
        }
        return cursor
    }

    override fun getType(uri: Uri): String? = when (uriMatcher.match(uri)) {
        ID_URL_DATA -> "vnd.android.cursor.dir/$AUTHORITY.$URL_TABLE_NAME"
        ID_URL_DATA_ITEM -> "vnd.android.cursor.item/$AUTHORITY.$URL_TABLE_NAME"
        else -> null
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? =
        if (uriMatcher.match(uri) == ID_URL_DATA && values != null) {
            urlDao.insert(values.toUrl()).takeIf { it > 0 }?.let { id ->
                rebuildSnapshotAndNotify(uri)
                ContentUris.withAppendedId(uri, id)
            }
        } else null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int =
        if (uriMatcher.match(uri) == ID_URL_DATA_ITEM) {
            urlDao.deleteById(ContentUris.parseId(uri)).also { count ->
                if (count > 0) rebuildSnapshotAndNotify(uri)
            }
        } else 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int =
        if (uriMatcher.match(uri) == ID_URL_DATA_ITEM && values != null) {
            val url = values.toUrl().apply { id = ContentUris.parseId(uri) }
            urlDao.update(url).also { count ->
                if (count > 0) rebuildSnapshotAndNotify(uri)
            }
        } else 0

    private fun notifyChange(uri: Uri) {
        context?.contentResolver?.notifyChange(uri, null)
    }

    private fun rebuildSnapshotAndNotify(uri: Uri) {
        if (!snapshotBuilder.rebuild()) {
            snapshotBuilder.invalidate()
        }
        notifyChange(uri)
    }

    private fun ensureSnapshotReady() {
        val providerContext = context ?: return
        val currentSnapshot = RuleSnapshotStore.currentSnapshotFile(providerContext)
        val snapshotUsable = currentSnapshot.exists() && runCatching {
            RuleSnapshotPayload.JSON.decodeFromString<RuleSnapshotPayload>(
                currentSnapshot.readText(StandardCharsets.UTF_8)
            ).version == RuleSnapshotPayload.CURRENT_VERSION
        }.getOrDefault(false)

        if (snapshotUsable) {
            return
        }
        if (!snapshotBuilder.rebuild()) {
            snapshotBuilder.invalidate()
        }
    }

    private fun ContentValues.toUrl(): Url =
        RuleUtils.normalizeRule(getAsString(Url.URL_TYPE), getAsString(Url.URL_ADDRESS))
            ?: Url(
                type = RuleUtils.normalizeType(getAsString(Url.URL_TYPE)) ?: getAsString(Url.URL_TYPE).orEmpty(),
                url = getAsString(Url.URL_ADDRESS).orEmpty()
            )

    companion object {
        const val AUTHORITY = "com.close.hook.ads.provider.url"
        const val URL_TABLE_NAME = "url_info"
        private const val ID_URL_DATA = 1
        private const val ID_URL_DATA_ITEM = 2
        private const val MAX_CANDIDATES_PER_QUERY = 800
        private const val RULE_SNAPSHOT_PATH = "snapshot"
        private const val ID_RULE_SNAPSHOT = 3
        val CONTENT_URI: Uri = Uri.Builder()
            .scheme("content")
            .authority(AUTHORITY)
            .appendPath(URL_TABLE_NAME)
            .build()
        val SNAPSHOT_URI: Uri = Uri.Builder()
            .scheme("content")
            .authority(AUTHORITY)
            .appendPath(URL_TABLE_NAME)
            .appendPath(RULE_SNAPSHOT_PATH)
            .build()

        private val uriMatcher = UriMatcher(UriMatcher.NO_MATCH).apply {
            addURI(AUTHORITY, URL_TABLE_NAME, ID_URL_DATA)
            addURI(AUTHORITY, "$URL_TABLE_NAME/#", ID_URL_DATA_ITEM)
            addURI(AUTHORITY, "$URL_TABLE_NAME/$RULE_SNAPSHOT_PATH", ID_RULE_SNAPSHOT)
        }

        fun notifyRulesChanged(context: Context) {
            context.applicationContext.contentResolver.notifyChange(CONTENT_URI, null)
        }
    }
}
