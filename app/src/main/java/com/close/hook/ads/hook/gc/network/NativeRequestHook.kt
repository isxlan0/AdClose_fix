package com.close.hook.ads.hook.gc.network

import com.close.hook.ads.hook.util.HookUtil
import de.robv.android.xposed.XposedBridge

object NativeRequestHook {

    private const val LOG_PREFIX = "[NativeRequestHook] "
    private var isInitialized = false

    private val JAVA_NET_KEYWORDS = setOf(
        "libjavacrypto.so",
        "libopenjdk.so",
        "SocketOutputStream_socketWrite0",
        "NET_Send",
        "NET_Read"
    )

    fun init() {
        if (isInitialized) return
        try {
            System.loadLibrary("native_hook")
            initNativeHook()
            isInitialized = true
            XposedBridge.log("$LOG_PREFIX Native hook initialized successfully.")
        } catch (e: Throwable) {
            XposedBridge.log("$LOG_PREFIX Failed to load native library: ${e.message}")
        }
    }

    private external fun initNativeHook()

    @JvmStatic
    fun onNativeData(
        id: Long,
        isWrite: Boolean,
        data: ByteArray?,
        address: String?,
        stack: String?,
        isSSL: Boolean
    ): Boolean {
        if (data == null || data.isEmpty()) return false

        val key = RequestHook.nativeKey(id, isSSL)
        
        var shouldBlock = false

        if (isWrite) {
            try {
                RequestHook.appendToBuffer(
                    key = key,
                    buffers = RequestHook.requestBuffers,
                    bytes = data,
                    offset = 0,
                    len = data.size,
                    bufferLabel = "native request"
                ) ?: return false
                RequestHook.processRequestBuffer(key, isSSL)
                
                val requestInfo = RequestHook.pendingRequests[key]
                if (requestInfo != null) {
                    
                    val javaStack = HookUtil.getFormattedStackTrace()
                    
                    val isFromJavaNetworking = stack?.let { nativeStack ->
                        JAVA_NET_KEYWORDS.any { keyword -> nativeStack.contains(keyword) }
                    } ?: false

                    val finalStack = if (isFromJavaNetworking) {
                        javaStack
                    } else {
                        stack ?: javaStack
                    }
                    
                    val enrichedInfo = requestInfo.copy(
                        requestType = if (isSSL) " NATIVE-SSL" else " NATIVE-TCP",
                        fullAddress = address ?: requestInfo.fullAddress,
                        stack = finalStack
                    )
                    
                    RequestHook.pendingRequests[key] = enrichedInfo
                    if (!RequestHook.markPendingRequestAnnounced(enrichedInfo.requestId)) {
                        return shouldBlock
                    }

                    shouldBlock = RequestHook.checkShouldBlockRequest(enrichedInfo)
                    if (shouldBlock) {
                        RequestHook.releaseConnection(key)
                    }
                }
            } catch (e: Exception) {
                XposedBridge.log("$LOG_PREFIX Error processing request buffer: ${e.message}")
            }
        } else {
            try {
                RequestHook.appendToBuffer(
                    key = key,
                    buffers = RequestHook.responseBuffers,
                    bytes = data,
                    offset = 0,
                    len = data.size,
                    bufferLabel = "native response"
                ) ?: return false
                if (RequestHook.processResponseBuffer(key, null)) {
                    shouldBlock = true
                }
            } catch (e: Exception) {
                XposedBridge.log("$LOG_PREFIX Error processing response buffer: ${e.message}")
            }
        }
        
        return shouldBlock
    }
}
