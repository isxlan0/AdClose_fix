package com.close.hook.ads.hook.gc.network

import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.contentValuesOf
import com.close.hook.ads.data.model.BlockedRequest
import com.close.hook.ads.data.model.RequestInfo
import com.close.hook.ads.data.model.Url
import com.close.hook.ads.hook.util.HookUtil
import com.close.hook.ads.preference.HookPrefs
import com.close.hook.ads.provider.TemporaryFileProvider
import com.close.hook.ads.provider.UrlContentProvider
import com.close.hook.ads.util.AppUtils
import com.google.common.cache.Cache
import com.google.common.cache.CacheBuilder
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.URL
import java.net.URLDecoder
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

object RequestHook {

    private const val LOG_PREFIX = "[RequestHook] "
    private const val MAX_STREAM_BUFFER_BYTES = 512 * 1024
    internal const val MAX_CAPTURED_BODY_BYTES = 256 * 1024
    private const val KEY_SCOPE_JAVA_SOCKET = 0x1000_0000_0000_0000L
    private const val KEY_SCOPE_JAVA_SSL = 0x2000_0000_0000_0000L
    private const val KEY_SCOPE_NATIVE_SOCKET = 0x3000_0000_0000_0000L
    private const val KEY_SCOPE_NATIVE_SSL = 0x4000_0000_0000_0000L
    private const val KEY_VALUE_MASK = 0x0FFF_FFFF_FFFF_FFFFL
    private val UTF8: Charset = StandardCharsets.UTF_8

    internal lateinit var applicationContext: Context

    private val requestIdGenerator = AtomicLong(0)
    private val announcedPendingRequestIds = ConcurrentHashMap.newKeySet<String>()
    private val isRuleObserverRegistered = AtomicBoolean(false)
    @Volatile
    private var ruleChangeObserver: ContentObserver? = null

    private data class ParsingState(
        var isHeaderParsed: Boolean = false,
        var contentLength: Int = -1,
        var isChunked: Boolean = false,
        var headerSize: Int = 0
    )
    private val requestParsingStates = ConcurrentHashMap<Long, ParsingState>()
    private val responseParsingStates = ConcurrentHashMap<Long, ParsingState>()

    internal val requestBuffers = ConcurrentHashMap<Long, ByteArrayOutputStream>()
    internal val responseBuffers = ConcurrentHashMap<Long, ByteArrayOutputStream>()
    internal val pendingRequests = ConcurrentHashMap<Long, BlockedRequest>()
    private val headerEndMarker = "\r\n\r\n".toByteArray()

    private val URL_CONTENT_URI: Uri = UrlContentProvider.CONTENT_URI

    private val queryCache: Cache<String, Triple<Boolean, String?, String?>> = CacheBuilder.newBuilder()
        .maximumSize(1000)
        .expireAfterAccess(4, TimeUnit.HOURS)
        .softValues()
        .build()

    fun init(context: Context) {
        applicationContext = context
        registerRuleChangeObserverAsync()
    }

    private fun registerRuleChangeObserverAsync() {
        if (!isRuleObserverRegistered.compareAndSet(false, true)) return

        Thread({
            try {
                val mainLooper = Looper.getMainLooper()
                if (mainLooper == null) {
                    isRuleObserverRegistered.set(false)
                    return@Thread
                }
                val observer = object : ContentObserver(Handler(mainLooper)) {
                    override fun onChange(selfChange: Boolean) {
                        queryCache.invalidateAll()
                    }

                    override fun onChange(selfChange: Boolean, uri: Uri?) {
                        queryCache.invalidateAll()
                    }
                }
                applicationContext.contentResolver.registerContentObserver(URL_CONTENT_URI, true, observer)
                ruleChangeObserver = observer
            } catch (e: Throwable) {
                isRuleObserverRegistered.set(false)
                XposedBridge.log("$LOG_PREFIX Failed to register rule observer: ${e.message}")
            }
        }, "AdClose-RuleObserver").apply {
            isDaemon = true
            start()
        }
    }

    private fun scopedKey(scope: Long, rawValue: Long): Long = scope or (rawValue and KEY_VALUE_MASK)

    internal fun javaSocketKey(connection: Any): Long =
        scopedKey(KEY_SCOPE_JAVA_SOCKET, System.identityHashCode(connection).toLong())

    internal fun javaSslKey(connection: Any): Long =
        scopedKey(KEY_SCOPE_JAVA_SSL, System.identityHashCode(connection).toLong())

    internal fun nativeKey(rawId: Long, isSsl: Boolean): Long =
        scopedKey(if (isSsl) KEY_SCOPE_NATIVE_SSL else KEY_SCOPE_NATIVE_SOCKET, rawId)

    internal fun nextRequestId(prefix: String): String =
        "$prefix-${System.currentTimeMillis()}-${requestIdGenerator.incrementAndGet()}"

    internal fun appendToBuffer(
        key: Long,
        buffers: ConcurrentHashMap<Long, ByteArrayOutputStream>,
        bytes: ByteArray,
        offset: Int,
        len: Int,
        bufferLabel: String
    ): ByteArrayOutputStream? {
        if (offset < 0 || len <= 0 || offset > bytes.size || bytes.size - offset < len) {
            return null
        }

        val buffer = buffers.getOrPut(key) { ByteArrayOutputStream(minOf(len, 8 * 1024)) }
        val newSize = buffer.size() + len
        if (newSize > MAX_STREAM_BUFFER_BYTES) {
            XposedBridge.log("$LOG_PREFIX Drop oversized $bufferLabel capture, key=$key size=$newSize")
            releaseConnection(key)
            return null
        }

        buffer.write(bytes, offset, len)
        return buffer
    }

    internal fun releaseConnection(key: Long) {
        pendingRequests[key]?.requestId?.let { announcedPendingRequestIds.remove(it) }
        requestBuffers.remove(key)
        responseBuffers.remove(key)
        requestParsingStates.remove(key)
        responseParsingStates.remove(key)
        pendingRequests.remove(key)
    }

    internal fun markPendingRequestAnnounced(requestId: String): Boolean =
        announcedPendingRequestIds.add(requestId)

    internal fun formatUrlWithoutQuery(urlObject: Any?): String {
        return try {
            when (urlObject) {
                is URL -> {
                    val decodedPath = URLDecoder.decode(urlObject.path, UTF8.name())
                    val portStr = if (urlObject.port != -1 && urlObject.port != urlObject.defaultPort) ":${urlObject.port}" else ""
                    "${urlObject.protocol}://${urlObject.host}$portStr$decodedPath"
                }
                is Uri -> {
                    val decodedPath = URLDecoder.decode(urlObject.path ?: "", UTF8.name())
                    val port = urlObject.port
                    val host = urlObject.host ?: ""
                    val scheme = urlObject.scheme ?: "http"

                    val portStr = if (port != -1) ":$port" else ""
                    "$scheme://$host$portStr$decodedPath"
                }
                else -> urlObject?.toString() ?: ""
            }
        } catch (e: Exception) {
            XposedBridge.log("$LOG_PREFIX URL format error: ${e.message}")
            urlObject?.toString() ?: ""
        }
    }

    internal fun checkShouldBlockRequest(info: BlockedRequest?): Boolean {
        info ?: return false
        val blockResult = sequenceOf("URL", "Domain", "KeyWord")
            .mapNotNull { type ->
                val value = if (type == "Domain") AppUtils.extractHostOrSelf(info.requestValue) else info.requestValue
                val result = queryContentProvider(type, value)
                if (result.first) result else null
            }
            .firstOrNull()
        blockResult?.let {
            sendBroadcast(info, true, it.second, it.third)
            return true
        }
        sendBroadcast(info, false, null, null)
        return false
    }

    private fun queryContentProvider(queryType: String, queryValue: String): Triple<Boolean, String?, String?> {
        val cacheKey = "$queryType:$queryValue"
        return queryCache.get(cacheKey) {
            try {
                applicationContext.contentResolver.query(
                    URL_CONTENT_URI,
                    arrayOf(Url.URL_TYPE, Url.URL_ADDRESS),
                    null,
                    arrayOf(queryType, queryValue),
                    null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val urlType = cursor.getString(cursor.getColumnIndexOrThrow(Url.URL_TYPE))
                        val urlAddress = cursor.getString(cursor.getColumnIndexOrThrow(Url.URL_ADDRESS))
                        return@get Triple(true, urlType, urlAddress)
                    }
                }
            } catch (e: Exception) {
                XposedBridge.log("$LOG_PREFIX Query error: ${e.message}")
            }
            Triple(false, null, null)
        }
    }

    internal fun processDnsRequest(hostObject: Any?, result: Any?): Boolean {
        val host = hostObject as? String ?: return false
        val stackTrace = HookUtil.getFormattedStackTrace()
        val fullAddress = when (result) {
            is InetAddress -> result.hostAddress
            is Array<*> -> result.filterIsInstance<InetAddress>()
                .joinToString(", ") { it.hostAddress.orEmpty() }
            else -> null
        }
        if (fullAddress.isNullOrEmpty()) return false

        val info = BlockedRequest(
            requestId = nextRequestId("dns"),
            requestType = " DNS",
            requestValue = host,
            method = null,
            urlString = null,
            requestHeaders = null,
            requestBody = null,
            responseCode = -1,
            responseMessage = null,
            responseHeaders = null,
            responseBody = null,
            responseBodyContentType = null,
            stack = stackTrace,
            dnsHost = host,
            fullAddress = fullAddress
        )
        return checkShouldBlockRequest(info)
    }

    internal fun processRequestBuffer(key: Long, isHttps: Boolean) {
        val bufferStream = requestBuffers[key] ?: return
        val state = requestParsingStates.getOrPut(key) { ParsingState() }

        if (state.isHeaderParsed) return

        val buffer = bufferStream.toByteArray()
        val headerEndIndex = findBytes(buffer, headerEndMarker, 0)
        
        if (headerEndIndex != -1) {
            state.isHeaderParsed = true
            state.headerSize = headerEndIndex + headerEndMarker.size
            val headerString = String(buffer, 0, headerEndIndex, StandardCharsets.ISO_8859_1)
            
            if (headerString.startsWith("CONNECT ", ignoreCase = true)) {
                cleanBuffer(bufferStream, buffer, state.headerSize)
                state.isHeaderParsed = false
                return
            }
            
            state.contentLength = parseContentLength(headerString)
            
            if (buffer.size >= state.headerSize + state.contentLength) {
                val bodyBytes = if (state.contentLength > 0) {
                    buffer.copyOfRange(
                        state.headerSize,
                        minOf(state.headerSize + state.contentLength, state.headerSize + MAX_CAPTURED_BODY_BYTES)
                    )
                } else null
                buildHttpRequest(key, headerString, bodyBytes, isHttps)
                
                cleanBuffer(bufferStream, buffer, state.headerSize + state.contentLength)
                state.isHeaderParsed = false
            }
        }
    }

    internal fun processResponseBuffer(key: Long, param: XC_MethodHook.MethodHookParam?): Boolean {
        val bufferStream = responseBuffers[key] ?: return false
        val state = responseParsingStates.getOrPut(key) { ParsingState() }
        val requestInfo = pendingRequests[key] ?: return false

        val buffer = bufferStream.toByteArray()
        val headerEndIndex = findBytes(buffer, headerEndMarker, 0)
        
        if (headerEndIndex != -1 || state.isHeaderParsed) {
            if (!state.isHeaderParsed && headerEndIndex != -1) {
                val headerString = String(buffer, 0, headerEndIndex, StandardCharsets.ISO_8859_1)
                state.isHeaderParsed = true
                state.headerSize = headerEndIndex + headerEndMarker.size
                state.contentLength = parseContentLength(headerString)
                state.isChunked = headerString.contains("Transfer-Encoding: chunked", ignoreCase = true)
            }

            if (!state.isHeaderParsed) return false

            val bodyStartIndex = state.headerSize
            var totalResponseSize = 0
            var bodyBytes: ByteArray? = null
            var isBlocked = false

            val complete = if (state.isChunked) {
                parseChunkedBody(buffer, bodyStartIndex)?.let {
                    totalResponseSize = it.second
                    bodyBytes = if (HookPrefs.getBoolean(HookPrefs.KEY_COLLECT_RESPONSE_BODY, false)) it.first else null
                    true
                } ?: false
            } else {
                totalResponseSize = bodyStartIndex + state.contentLength
                if (buffer.size >= totalResponseSize) {
                    bodyBytes = if (HookPrefs.getBoolean(HookPrefs.KEY_COLLECT_RESPONSE_BODY, false) && state.contentLength > 0) {
                        buffer.copyOfRange(
                            bodyStartIndex,
                            minOf(totalResponseSize, bodyStartIndex + MAX_CAPTURED_BODY_BYTES)
                        )
                    } else null
                    true
                } else false
            }

            if (complete) {
                val headerString = String(buffer, 0, state.headerSize, StandardCharsets.ISO_8859_1)
                isBlocked = completeAndDispatchRequest(key, requestInfo, headerString, bodyBytes, param)
                releaseConnection(key)
            }
            return isBlocked
        }
        return false
    }

    private fun cleanBuffer(stream: ByteArrayOutputStream, buffer: ByteArray, processedSize: Int) {
        stream.reset()
        if (buffer.size > processedSize) {
            stream.write(buffer, processedSize, buffer.size - processedSize)
        }
    }

    private fun buildHttpRequest(key: Long, headers: String, body: ByteArray?, isHttps: Boolean) {
        val lines = headers.lines()
        val requestLine = lines.firstOrNull()?.split(" ") ?: return
        if (requestLine.size < 2) return

        val method = requestLine[0]
        val path = requestLine[1]
        val host = lines.find { it.startsWith("Host:", ignoreCase = true) }?.substring(5)?.trim() ?: return
        val scheme = if (isHttps) "https" else "http"
        val url = "$scheme://$host$path"

        val firstNewline = headers.indexOf("\r\n")
        val cleanedHeaders = if (firstNewline != -1) headers.substring(firstNewline + 2) else headers

        val info = BlockedRequest(
            requestId = nextRequestId(if (isHttps) "https" else "http"),
            requestType = if (isHttps) " HTTPS" else " HTTP", 
            requestValue = formatUrlWithoutQuery(Uri.parse(url)),
            method = method,
            urlString = url,
            requestHeaders = cleanedHeaders,
            requestBody = body,
            responseCode = -1,
            responseMessage = null,
            responseHeaders = null,
            responseBody = null,
            responseBodyContentType = null,
            stack = HookUtil.getFormattedStackTrace(),
            dnsHost = null,
            fullAddress = null
        )
        pendingRequests.put(key, info)?.requestId?.let { announcedPendingRequestIds.remove(it) }
    }

    private fun completeAndDispatchRequest(
        key: Long,
        requestInfo: BlockedRequest,
        headers: String,
        body: ByteArray?,
        param: XC_MethodHook.MethodHookParam?
    ): Boolean {
        val lines = headers.lines()
        val statusLine = lines.firstOrNull()?.split(" ", limit = 3) ?: return false
        val responseCode = statusLine.getOrNull(1)?.toIntOrNull() ?: -1
        val responseMessage = statusLine.getOrNull(2) ?: ""
        val contentType = lines.find { it.startsWith("Content-Type:", ignoreCase = true) }?.substringAfter(':')?.trim()
        val contentEncoding = lines.find { it.startsWith("Content-Encoding:", ignoreCase = true) }?.substringAfter(':')?.trim()

        val mimeTypeForProvider = if (!contentEncoding.isNullOrEmpty()) {
            "$contentType; encoding=$contentEncoding"
        } else {
            contentType
        }

        val firstNewline = headers.indexOf("\r\n")
        val cleanedHeaders = if (firstNewline != -1) headers.substring(firstNewline + 2) else headers

        val finalInfo = requestInfo.copy(
            responseCode = responseCode,
            responseMessage = responseMessage,
            responseHeaders = cleanedHeaders,
            responseBody = body,
            responseBodyContentType = mimeTypeForProvider
        )

        val shouldBlock = checkShouldBlockRequest(finalInfo)
        if (shouldBlock) {
            param?.throwable = IOException("Request blocked by AdClose")
        }
        return shouldBlock
    }

    private fun findBytes(data: ByteArray, pattern: ByteArray, startIndex: Int = 0): Int {
        if (pattern.isEmpty() || data.size < pattern.size + startIndex) return -1
        val skip = IntArray(256) { pattern.size }
        for (i in 0 until pattern.size - 1) {
            skip[pattern[i].toInt() and 0xFF] = pattern.size - 1 - i
        }
        var k = startIndex + pattern.size - 1
        while (k < data.size) {
            var j = pattern.size - 1
            var i = k
            while (j >= 0 && data[i] == pattern[j]) {
                j--
                i--
            }
            if (j == -1) return i + 1
            k += skip[data[k].toInt() and 0xFF]
        }
        return -1
    }

    private fun parseContentLength(headers: String): Int {
        return headers.lines().find { it.startsWith("Content-Length:", ignoreCase = true) }
            ?.substringAfter(':')?.trim()?.toIntOrNull() ?: 0
    }

    private fun parseChunkedBody(buffer: ByteArray, bodyStartIndex: Int): Pair<ByteArray, Int>? {
        val bodyStream = ByteArrayOutputStream()
        var currentIndex = bodyStartIndex
        try {
            while (true) {
                val crlfIndex = findBytes(buffer, "\r\n".toByteArray(), currentIndex)
                if (crlfIndex == -1) return null

                val chunkSizeHex = String(buffer, currentIndex, crlfIndex - currentIndex, Charsets.US_ASCII).trim()
                val chunkSize = chunkSizeHex.toIntOrNull(16) ?: 0

                if (chunkSize == 0) {
                    val finalCrlfIndex = findBytes(buffer, "\r\n".toByteArray(), crlfIndex + 2)
                    if (finalCrlfIndex == -1) return null
                    return bodyStream.toByteArray() to finalCrlfIndex + 2
                }

                val chunkDataStart = crlfIndex + 2
                val chunkDataEnd = chunkDataStart + chunkSize
                if (buffer.size < chunkDataEnd + 2) return null

                val remainingCapture = MAX_CAPTURED_BODY_BYTES - bodyStream.size()
                if (remainingCapture > 0) {
                    bodyStream.write(buffer, chunkDataStart, minOf(chunkSize, remainingCapture))
                }
                currentIndex = chunkDataEnd + 2
            }
        } catch (e: Exception) {
            return null
        }
    }

    private fun sendBroadcast(info: BlockedRequest, shouldBlock: Boolean, blockRuleType: String?, ruleUrl: String?) {
        sendBlockedRequestBroadcast(info, shouldBlock, ruleUrl, blockRuleType)
    }

    private fun sendBlockedRequestBroadcast(info: BlockedRequest, isBlocked: Boolean, ruleUrl: String?, blockRuleType: String?) {
        try {
            var requestBodyUriString: String? = null
            var responseBodyUriString: String? = null

            fun storeBody(body: ByteArray?, contentType: String?): String? {
                if (body == null || body.isEmpty()) return null
                return try {
                    val values = contentValuesOf("body_content" to body, "mime_type" to contentType)
                    applicationContext.contentResolver.insert(TemporaryFileProvider.CONTENT_URI, values)?.toString()
                } catch (e: Exception) {
                    Log.e(LOG_PREFIX, "Error inserting body into provider: ${e.message}", e)
                    null
                }
            }

            requestBodyUriString = storeBody(info.requestBody, "text/plain")
            responseBodyUriString = storeBody(info.responseBody, info.responseBodyContentType)

            val requestInfoForBroadcast = RequestInfo(
                requestId = info.requestId,
                appName = "${applicationContext.applicationInfo.loadLabel(applicationContext.packageManager)}${info.requestType}",
                packageName = applicationContext.packageName,
                request = info.requestValue,
                timestamp = System.currentTimeMillis(),
                requestType = if (isBlocked) "block" else "pass",
                isBlocked = isBlocked,
                url = ruleUrl,
                blockType = blockRuleType,
                method = info.method,
                urlString = info.urlString,
                requestHeaders = info.requestHeaders,
                requestBodyUriString = requestBodyUriString,
                responseCode = info.responseCode,
                responseMessage = info.responseMessage,
                responseHeaders = info.responseHeaders,
                responseBodyUriString = responseBodyUriString,
                responseBodyContentType = info.responseBodyContentType,
                stack = info.stack,
                dnsHost = info.dnsHost,
                fullAddress = info.fullAddress
            )
            Intent("com.rikkati.REQUEST").apply {
                putExtra("request", requestInfoForBroadcast)
                setPackage("com.close.hook.ads")
            }.also {
                applicationContext.sendBroadcast(it)
            }
        } catch (e: Exception) {
            Log.w(LOG_PREFIX, "Broadcast send error.", e)
        }
    }
}
