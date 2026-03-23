package com.adblocker.xposed

import android.app.Application
import android.content.Context
import com.adblocker.xposed.data.db.AppDatabase
import com.adblocker.xposed.data.repository.AdRuleRepository

class App : Application() {

    companion object {
        lateinit var instance: App
            private set

        const val PREFS_NAME = "adblocker_prefs"
        const val KEY_ENABLED = "enabled"
        const val KEY_KEYWORDS = "keywords"
        const val KEY_MOSDNS_ENABLED = "mosdns_enabled"
        const val KEY_MOSDNS_URL = "mosdns_url"
        const val KEY_CAPTURE_ENABLED = "capture_enabled"
        const val KEY_RULE_UPDATE_INTERVAL = "rule_update_interval"
    }

    val database: AppDatabase by lazy { AppDatabase.getInstance(this) }
    val repository: AdRuleRepository by lazy { AdRuleRepository(database.adRuleDao()) }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    fun prefs() = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
