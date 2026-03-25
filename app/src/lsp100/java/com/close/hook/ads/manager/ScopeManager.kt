package com.close.hook.ads.manager

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object ScopeManager {

    private const val TAG = "ScopeManager"

    interface ScopeCallback {
        fun onScopeOperationSuccess(message: String)
        fun onScopeOperationFail(message: String)
    }

    suspend fun getScope(): List<String>? = withContext(Dispatchers.IO) {
        val service = ServiceManager.service
        if (service == null) {
            Log.e(TAG, "getScope: LSPosed service not available.")
            return@withContext null
        }
        return@withContext try {
            service.scope
        } catch (e: Exception) {
            Log.e(TAG, "getScope failed", e)
            null
        }
    }

    suspend fun addScope(packageName: String, callback: ScopeCallback) {
        withContext(Dispatchers.Main) {
            val service = ServiceManager.service
            if (service == null) {
                callback.onScopeOperationFail("LSPosed service not available.")
                return@withContext
            }

            val serviceCallback = object : io.github.libxposed.service.XposedService.OnScopeEventListener {
                override fun onScopeRequestApproved(pkg: String) {
                    callback.onScopeOperationSuccess("$pkg enabled successfully.")
                }

                override fun onScopeRequestDenied(pkg: String) {
                    callback.onScopeOperationFail("Request for $pkg was denied.")
                }

                override fun onScopeRequestFailed(pkg: String, message: String) {
                    callback.onScopeOperationFail("Failed to enable $pkg: $message")
                }
            }

            try {
                service.requestScope(packageName, serviceCallback)
            } catch (e: Exception) {
                Log.e(TAG, "addScope failed", e)
                callback.onScopeOperationFail(e.message ?: "Unknown error")
            }
        }
    }

    suspend fun removeScope(packageName: String): String? = withContext(Dispatchers.IO) {
        val service = ServiceManager.service
        if (service == null) {
            Log.e(TAG, "removeScope: LSPosed service not available.")
            return@withContext "LSPosed service not available."
        }
        return@withContext try {
            service.removeScope(packageName)
        } catch (e: Exception) {
            Log.e(TAG, "removeScope failed", e)
            e.message
        }
    }
}
