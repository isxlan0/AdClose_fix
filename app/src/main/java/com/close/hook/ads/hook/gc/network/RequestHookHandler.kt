package com.close.hook.ads.hook.gc.network

import android.content.Context
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.close.hook.ads.data.model.BlockedRequest
import com.close.hook.ads.hook.util.HookUtil
import com.close.hook.ads.hook.util.StringFinderKit
import com.close.hook.ads.preference.HookPrefs
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.io.ByteArrayInputStream
import java.io.IOException
import java.lang.reflect.Method
import java.net.InetAddress
import java.net.URL
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLEngineResult
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLParameters

internal object RequestHookHandler {

    private const val LOG_PREFIX = "[RequestHookHandler] "
    private lateinit var applicationContext: Context
    private val isInitialized = AtomicBoolean(false)
    private val isOkHttpFallbackScheduled = AtomicBoolean(false)
    private val hookedOkHttpMethods = ConcurrentHashMap.newKeySet<String>()
    private val okHttpRequestIds = ConcurrentHashMap<Int, String>()

    private val okioBufferClass: Class<*>? by lazy {
        try {
            XposedHelpers.findClass("okio.Buffer", applicationContext.classLoader)
        } catch (e: Throwable) {
            XposedBridge.log("$LOG_PREFIX ${e.message}")
            null
        }
    }

    private val emptyWebResponse: WebResourceResponse? by lazy { createEmptyWebResourceResponse() }

    private val cronetHttpURLConnectionCls: Class<*>? by lazy {
        try {
            XposedHelpers.findClass("com.ttnet.org.chromium.net.urlconnection.CronetHttpURLConnection", applicationContext.classLoader)
        } catch (e: Throwable) { null }
    }

    fun init(context: Context) {
        if (isInitialized.get()) return
        applicationContext = context
        if (!isInitialized.compareAndSet(false, true)) return
        setupDNSRequestHook()
        setupSocketHook() // HTTP/1.1
        setupConscryptEngineHook() // HTTPS over HTTP/1.1
        setupOkHttpRequestHook()
        // setupProtocolDowngradeHook()
        setupWebViewRequestHook()
        setupCronetRequestHook() // ByteDance
    }

    private fun handleParsedRequest(key: Long, param: XC_MethodHook.MethodHookParam? = null) {
        val requestInfo = RequestHook.pendingRequests[key] ?: return
        if (!RequestHook.markPendingRequestAnnounced(requestInfo.requestId)) {
            return
        }
        if (RequestHook.checkShouldBlockRequest(requestInfo)) {
            param?.throwable = IOException("Request blocked by AdClose")
            RequestHook.releaseConnection(key)
        }
    }

    private fun setupDNSRequestHook() {
        HookUtil.findAndHookMethod(
            InetAddress::class.java,
            "getByName",
            arrayOf(String::class.java),
            "after"
        ) { param ->
            if (RequestHook.processDnsRequest(param.args[0], param.result)) {
                param.result = null
            }
        }
        HookUtil.findAndHookMethod(
            InetAddress::class.java,
            "getAllByName",
            arrayOf(String::class.java),
            "after"
        ) { param ->
            if (RequestHook.processDnsRequest(param.args[0], param.result)) {
                param.result = emptyArray<InetAddress>()
            }
        }
    }

    private fun setupSocketHook() {
        try {
            HookUtil.hookAllMethods(
                "java.net.SocketOutputStream",
                "socketWrite0",
                "before"
            ) { param ->
                try {
                    val socket = XposedHelpers.getObjectField(param.thisObject, "socket") ?: return@hookAllMethods
                    if (socket is SSLSocket) return@hookAllMethods

                    val bytes = param.args.getOrNull(1) as? ByteArray ?: return@hookAllMethods
                    val offset = param.args.getOrNull(2) as? Int ?: 0
                    val len = param.args.getOrNull(3) as? Int ?: -1
                    if (len <= 0) return@hookAllMethods

                    val key = RequestHook.javaSocketKey(socket)
                    RequestHook.appendToBuffer(
                        key = key,
                        buffers = RequestHook.requestBuffers,
                        bytes = bytes,
                        offset = offset,
                        len = len,
                        bufferLabel = "socket request"
                    ) ?: return@hookAllMethods

                    RequestHook.processRequestBuffer(key, isHttps = false)
                    handleParsedRequest(key, param)
                } catch (e: Throwable) {
                    XposedBridge.log("$LOG_PREFIX socketWrite0 hook error: ${e.message}")
                }
            }

            HookUtil.hookAllMethods(
                "java.net.SocketInputStream",
                "socketRead0",
                "after"
            ) { param ->
                try {
                    val socket = XposedHelpers.getObjectField(param.thisObject, "socket") ?: return@hookAllMethods
                    if (socket is SSLSocket) return@hookAllMethods

                    val bytes = param.args.getOrNull(1) as? ByteArray ?: return@hookAllMethods
                    val offset = param.args.getOrNull(2) as? Int ?: 0
                    val len = param.result as? Int ?: -1
                    if (len <= 0) return@hookAllMethods

                    val key = RequestHook.javaSocketKey(socket)
                    RequestHook.appendToBuffer(
                        key = key,
                        buffers = RequestHook.responseBuffers,
                        bytes = bytes,
                        offset = offset,
                        len = len,
                        bufferLabel = "socket response"
                    ) ?: return@hookAllMethods

                    RequestHook.processResponseBuffer(key, param)
                } catch (e: Throwable) {
                    XposedBridge.log("$LOG_PREFIX socketRead0 hook error: ${e.message}")
                }
            }
        } catch (e: Throwable) {
            XposedBridge.log("$LOG_PREFIX Error setting up plain socket hook: ${e.message}")
        }
    }

    private fun setupProtocolDowngradeHook() {
        try {
            HookUtil.findAndHookMethod(
                SSLParameters::class.java,
                "setApplicationProtocols",
                arrayOf(Array<String>::class.java),
                "before"
            ) { param ->
                val originalProtocols = param.args[0] as? Array<String> ?: return@findAndHookMethod

                val filteredProtocols = originalProtocols.filter {
                    it.equals("http/1.1", ignoreCase = true)
                }

                val newProtocols = if (filteredProtocols.isEmpty() && originalProtocols.isNotEmpty()) {
                    arrayOf("http/1.1")
                } else {
                    filteredProtocols.toTypedArray()
                }

                if (!originalProtocols.contentEquals(newProtocols)) {
                    XposedBridge.log("$LOG_PREFIX Downgrading ALPN protocols from ${originalProtocols.joinToString()} to ${newProtocols.joinToString()}")
                    param.args[0] = newProtocols
                }
            }
        } catch(e: Throwable) {
            XposedBridge.log("$LOG_PREFIX Error setting up protocol downgrade hook: ${e.message}")
        }
    }

    private fun setupConscryptEngineHook() {
        try {
            val conscryptEngineClass = Class.forName("com.android.org.conscrypt.ConscryptEngine")

            HookUtil.findAndHookMethod(
                conscryptEngineClass,
                "wrap",
                arrayOf(ByteBuffer::class.java, ByteBuffer::class.java),
                "before"
            ) { param ->
                try {
                    val srcBuffer = param.args[0] as ByteBuffer
                    if (srcBuffer.hasRemaining()) {
                        val key = RequestHook.javaSslKey(param.thisObject)

                        val position = srcBuffer.position()
                        val bytes = ByteArray(srcBuffer.remaining())
                        srcBuffer.get(bytes)
                        srcBuffer.position(position)

                        RequestHook.appendToBuffer(
                            key = key,
                            buffers = RequestHook.requestBuffers,
                            bytes = bytes,
                            offset = 0,
                            len = bytes.size,
                            bufferLabel = "ssl request"
                        ) ?: return@findAndHookMethod
                        RequestHook.processRequestBuffer(key, isHttps = true)
                        handleParsedRequest(key, param)
                    }
                } catch (e: Throwable) {
                    XposedBridge.log("$LOG_PREFIX ConscryptEngine.wrap hook error: ${e.message}")
                }
            }

            HookUtil.findAndHookMethod(
                conscryptEngineClass,
                "unwrap",
                arrayOf(ByteBuffer::class.java, ByteBuffer::class.java),
                "after"
            ) { param ->
                try {
                    val result = param.result as? SSLEngineResult ?: return@findAndHookMethod
                    val dstBuffer = param.args[1] as ByteBuffer
                    val bytesProduced = result.bytesProduced()

                    if (bytesProduced > 0) {
                        val key = RequestHook.javaSslKey(param.thisObject)

                        val position = dstBuffer.position()
                        val start = position - bytesProduced
                        val bytes = ByteArray(bytesProduced)
                        for (i in 0 until bytesProduced) {
                           bytes[i] = dstBuffer.get(start + i)
                        }

                        RequestHook.appendToBuffer(
                            key = key,
                            buffers = RequestHook.responseBuffers,
                            bytes = bytes,
                            offset = 0,
                            len = bytes.size,
                            bufferLabel = "ssl response"
                        ) ?: return@findAndHookMethod
                        RequestHook.processResponseBuffer(key, param)
                    }
                } catch (e: Throwable) {
                    XposedBridge.log("$LOG_PREFIX ConscryptEngine.unwrap hook error: ${e.message}")
                }
            }
        } catch (e: Throwable) {
            XposedBridge.log("$LOG_PREFIX Error setting up ConscryptEngine hook: ${e.message}")
        }
    }

    fun setupOkHttpRequestHook() {
        hookKnownOkHttpMethod("okhttp3.RealCall", "execute")
        hookKnownOkHttpMethod("okhttp3.internal.http.RetryAndFollowUpInterceptor", "intercept")
        scheduleOkHttpFallbackHooking()
    }

    private fun hookKnownOkHttpMethod(className: String, methodName: String) {
        val clazz = XposedHelpers.findClassIfExists(className, applicationContext.classLoader) ?: return
        clazz.declaredMethods
            .filter { it.name == methodName }
            .forEach { hookOkHttpMethod(it, className) }
    }

    private fun scheduleOkHttpFallbackHooking() {
        if (!isOkHttpFallbackScheduled.compareAndSet(false, true)) return

        Thread({
            try {
                hookOkHttpMethodsByString("setupOkHttpRequestHook_execute", "Already Executed", "execute")
                hookOkHttpMethodsByString("setupOkHttp2RequestHook_intercept", "Canceled", "intercept")
            } catch (e: Throwable) {
                XposedBridge.log("$LOG_PREFIX OkHttp fallback hook scheduling failed: ${e.message}")
            }
        }, "AdClose-OkHttpHook").apply {
            isDaemon = true
            start()
        }
    }

    private fun hookOkHttpMethodsByString(cacheKeySuffix: String, methodDescription: String, methodName: String) {
        val cacheKey = "${applicationContext.packageName}:$cacheKeySuffix"
        StringFinderKit.findMethodsWithString(cacheKey, methodDescription, methodName)?.forEach { methodData ->
            try {
                hookOkHttpMethod(methodData.getMethodInstance(applicationContext.classLoader), "DexKit:$cacheKeySuffix")
            } catch (e: Exception) {
                XposedBridge.log("$LOG_PREFIX Error hooking OkHttp method: $methodData, ${e.message}")
            }
        }
    }

    private fun hookOkHttpMethod(method: Method, source: String) {
        val signature = method.toGenericString()
        if (!hookedOkHttpMethods.add(signature)) {
            return
        }

        XposedBridge.log("$LOG_PREFIX Hooked OkHttp method from $source: $signature")
        HookUtil.hookMethod(method, "after") { param ->
            handleOkHttpHook(method.name, param)
        }
    }

    private fun handleOkHttpHook(methodName: String, param: XC_MethodHook.MethodHookParam) {
        try {
            val response = param.result ?: return
            val request = XposedHelpers.callMethod(response, "request")
            val url = URL(XposedHelpers.callMethod(request, "url").toString())
            val stackTrace = HookUtil.getFormattedStackTrace()
            val requestBodyBytes = readOkHttpRequestBody(request)
            val (responseBodyBytes, mimeTypeWithEncoding) = readOkHttpResponseBody(response)

            val info = buildOkHttpRequest(
                url = url,
                requestFrameworkType = " OKHTTP",
                request = request,
                response = response,
                requestBody = requestBodyBytes,
                responseBody = responseBodyBytes,
                responseBodyContentType = mimeTypeWithEncoding,
                stack = stackTrace
            )

            if (RequestHook.checkShouldBlockRequest(info)) {
                param.throwable = IOException("Request blocked by AdClose: ${url.host}")
            }
        } catch (e: IOException) {
            param.throwable = e
        } catch (e: Throwable) {
            XposedBridge.log("$LOG_PREFIX OkHttp hook error ($methodName): ${e.message}")
        }
    }

    private fun readOkHttpRequestBody(request: Any): ByteArray? {
        return try {
            XposedHelpers.callMethod(request, "body")?.let { requestBody ->
                val bufferClass = okioBufferClass ?: return@let null
                val bufferInstance = bufferClass.getDeclaredConstructor().newInstance()
                XposedHelpers.callMethod(requestBody, "writeTo", bufferInstance)
                val size = XposedHelpers.callMethod(bufferInstance, "size") as? Long ?: 0L
                when {
                    size <= 0L -> null
                    size > RequestHook.MAX_CAPTURED_BODY_BYTES.toLong() ->
                        XposedHelpers.callMethod(
                            bufferInstance,
                            "readByteArray",
                            RequestHook.MAX_CAPTURED_BODY_BYTES.toLong()
                        ) as? ByteArray
                    else -> XposedHelpers.callMethod(bufferInstance, "readByteArray") as? ByteArray
                }
            }
        } catch (e: Throwable) {
            null
        }
    }

    private fun readOkHttpResponseBody(response: Any): Pair<ByteArray?, String?> {
        if (!HookPrefs.getBoolean(HookPrefs.KEY_COLLECT_RESPONSE_BODY, false)) {
            return null to null
        }

        return try {
            val originalBody = XposedHelpers.callMethod(response, "body") ?: return null to null
            val mediaType = XposedHelpers.callMethod(originalBody, "contentType")
            val responseContentType = mediaType?.toString()
            val peekedBody = XposedHelpers.callMethod(
                response,
                "peekBody",
                RequestHook.MAX_CAPTURED_BODY_BYTES.toLong()
            )
            val responseBodyBytes = XposedHelpers.callMethod(peekedBody, "bytes") as? ByteArray
            val contentEncoding = XposedHelpers.callMethod(response, "header", "Content-Encoding") as? String

            responseBodyBytes to if (!contentEncoding.isNullOrEmpty()) {
                "$responseContentType; encoding=$contentEncoding"
            } else {
                responseContentType
            }
        } catch (e: Throwable) {
            XposedBridge.log("$LOG_PREFIX OkHttp response body reading failed: ${e.message}")
            null to null
        }
    }

    private fun getOkHttpRequestId(request: Any): String {
        if (okHttpRequestIds.size > 4096) {
            okHttpRequestIds.clear()
        }
        val requestKey = System.identityHashCode(request)
        return okHttpRequestIds.getOrPut(requestKey) { RequestHook.nextRequestId("okhttp") }
    }

    private fun buildOkHttpRequest(
        url: URL, requestFrameworkType: String, request: Any, response: Any,
        requestBody: ByteArray?,
        responseBody: ByteArray?, responseBodyContentType: String?, stack: String
    ): BlockedRequest? {
        return try {
            val method = XposedHelpers.callMethod(request, "method") as? String
            val urlString = url.toString()
            val requestHeaders = XposedHelpers.callMethod(request, "headers")?.toString()
            val code = XposedHelpers.callMethod(response, "code") as? Int ?: -1
            val message = XposedHelpers.callMethod(response, "message") as? String
            val responseHeaders = XposedHelpers.callMethod(response, "headers")?.toString()
            val formattedUrl = RequestHook.formatUrlWithoutQuery(url)
            BlockedRequest(
                requestId = getOkHttpRequestId(request),
                requestType = requestFrameworkType,
                requestValue = formattedUrl,
                method = method,
                urlString = urlString,
                requestHeaders = requestHeaders,
                requestBody = requestBody,
                responseCode = code,
                responseMessage = message,
                responseHeaders = responseHeaders,
                responseBody = responseBody,
                responseBodyContentType = responseBodyContentType,
                stack = stack,
                dnsHost = null,
                fullAddress = null
            )
        } catch (e: Exception) {
            XposedBridge.log("$LOG_PREFIX buildOkHttpRequest error: ${e.message}")
            null
        }
    }

    private fun setupWebViewRequestHook() {
        HookUtil.findAndHookMethod(
            WebView::class.java,
            "setWebViewClient",
            arrayOf(WebViewClient::class.java),
            "before"
        ) { param ->
            param.args[0]?.let {
                hookClientMethods(it.javaClass.name, applicationContext.classLoader)
            }
        }
    }

    private fun hookClientMethods(clientClassName: String, classLoader: ClassLoader) {
        XposedBridge.log("$LOG_PREFIX WebViewClient set: $clientClassName")

        HookUtil.hookAllMethods(
            clientClassName,
            "shouldInterceptRequest",
            "before",
            { param ->
                if (param.args.size != 2) return@hookAllMethods
                val request = param.args[1] as? WebResourceRequest ?: return@hookAllMethods
                
                if (processWebRequest(request)) {
                    param.result = emptyWebResponse
                }
            },
            classLoader
        )

        HookUtil.hookAllMethods(
            clientClassName,
            "shouldOverrideUrlLoading",
            "before",
            { param ->
                if (param.args.size != 2) return@hookAllMethods
                if (processWebRequest(param.args[1])) {
                    param.result = true
                }
            },
            classLoader
        )
    }

    private fun processWebRequest(request: Any?): Boolean {
        try {
            val webResourceRequest = request as? WebResourceRequest ?: return false
            
            if (webResourceRequest.requestHeaders["X-AdClose-Proxy"] == "true") {
                return false
            }

            val urlString = webResourceRequest.url?.toString() ?: return false
            val formattedUrl = RequestHook.formatUrlWithoutQuery(Uri.parse(urlString))
            
            val info = BlockedRequest(
                requestId = RequestHook.nextRequestId("web"),
                requestType = " Web",
                requestValue = formattedUrl,
                method = webResourceRequest.method,
                urlString = urlString,
                requestHeaders = webResourceRequest.requestHeaders.toString(),
                requestBody = null,
                responseCode = -1,
                responseMessage = null,
                responseHeaders = null,
                responseBody = null,
                responseBodyContentType = null,
                stack = HookUtil.getFormattedStackTrace(),
                dnsHost = null,
                fullAddress = null
            )
            return RequestHook.checkShouldBlockRequest(info)
        } catch (e: Exception) {
            XposedBridge.log("$LOG_PREFIX Web request error: ${e.message}")
        }
        return false
    }

    private fun createEmptyWebResourceResponse(): WebResourceResponse? {
        return try {
            WebResourceResponse("text/plain", "UTF-8", 204, "No Content",
                emptyMap(), ByteArrayInputStream(ByteArray(0))
            )
        } catch (e: Exception) {
            XposedBridge.log("$LOG_PREFIX Empty response error: ${e.message}")
            null
        }
    }

    private fun readCronetBufferedBody(streamObject: Any?): ByteArray? {
        if (!HookPrefs.getBoolean(HookPrefs.KEY_COLLECT_RESPONSE_BODY, false)) {
            return null
        }

        return try {
            val buffer = XposedHelpers.getObjectField(streamObject, "mBuffer") as? ByteBuffer ?: return null
            val duplicate = buffer.asReadOnlyBuffer()
            val readable = when {
                duplicate.position() > 0 -> duplicate.position()
                duplicate.limit() > 0 -> duplicate.limit()
                else -> duplicate.remaining()
            }.coerceAtMost(RequestHook.MAX_CAPTURED_BODY_BYTES)

            if (readable <= 0) {
                return null
            }

            duplicate.position(0)
            duplicate.limit(readable)
            ByteArray(readable).also { duplicate.get(it) }
        } catch (_: Throwable) {
            null
        }
    }

    private fun setupCronetRequestHook() {
        try {
            HookUtil.hookAllMethods(
                cronetHttpURLConnectionCls,
                "getResponse",
                "after"
            ) { param ->
                try {
                    val thisObject = param.thisObject

                    val requestObject = XposedHelpers.getObjectField(thisObject, "mRequest")
                    val responseInfoObject = XposedHelpers.getObjectField(thisObject, "mResponseInfo")

                    if (requestObject == null || responseInfoObject == null) return@hookAllMethods

                    val method = XposedHelpers.getObjectField(requestObject, "mInitialMethod") as? String
                    val requestHeaders = XposedHelpers.getObjectField(requestObject, "mRequestHeaders")?.toString()

                    val finalUrl = XposedHelpers.callMethod(responseInfoObject, "getUrl") as? String
                    val httpStatusCode = XposedHelpers.callMethod(responseInfoObject, "getHttpStatusCode") as? Int ?: -1
                    val httpStatusText = XposedHelpers.callMethod(responseInfoObject, "getHttpStatusText") as? String
                    val responseHeadersMap = XposedHelpers.callMethod(responseInfoObject, "getAllHeaders") as? Map<String, List<String>>
                    val responseHeaders = responseHeadersMap?.toString() ?: ""
                    val negotiatedProtocol = XposedHelpers.callMethod(responseInfoObject, "getNegotiatedProtocol") as? String
                    
                    val responseContentType = responseHeadersMap?.entries
                        ?.firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }
                        ?.value
                        ?.firstOrNull()
                    val responseBody = if (httpStatusCode in 200..399) {
                        readCronetBufferedBody(XposedHelpers.getObjectField(thisObject, "mInputStream"))
                    } else {
                        null
                    }

                    val stackTrace = HookUtil.getFormattedStackTrace()
                    val formattedUrl = finalUrl?.let { RequestHook.formatUrlWithoutQuery(Uri.parse(it)) }

                    val info = BlockedRequest(
                        requestId = RequestHook.nextRequestId("cronet"),
                        requestType = " CRONET/$negotiatedProtocol",
                        requestValue = formattedUrl ?: "",
                        method = method,
                        urlString = finalUrl,
                        requestHeaders = requestHeaders,
                        requestBody = null,
                        responseCode = httpStatusCode,
                        responseMessage = httpStatusText,
                        responseHeaders = responseHeaders,
                        responseBody = responseBody,
                        responseBodyContentType = responseContentType,
                        stack = stackTrace,
                        dnsHost = null,
                        fullAddress = null
                    )

                    if (RequestHook.checkShouldBlockRequest(info)) {
                        param.throwable = IOException("Request blocked by AdClose (Cronet)")
                    }
                } catch (e: Throwable) {
                    XposedBridge.log("$LOG_PREFIX Error in Cronet hook: ${e.message}")
                }
            }
        } catch (e: Throwable) {
            XposedBridge.log("$LOG_PREFIX Error setting up Cronet hook: ${e.message}")
        }
    }
}
