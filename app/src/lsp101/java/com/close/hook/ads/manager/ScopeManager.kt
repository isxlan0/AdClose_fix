package com.close.hook.ads.manager

import android.util.Log
import io.github.libxposed.service.XposedService
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

            val requestedPackages = listOf(packageName)
            val serviceCallback = object : XposedService.OnScopeEventListener {
                override fun onScopeRequestApproved(packages: List<String>) {
                    val message = if (packageName in packages) {
                        "$packageName enabled successfully."
                    } else {
                        "${packages.joinToString()} enabled successfully."
                    }
                    callback.onScopeOperationSuccess(message)
                }

                override fun onScopeRequestFailed(message: String) {
                    callback.onScopeOperationFail("Failed to enable $packageName: $message")
                }
            }

            try {
                service.requestScope(requestedPackages, serviceCallback)
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
            service.removeScope(listOf(packageName))
            null
        } catch (e: Exception) {
            Log.e(TAG, "removeScope failed", e)
            e.message
        }
    }
}
