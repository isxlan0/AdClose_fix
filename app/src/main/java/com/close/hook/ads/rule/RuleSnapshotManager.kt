package com.close.hook.ads.rule

import android.content.Context
import android.os.ParcelFileDescriptor
import android.util.Log
import com.close.hook.ads.provider.UrlContentProvider
import kotlinx.serialization.decodeFromString
import java.io.FileInputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

object RuleSnapshotManager {

    private const val TAG = "RuleSnapshotManager"
    private const val RELOAD_RETRY_INTERVAL_MS = 5_000L

    @Volatile
    private var currentMatcher: RuleMatcher? = null

    @Volatile
    private var stale: Boolean = true

    @Volatile
    private var lastLoadAttemptAt: Long = 0L

    private val reloadLock = Any()

    fun markStale() {
        stale = true
        lastLoadAttemptAt = 0L
    }

    fun current(context: Context): RuleMatcher? {
        if (!stale) {
            return currentMatcher
        }

        val now = System.currentTimeMillis()
        if (now - lastLoadAttemptAt < RELOAD_RETRY_INTERVAL_MS) {
            return currentMatcher
        }

        synchronized(reloadLock) {
            if (!stale) {
                return currentMatcher
            }
            val recheckedNow = System.currentTimeMillis()
            if (recheckedNow - lastLoadAttemptAt < RELOAD_RETRY_INTERVAL_MS) {
                return currentMatcher
            }

            lastLoadAttemptAt = recheckedNow
            val loadedMatcher = loadMatcher(context)
            currentMatcher = loadedMatcher
            stale = loadedMatcher == null
            return loadedMatcher
        }
    }

    private fun loadMatcher(context: Context): RuleMatcher? {
        return runCatching {
            context.contentResolver.openFileDescriptor(UrlContentProvider.SNAPSHOT_URI, "r")
                ?.use(::readSnapshotPayload)
                ?.takeIf { it.version == RuleSnapshotPayload.CURRENT_VERSION }
                ?.let(::SnapshotRuleMatcher)
        }.onFailure {
            Log.w(TAG, "Failed to load rule snapshot", it)
        }.getOrNull()
    }

    private fun readSnapshotPayload(descriptor: ParcelFileDescriptor): RuleSnapshotPayload {
        return FileInputStream(descriptor.fileDescriptor).use { input ->
            InputStreamReader(input, StandardCharsets.UTF_8).use { reader ->
                RuleSnapshotPayload.JSON.decodeFromString(reader.readText())
            }
        }
    }
}
