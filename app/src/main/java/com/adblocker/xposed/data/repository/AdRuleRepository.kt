package com.adblocker.xposed.data.repository

import androidx.lifecycle.LiveData
import com.adblocker.xposed.data.db.*
import com.adblocker.xposed.data.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class AdRuleRepository(
    private val adRuleDao: AdRuleDao
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    fun getAllRules(): LiveData<List<AdRule>> = adRuleDao.getAllRules()
    fun getEnabledRules(): LiveData<List<AdRule>> = adRuleDao.getEnabledRules()
    fun searchRules(query: String): LiveData<List<AdRule>> = adRuleDao.searchRules(query)
    fun getRuleCount(): LiveData<Int> = adRuleDao.getRuleCount()
    fun getSources(): LiveData<List<String>> = adRuleDao.getSources()

    suspend fun insert(rule: AdRule) = withContext(Dispatchers.IO) {
        adRuleDao.insert(rule)
    }

    suspend fun delete(rule: AdRule) = withContext(Dispatchers.IO) {
        adRuleDao.delete(rule)
    }

    suspend fun update(rule: AdRule) = withContext(Dispatchers.IO) {
        adRuleDao.update(rule)
    }

    suspend fun importMosdnsRules(url: String): Int = withContext(Dispatchers.IO) {
        // Remove old mosdns rules first
        adRuleDao.deleteBySource("mosdns")

        val request = Request.Builder().url(url).build()
        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: return@withContext 0

        val rules = mutableListOf<AdRule>()
        body.lines().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isNotEmpty() && !trimmed.startsWith("#") && !trimmed.startsWith("!")) {
                // Parse different rule formats
                val domain = when {
                    // hosts format: 0.0.0.0 ads.example.com
                    trimmed.startsWith("0.0.0.0") || trimmed.startsWith("127.0.0.1") -> {
                        trimmed.split("\\s+".toRegex()).getOrNull(1)
                    }
                    // domain list format
                    trimmed.startsWith("||") -> {
                        trimmed.removePrefix("||").removeSuffix("^").split("/").firstOrNull()
                    }
                    // plain domain
                    !trimmed.contains(" ") && trimmed.contains(".") -> trimmed
                    else -> null
                }

                if (domain != null && domain.contains(".")) {
                    rules.add(AdRule(
                        domain = domain.lowercase(),
                        source = "mosdns",
                        ruleType = "domain"
                    ))
                }
            }
        }

        if (rules.isNotEmpty()) {
            adRuleDao.insertAll(rules)
        }
        rules.size
    }

    suspend fun importHostsRules(url: String): Int = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).build()
        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: return@withContext 0

        val rules = mutableListOf<AdRule>()
        body.lines().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                val parts = trimmed.split("\\s+".toRegex())
                if (parts.size >= 2 && (parts[0] == "0.0.0.0" || parts[0] == "127.0.0.1")) {
                    val domain = parts[1].lowercase()
                    if (domain.contains(".") && domain != "localhost") {
                        rules.add(AdRule(
                            domain = domain,
                            source = "hosts",
                            ruleType = "domain"
                        ))
                    }
                }
            }
        }

        if (rules.isNotEmpty()) {
            adRuleDao.insertAll(rules)
        }
        rules.size
    }

    // Well-known ad-blocking rule sources
    companion object {
        val RULE_SOURCES = mapOf(
            "mosdns_ad" to "https://raw.githubusercontent.com/privacy-protection-tools/anti-AD/master/anti-ad-domains.txt",
            "adguard_dns" to "https://adguardteam.github.io/AdGuardSDNSFilter/Filters/filter.txt",
            "steven_black" to "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts",
            "oisd_small" to "https://small.oisd.nl/",
            "oisd_big" to "https://big.oisd.nl/",
            "hagezi_pro" to "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/adblock/pro.txt",
            "hagezi_multi" to "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/adblock/multi.txt"
        )

        // Known ad SDK patterns
        val AD_SDK_PATTERNS = listOf(
            "ads.google.com", "pagead2.googlesyndication.com",
            "graph.facebook.com", "ads.facebook.com",
            "analytics.twitter.com", "ads.twitter.com",
            "app-measurement.com", "firebase-settings.crashlytics.com",
            "umeng.com", "adashx.ut.taobao.com",
            "ad.doubleclick.net", "adclick.g.doubleclick.net",
            "adsense.google.com", "adserver.",
            "adservice.google.com", "googleads.g.doubleclick.net",
            "adsrvr.org", "adnxs.com",
            "mopub.com", "unityads.unity3d.com",
            "vungle.com", "applovin.com",
            "ironsrc.com", "tapjoy.com",
            "inmobi.com", "chartboost.com",
            "flurry.com", "crashlytics.com",
            "sentry.io", "adjust.com",
            "kochava.com", "branch.io"
        )
    }
}
