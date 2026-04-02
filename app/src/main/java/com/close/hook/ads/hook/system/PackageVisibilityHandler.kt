package com.close.hook.ads.hook.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.close.hook.ads.preference.HookPrefs
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Field
import java.lang.reflect.Method

object PackageVisibilityHandler {

    private const val TAG = "PkgVisHandler"
    private const val PERM_QUERY_ALL = "android.permission.QUERY_ALL_PACKAGES"
    private const val ACTION_UPDATE = "com.close.hook.ads.ACTION_UPDATE_PKG_VISIBILITY"

    private val enablePrefixes = arrayOf(
        "switch_one_",
        "switch_two_",
        "switch_three_",
        "switch_four_",
        "switch_five_",
        "switch_six_",
        "switch_seven_",
        "switch_eight_",
        "switch_nine_",
        "overall_hook_enabled_"
    )

    private val threadWakeLock = Object()

    @Volatile
    private var cachedSystemContext: Context? = null

    fun init(xposed: XposedInterface) {
        xposed.log(Log.INFO, TAG, "Initializing package visibility handler")

        Thread {
            var appsFilterInstance: Any? = null
            var disabledPackagesInstance: Any? = null
            var cacheEnabledField: Field? = null
            var addMethod: Method? = null
            var clearMethod: Method? = null

            var lastTargetPackages: Set<String> = emptySet()
            var lastFeatureEnabled: Boolean? = null
            var isReceiverRegistered = false

            val updateReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    if (intent?.action == ACTION_UPDATE) {
                        xposed.log(Log.DEBUG, TAG, "Received package visibility update signal")
                        synchronized(threadWakeLock) {
                            threadWakeLock.notifyAll()
                        }
                    }
                }
            }

            while (true) {
                try {
                    if (!isReceiverRegistered) {
                        getSystemContext()?.let { systemContext ->
                            try {
                                val filter = IntentFilter(ACTION_UPDATE)
                                if (Build.VERSION.SDK_INT >= 33) {
                                    systemContext.javaClass.getMethod(
                                        "registerReceiver",
                                        BroadcastReceiver::class.java,
                                        IntentFilter::class.java,
                                        Int::class.javaPrimitiveType
                                    ).invoke(systemContext, updateReceiver, filter, 2)
                                } else {
                                    systemContext.registerReceiver(updateReceiver, filter)
                                }
                                isReceiverRegistered = true
                                xposed.log(Log.INFO, TAG, "Registered package visibility update receiver")
                            } catch (e: Throwable) {
                                xposed.log(Log.WARN, TAG, "Failed to register receiver: ${e.message}")
                            }
                        }
                    }

                    if (appsFilterInstance == null) {
                        val binder = getService("package")
                        val packageManagerService = binder?.let(::extractPackageManagerService)
                        if (packageManagerService != null) {
                            val appsFilter = getFieldValue(packageManagerService, "mAppsFilter")
                            val featureConfig = appsFilter?.let { getFieldValue(it, "mFeatureConfig") }
                            if (appsFilter != null && featureConfig != null) {
                                appsFilterInstance = appsFilter
                                cacheEnabledField = findField(appsFilter.javaClass, "mCacheEnabled")
                                disabledPackagesInstance = getFieldValue(featureConfig, "mDisabledPackages")

                                if (disabledPackagesInstance != null) {
                                    val arraySetClass = Class.forName("android.util.ArraySet")
                                    addMethod = arraySetClass.getMethod("add", Object::class.java)
                                    clearMethod = arraySetClass.getMethod("clear")
                                }

                                xposed.log(Log.INFO, TAG, "Package visibility bypass is ready")
                            }
                        }

                        if (appsFilterInstance == null) {
                            Thread.sleep(10_000L)
                            continue
                        }
                    }

                    HookPrefs.invalidateCaches()
                    val isFeatureEnabled = HookPrefs.getBoolean(
                        HookPrefs.KEY_ENABLE_PACKAGE_VISIBILITY_BYPASS,
                        false
                    )

                    if (isFeatureEnabled) {
                        cacheEnabledField?.let { field ->
                            if (field.get(appsFilterInstance) as? Boolean != false) {
                                field.set(appsFilterInstance, false)
                            }
                        }

                        val targetPackages = buildTargetPackages()
                        if (lastFeatureEnabled != true || targetPackages != lastTargetPackages) {
                            disabledPackagesInstance?.let { disabledPackages ->
                                synchronized(disabledPackages) {
                                    clearMethod?.invoke(disabledPackages)
                                    targetPackages.forEach { pkg ->
                                        addMethod?.invoke(disabledPackages, pkg)
                                    }
                                }
                                lastTargetPackages = targetPackages
                                xposed.log(
                                    Log.INFO,
                                    TAG,
                                    "Updated package visibility whitelist: ${targetPackages.joinToString()}"
                                )
                            }
                        }
                    } else if (lastFeatureEnabled != false) {
                        cacheEnabledField?.let { field ->
                            if (field.get(appsFilterInstance) as? Boolean == false) {
                                field.set(appsFilterInstance, true)
                                disabledPackagesInstance?.let { disabledPackages ->
                                    synchronized(disabledPackages) {
                                        clearMethod?.invoke(disabledPackages)
                                    }
                                }
                                lastTargetPackages = emptySet()
                                xposed.log(Log.INFO, TAG, "Package visibility bypass disabled")
                            }
                        }
                    }

                    lastFeatureEnabled = isFeatureEnabled
                    if (isReceiverRegistered) {
                        synchronized(threadWakeLock) {
                            threadWakeLock.wait(60_000L)
                        }
                    } else {
                        Thread.sleep(5_000L)
                    }
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                } catch (e: Throwable) {
                    xposed.log(Log.ERROR, TAG, Log.getStackTraceString(e))
                    if (e is ReflectiveOperationException) {
                        appsFilterInstance = null
                    }
                    Thread.sleep(10_000L)
                }
            }
        }.apply {
            isDaemon = true
            name = "AdClose-PkgVisDaemon"
            start()
        }
    }

    private fun buildTargetPackages(): Set<String> {
        val allPrefs = HookPrefs.getAll()
        val systemPm = getSystemContext()?.packageManager
        val rawPackages = mutableSetOf<String>()

        allPrefs.forEach { (key, value) ->
            if (value == true) {
                for (prefix in enablePrefixes) {
                    if (key.startsWith(prefix)) {
                        rawPackages.add(key.substring(prefix.length))
                        break
                    }
                }
            }
        }

        return if (systemPm != null) {
            rawPackages.filterTo(mutableSetOf()) { pkg ->
                systemPm.checkPermission(PERM_QUERY_ALL, pkg) != PackageManager.PERMISSION_GRANTED
            }
        } else {
            rawPackages
        }
    }

    private fun getSystemContext(): Context? {
        cachedSystemContext?.let { return it }
        return try {
            val activityThreadClass = Class.forName("android.app.ActivityThread")
            val activityThread = activityThreadClass.getMethod("currentActivityThread").invoke(null)
            val context = activityThreadClass.getMethod("getSystemContext").invoke(activityThread) as? Context
            cachedSystemContext = context
            context
        } catch (_: Exception) {
            null
        }
    }

    private fun getService(name: String): IBinder? {
        return try {
            val serviceManagerClass = Class.forName("android.os.ServiceManager")
            val method = serviceManagerClass.getDeclaredMethod("getService", String::class.java)
            method.invoke(null, name) as? IBinder
        } catch (_: Exception) {
            null
        }
    }

    private fun extractPackageManagerService(binder: IBinder): Any? {
        val className = binder.javaClass.name
        if (className.contains("PackageManagerService") && !className.contains("IPackageManagerImpl")) {
            return binder
        }
        return getFieldValue(binder, "mService") ?: getFieldValue(binder, "this\$0")
    }

    private fun getFieldValue(obj: Any, fieldName: String): Any? {
        val field = findField(obj.javaClass, fieldName) ?: return null
        return try {
            field.get(obj)
        } catch (_: Exception) {
            null
        }
    }

    private fun findField(clazz: Class<*>, fieldName: String): Field? {
        var current: Class<*>? = clazz
        while (current != null && current != Any::class.java) {
            try {
                return current.getDeclaredField(fieldName).also { it.isAccessible = true }
            } catch (_: NoSuchFieldException) {
                current = current.superclass
            }
        }
        return null
    }
}
