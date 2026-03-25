package com.close.hook.ads.manager

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.close.hook.ads.preference.HookPrefs
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean

object ServiceManager {

    private const val TAG = "ServiceManager"
    private const val BIND_TIMEOUT_MS = 3_000L

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Connecting)
    val connectionState = _connectionState.asStateFlow()

    private val isInitialized = AtomicBoolean(false)
    private val hasSuccessfulBinding = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())

    @JvmStatic
    val service: XposedService?
        get() = (connectionState.value as? ConnectionState.Connected)?.service

    @JvmStatic
    val isModuleActivated: Boolean
        get() = hasSuccessfulBinding.get() || connectionState.value is ConnectionState.Connected

    @JvmStatic
    val isServiceConnected: Boolean
        get() = connectionState.value is ConnectionState.Connected

    fun init() {
        if (!isInitialized.compareAndSet(false, true)) {
            return
        }

        mainHandler.postDelayed({
            if (!hasSuccessfulBinding.get() && !isServiceConnected && connectionState.value is ConnectionState.Connecting) {
                _connectionState.value = ConnectionState.Disconnected
            }
        }, BIND_TIMEOUT_MS)

        val listener = object : XposedServiceHelper.OnServiceListener {
            override fun onServiceBind(boundService: XposedService) {
                if (isServiceConnected) {
                    Log.w(TAG, "Another Xposed service tried to connect: ${boundService.frameworkName}. Ignoring.")
                    return
                }

                Log.i(TAG, "LSPosed service connected: ${boundService.frameworkName} v${boundService.frameworkVersion}")
                hasSuccessfulBinding.set(true)
                _connectionState.value = ConnectionState.Connected(boundService)
                HookPrefs.syncLocalCacheToRemote()
            }

            override fun onServiceDied(deadService: XposedService) {
                if (service == deadService) {
                    Log.w(TAG, "LSPosed service died.")
                    _connectionState.value = ConnectionState.Disconnected
                }
            }
        }
        
        XposedServiceHelper.registerListener(listener)
        Log.i(TAG, "ServiceManager initialized and listener registered.")
    }
}

sealed interface ConnectionState {
    data object Connecting : ConnectionState
    data class Connected(val service: XposedService) : ConnectionState
    data object Disconnected : ConnectionState
}
