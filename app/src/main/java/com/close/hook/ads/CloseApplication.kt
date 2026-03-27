package com.close.hook.ads

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import com.close.hook.ads.preference.PrefManager
import com.close.hook.ads.preference.PrefManager.darkTheme
import com.close.hook.ads.manager.ServiceManager
import com.close.hook.ads.rule.RuleSnapshotBuilder
import com.close.hook.ads.util.LocaleUtils
import com.microsoft.appcenter.AppCenter
import com.microsoft.appcenter.analytics.Analytics
import com.microsoft.appcenter.crashes.Crashes
import rikka.material.app.LocaleDelegate
import java.util.Locale

lateinit var closeApp: CloseApplication

class CloseApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        closeApp = this

        ServiceManager.init()
        Thread({
            val builder = RuleSnapshotBuilder.getInstance(this)
            if (!builder.rebuild()) {
                builder.invalidate()
            }
        }, "AdClose-RuleSnapshotInit").apply {
            isDaemon = true
            start()
        }

        initAppCenter()
        AppCompatDelegate.setDefaultNightMode(darkTheme)
        applyLocale(PrefManager.language)
    }

    private fun initAppCenter() {
        AppCenter.start(
            this,
            "621cdb49-4473-44d3-a8f8-e76f28ba43d7",
            Analytics::class.java,
            Crashes::class.java
        )
    }

    fun applyLocale(languageTag: String) {
        val locale = getLocale(languageTag)
        Locale.setDefault(locale)
        LocaleDelegate.defaultLocale = locale
        val config = resources.configuration
        config.setLocale(locale)
        createConfigurationContext(config)
    }

    fun getLocale(tag: String): Locale {
        return if (tag == "SYSTEM") {
            LocaleDelegate.systemLocale
        } else {
            Locale.forLanguageTag(LocaleUtils.normalizeLanguageTag(tag))
        }
    }
}
