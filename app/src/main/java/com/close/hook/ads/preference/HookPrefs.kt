package com.close.hook.ads.preference

import android.os.ParcelFileDescriptor
import android.util.Log
import com.close.hook.ads.closeApp
import com.close.hook.ads.data.model.CustomHookInfo
import com.close.hook.ads.hook.HookLogic
import com.close.hook.ads.manager.ServiceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap

object HookPrefs {

    private const val TAG = "HookPrefs"
    private const val GLOBAL_KEY = "global"
    private const val LOCAL_CACHE_DIR = "hook_prefs_cache"
    private const val FILE_PENDING_SYNC_STATE = ".pending_remote_sync.json"

    private const val FILE_GENERAL_SETTINGS = "com.close.hook.ads_preferences.json"
    private const val FILE_PREFIX_CUSTOM_HOOK = "custom_hooks_"
    private const val SYNC_OP_UPSERT = "upsert"
    private const val SYNC_OP_DELETE = "delete"

    private const val KEY_PREFIX_OVERALL_HOOK = "overall_hook_enabled_"
    private const val KEY_PREFIX_ENABLE_LOGGING = "enable_logging_"
    const val KEY_COLLECT_RESPONSE_BODY = "collect_response_body_enabled"
    const val KEY_ENABLE_DEX_DUMP = "enable_dex_dump"
    const val KEY_REQUEST_CACHE_EXPIRATION = "request_cache_expiration"

    private val jsonFormat = Json { 
        ignoreUnknownKeys = true 
        prettyPrint = true 
        isLenient = true
    }

    private val ioScope = CoroutineScope(Dispatchers.IO.limitedParallelism(1) + SupervisorJob())

    private interface IFileAccessor {
        fun openRemoteFile(fileName: String, mode: String = "rw"): ParcelFileDescriptor?
        fun listRemoteFiles(): Array<String>?
        fun deleteRemoteFile(fileName: String): Boolean
    }

    private val fileAccessor: IFileAccessor?
        get() {
            ServiceManager.service?.let { service ->
                return object : IFileAccessor {
                    override fun openRemoteFile(fileName: String, mode: String) =
                        service.openRemoteFile(fileName)
                    override fun listRemoteFiles(): Array<String>? = service.listRemoteFiles()
                    override fun deleteRemoteFile(fileName: String) = service.deleteRemoteFile(fileName)
                }
            }
            HookLogic.xposedInterface?.let { xposedInterface ->
                return object : IFileAccessor {
                    override fun openRemoteFile(fileName: String, mode: String) =
                        xposedInterface.openRemoteFile(fileName)
                    override fun listRemoteFiles(): Array<String>? = xposedInterface.listRemoteFiles()
                    override fun deleteRemoteFile(fileName: String): Boolean = false
                }
            }
            return null
        }

    private val generalSettingsCache = MutableStateFlow<JsonObject?>(null)
    val generalSettingsFlow = generalSettingsCache.asStateFlow()
    
    private val cacheLock = Any()
    private val pendingSyncLock = Any()

    private fun getLocalCacheDir() = try {
        closeApp.filesDir.resolve(LOCAL_CACHE_DIR).apply { mkdirs() }
    } catch (_: UninitializedPropertyAccessException) {
        null
    }

    private fun getLocalCacheFile(fileName: String) = getLocalCacheDir()?.resolve(fileName)

    private fun readLocalCacheOrNull(fileName: String): String? {
        val file = getLocalCacheFile(fileName) ?: return null
        return try {
            if (file.exists()) file.readText(StandardCharsets.UTF_8) else null
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read local cache: $fileName", e)
            null
        }
    }

    private fun readLocalCache(fileName: String): String = readLocalCacheOrNull(fileName).orEmpty()

    private fun writeLocalCache(fileName: String, content: String): Boolean {
        val file = getLocalCacheFile(fileName) ?: return false
        return try {
            file.writeText(content, StandardCharsets.UTF_8)
            true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to write local cache: $fileName", e)
            false
        }
    }

    private fun deleteLocalCache(fileName: String): Boolean {
        val file = getLocalCacheFile(fileName) ?: return false
        return !file.exists() || file.delete()
    }

    private fun hasLocalCache(fileName: String): Boolean {
        return getLocalCacheFile(fileName)?.exists() == true
    }

    private fun getSettingsJson(): JsonObject {
        val cached = generalSettingsCache.value
        if (cached != null) return cached

        synchronized(cacheLock) {
            val cachedAgain = generalSettingsCache.value
            if (cachedAgain != null) return cachedAgain

            val jsonObject = readSettingsFromFile()
            if (jsonObject != null) {
                generalSettingsCache.value = jsonObject
                return jsonObject
            }
            return JsonObject(emptyMap())
        }
    }

    private fun updateSetting(transform: (MutableMap<String, JsonElement>) -> Unit) {
        val newJson: JsonObject
        synchronized(cacheLock) {
            val baseline = generalSettingsCache.value ?: readSettingsFromFile()
            if (baseline == null) {
                Log.e(TAG, "Write aborted for general settings: no safe baseline while framework service is disconnected.")
                return
            }

            val mutableMap = baseline.toMutableMap()
            transform(mutableMap)
            newJson = JsonObject(mutableMap)
            generalSettingsCache.value = newJson
        }

        ioScope.launch {
            try {
                val jsonString = jsonFormat.encodeToString(newJson)
                writeTextToFile(FILE_GENERAL_SETTINGS, jsonString)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to persist settings", e)
            }
        }
    }

    fun getBoolean(key: String, defaultValue: Boolean): Boolean {
        val element = getSettingsJson()[key]
        return if (element is JsonPrimitive) {
            element.booleanOrNull ?: defaultValue
        } else {
            defaultValue
        }
    }

    fun setBoolean(key: String, value: Boolean) {
        updateSetting { it[key] = JsonPrimitive(value) }
    }
    
    fun getLong(key: String, defaultValue: Long): Long {
        val element = getSettingsJson()[key]
        return if (element is JsonPrimitive) {
            element.longOrNull ?: element.contentOrNull?.toLongOrNull() ?: defaultValue
        } else {
            defaultValue
        }
    }

    fun setLong(key: String, value: Long) {
        updateSetting { it[key] = JsonPrimitive(value) }
    }

    fun getString(key: String, defaultValue: String?): String? {
        val element = getSettingsJson()[key]
        return if (element is JsonPrimitive) {
            element.contentOrNull ?: defaultValue
        } else {
            defaultValue
        }
    }

    fun setString(key: String, value: String?) {
        updateSetting {
            if (value == null) it.remove(key) else it[key] = JsonPrimitive(value)
        }
    }

    fun setMultiple(updates: Map<String, Any>) {
        if (updates.isEmpty()) return
        updateSetting { map ->
            updates.forEach { (k, v) ->
                val jsonElement = when (v) {
                    is Boolean -> JsonPrimitive(v)
                    is Number -> JsonPrimitive(v)
                    is String -> JsonPrimitive(v)
                    else -> JsonPrimitive(v.toString())
                }
                map[k] = jsonElement
            }
        }
    }

    fun remove(key: String) {
        updateSetting { it.remove(key) }
    }

    fun clear() {
        updateSetting { it.clear() }
    }

    fun contains(key: String): Boolean {
        return getSettingsJson().containsKey(key)
    }

    fun getAll(): Map<String, Any?> {
        val json = getSettingsJson()
        return json.mapValues { entry ->
            val primitive = entry.value as? JsonPrimitive
            when {
                primitive?.isString == true -> primitive.content
                primitive != null -> {
                    primitive.booleanOrNull 
                        ?: primitive.longOrNull 
                        ?: primitive.doubleOrNull 
                        ?: primitive.content
                }
                else -> entry.value.toString()
            }
        }
    }

    private val customHookCache = ConcurrentHashMap<String, List<CustomHookInfo>>()

    fun getCustomHookConfigs(packageName: String?): List<CustomHookInfo> {
        val effectiveKey = packageName ?: GLOBAL_KEY
        customHookCache[effectiveKey]?.let { return it }

        val fileName = buildFileName(FILE_PREFIX_CUSTOM_HOOK, effectiveKey)
        val content = readAllTextFromFile(fileName)
        if (content == null) {
            Log.w(TAG, "Cannot load custom hooks for $effectiveKey: no safe baseline while framework service is disconnected.")
            return emptyList()
        }

        val result = if (content.isBlank()) {
            emptyList()
        } else {
            try {
                jsonFormat.decodeFromString<List<CustomHookInfo>>(content)
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing JSON from file: $fileName", e)
                emptyList()
            }
        }
        customHookCache[effectiveKey] = result
        return result
    }

    fun setCustomHookConfigs(packageName: String?, configs: List<CustomHookInfo>) {
        val effectiveKey = packageName ?: GLOBAL_KEY
        val fileName = buildFileName(FILE_PREFIX_CUSTOM_HOOK, effectiveKey)
        if (!canModifySafely(fileName, customHookCache.containsKey(effectiveKey))) {
            Log.e(TAG, "Write aborted for custom hooks: no safe baseline for $effectiveKey while framework service is disconnected.")
            return
        }
        
        if (configs.isEmpty()) {
            customHookCache.remove(effectiveKey)
        } else {
            customHookCache[effectiveKey] = configs
        }

        ioScope.launch {
            if (configs.isEmpty()) {
                deleteConfigFile(fileName)
            } else {
                try {
                    val jsonString = jsonFormat.encodeToString(configs)
                    if (!writeTextToFile(fileName, jsonString)) {
                        Log.e(TAG, "Failed to save hooks to file: $fileName")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Serialization error for hooks", e)
                }
            }
        }
    }

    fun invalidateCaches() {
        synchronized(cacheLock) {
            generalSettingsCache.value = null
        }
        customHookCache.clear()
        Log.d(TAG, "All caches invalidated.")
    }

    private fun readSettingsFromFile(): JsonObject? {
        val content = readAllTextFromFile(FILE_GENERAL_SETTINGS) ?: return null
        return if (content.isNotBlank()) {
            try {
                jsonFormat.parseToJsonElement(content) as? JsonObject ?: JsonObject(emptyMap())
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse settings JSON", e)
                JsonObject(emptyMap())
            }
        } else {
            JsonObject(emptyMap())
        }
    }

    private fun readAllTextFromFile(fileName: String): String? {
        val accessor = fileAccessor
        if (accessor == null) {
            return readLocalCacheOrNull(fileName)
        }

        when (getPendingSyncOperation(fileName)) {
            SYNC_OP_UPSERT -> {
                readLocalCacheOrNull(fileName)?.let { return it }
            }
            SYNC_OP_DELETE -> return ""
        }

        val remoteFiles = try {
            accessor.listRemoteFiles()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to list remote files for $fileName", e)
            return readLocalCacheOrNull(fileName)
        }

        if (remoteFiles == null || !remoteFiles.contains(fileName)) {
            return ""
        }

        return try {
            accessor.openRemoteFile(fileName, "r")?.use { pfd ->
                InputStreamReader(ParcelFileDescriptor.AutoCloseInputStream(pfd), StandardCharsets.UTF_8).use {
                    it.readText().also { content ->
                        if (content.isNotEmpty()) {
                            writeLocalCache(fileName, content)
                        }
                    }
                }
            } ?: readLocalCacheOrNull(fileName)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read remote file: $fileName", e)
            readLocalCacheOrNull(fileName)
        }
    }

    private fun canModifySafely(fileName: String, hasInMemoryBaseline: Boolean): Boolean {
        return fileAccessor != null || hasInMemoryBaseline || hasLocalCache(fileName)
    }

    private fun loadPendingSyncState(): MutableMap<String, String> {
        val raw = readLocalCacheOrNull(FILE_PENDING_SYNC_STATE) ?: return mutableMapOf()
        return try {
            jsonFormat.decodeFromString<Map<String, String>>(raw).toMutableMap()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse pending sync state.", e)
            mutableMapOf()
        }
    }

    private fun savePendingSyncState(state: Map<String, String>) {
        if (state.isEmpty()) {
            deleteLocalCache(FILE_PENDING_SYNC_STATE)
            return
        }

        val serialized = runCatching { jsonFormat.encodeToString(state) }.getOrElse {
            Log.e(TAG, "Failed to encode pending sync state.", it)
            return
        }
        writeLocalCache(FILE_PENDING_SYNC_STATE, serialized)
    }

    private fun markPendingSync(fileName: String, operation: String) {
        synchronized(pendingSyncLock) {
            val state = loadPendingSyncState()
            state[fileName] = operation
            savePendingSyncState(state)
        }
    }

    private fun clearPendingSync(fileName: String) {
        synchronized(pendingSyncLock) {
            val state = loadPendingSyncState()
            if (state.remove(fileName) != null) {
                savePendingSyncState(state)
            }
        }
    }

    private fun snapshotPendingSyncState(): Map<String, String> {
        return synchronized(pendingSyncLock) { loadPendingSyncState().toMap() }
    }

    private fun getPendingSyncOperation(fileName: String): String? {
        return synchronized(pendingSyncLock) { loadPendingSyncState()[fileName] }
    }

    private fun writeRemoteText(accessor: IFileAccessor, fileName: String, content: String): Boolean {
        return try {
            accessor.openRemoteFile(fileName, "rw")?.use { pfd ->
                FileOutputStream(pfd.fileDescriptor).use { fos ->
                    fos.channel.truncate(0)
                    fos.writer(StandardCharsets.UTF_8).use { writer ->
                        writer.write(content)
                    }
                }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write remote file: $fileName", e)
            false
        }
    }

    private fun deleteRemoteFile(accessor: IFileAccessor, fileName: String): Boolean {
        return try {
            accessor.deleteRemoteFile(fileName)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete remote file: $fileName", e)
            false
        }
    }

    private fun writeTextToFile(fileName: String, content: String): Boolean {
        val localSuccess = writeLocalCache(fileName, content)
        val accessor = fileAccessor
        val remoteSuccess = if (accessor != null) writeRemoteText(accessor, fileName, content) else false
        if (remoteSuccess) {
            clearPendingSync(fileName)
        } else if (localSuccess) {
            markPendingSync(fileName, SYNC_OP_UPSERT)
        }
        return remoteSuccess || localSuccess
    }

    private fun deleteConfigFile(fileName: String): Boolean {
        val localDeleted = deleteLocalCache(fileName)
        val accessor = fileAccessor
        val remoteDeleted = if (accessor != null) deleteRemoteFile(accessor, fileName) else false
        if (remoteDeleted) {
            clearPendingSync(fileName)
        } else if (localDeleted) {
            markPendingSync(fileName, SYNC_OP_DELETE)
        }
        return localDeleted || remoteDeleted
    }

    internal fun buildKey(prefix: String, packageName: String?): String {
        return prefix + (packageName ?: GLOBAL_KEY)
    }

    private fun buildFileName(prefix: String, key: String): String {
        return "$prefix$key.json"
    }

    fun getOverallHookEnabled(packageName: String?): Boolean {
        return getBoolean(buildKey(KEY_PREFIX_OVERALL_HOOK, packageName), false)
    }

    fun setOverallHookEnabled(packageName: String?, isEnabled: Boolean) {
        setBoolean(buildKey(KEY_PREFIX_OVERALL_HOOK, packageName), isEnabled)
    }

    fun getEnableLogging(packageName: String?): Boolean {
        return getBoolean(buildKey(KEY_PREFIX_ENABLE_LOGGING, packageName), false)
    }

    fun setEnableLogging(packageName: String?, isEnabled: Boolean) {
        setBoolean(buildKey(KEY_PREFIX_ENABLE_LOGGING, packageName), isEnabled)
    }

    fun getRequestCacheExpiration(): Long {
        return getString(KEY_REQUEST_CACHE_EXPIRATION, "5")?.toLongOrNull() ?: 5L
    }

    fun syncLocalCacheToRemote() {
        val accessor = fileAccessor ?: return
        val pendingState = snapshotPendingSyncState()
        if (pendingState.isEmpty()) return

        pendingState.forEach { (fileName, operation) ->
            if (fileName == FILE_PENDING_SYNC_STATE) return@forEach

            when (operation) {
                SYNC_OP_UPSERT -> {
                    val localContent = readLocalCacheOrNull(fileName)
                    if (localContent == null) {
                        Log.w(TAG, "Skipped pending upsert for missing local file: $fileName")
                        return@forEach
                    }
                    if (writeRemoteText(accessor, fileName, localContent)) {
                        clearPendingSync(fileName)
                    }
                }
                SYNC_OP_DELETE -> {
                    if (deleteRemoteFile(accessor, fileName)) {
                        clearPendingSync(fileName)
                    }
                }
                else -> {
                    Log.w(TAG, "Unknown pending sync operation for $fileName: $operation")
                    clearPendingSync(fileName)
                }
            }
        }
    }
}
