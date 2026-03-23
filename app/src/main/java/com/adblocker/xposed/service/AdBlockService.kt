package com.adblocker.xposed.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.adblocker.xposed.App
import com.adblocker.xposed.R
import com.adblocker.xposed.data.model.ScanResult
import com.adblocker.xposed.data.repository.AdRuleRepository
import com.adblocker.xposed.ui.MainActivity
import kotlinx.coroutines.*

class AdBlockService : Service() {

    companion object {
        const val CHANNEL_ID = "adblocker_service"
        const val NOTIFICATION_ID = 1001
        const val ACTION_SCAN = "action_scan"
        const val ACTION_IMPORT_RULES = "action_import_rules"
        const val EXTRA_URL = "extra_url"
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val pm: PackageManager by lazy { packageManager }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification("广告屏蔽运行中")
        startForeground(NOTIFICATION_ID, notification)

        when (intent?.action) {
            ACTION_SCAN -> {
                val packages = intent.getStringArrayExtra("packages")?.toList()
                if (packages != null) {
                    startScan(packages)
                }
            }
            ACTION_IMPORT_RULES -> {
                val url = intent.getStringExtra(EXTRA_URL)
                if (url != null) {
                    importRules(url)
                }
            }
        }

        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "广告屏蔽服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "广告屏蔽后台服务"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AdBlocker Xposed")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun startScan(packages: List<String>) {
        scope.launch {
            val dao = App.instance.database.scanResultDao()
            val adPatterns = AdRuleRepository.AD_SDK_PATTERNS

            packages.forEachIndexed { index, pkg ->
                updateNotification("正在扫描: ${index + 1}/${packages.size}")

                try {
                    val appInfo = pm.getApplicationInfo(pkg, 0)
                    val appName = pm.getApplicationLabel(appInfo).toString()

                    // Scan package files for ad SDK traces
                    val results = scanPackageForAds(pkg, appName, adPatterns)
                    if (results.isNotEmpty()) {
                        dao.insertAll(results)
                    }
                } catch (_: Exception) {}
            }

            updateNotification("扫描完成: ${packages.size} 个应用")
            delay(3000)
            stopSelf()
        }
    }

    private suspend fun scanPackageForAds(
        packageName: String,
        appName: String,
        patterns: List<String>
    ): List<ScanResult> {
        val results = mutableListOf<ScanResult>()

        try {
            val appInfo = pm.getApplicationInfo(packageName, 0)
            val appDir = appInfo.sourceDir
            val apkFile = java.io.File(appDir)

            if (!apkFile.exists()) return results

            val foundDomains = mutableSetOf<String>()

            // Scan APK file binary data for ad network patterns (replaces `strings` command)
            try {
                val minLen = 8
                val buffer = ByteArray(1024 * 1024) // 1MB read buffer
                apkFile.inputStream().buffered().use { fis ->
                    var bytesRead: Int
                    while (fis.read(buffer).also { bytesRead = it } > 0) {
                        // Extract printable ASCII sequences like `strings` command
                        val sb = StringBuilder()
                        for (i in 0 until bytesRead) {
                            val b = buffer[i].toInt() and 0xFF
                            if (b in 0x20..0x7E) {
                                sb.append(b.toChar())
                            } else {
                                if (sb.length >= minLen) {
                                    val line = sb.toString()
                                    for (pattern in patterns) {
                                        if (line.contains(pattern, ignoreCase = true)) {
                                            val domainMatch = Regex("([a-zA-Z0-9.-]+\\.${Regex.escape(pattern.split(".").takeLast(2).joinToString("."))})")
                                                .find(line)
                                            val domain = domainMatch?.value ?: pattern
                                            if (foundDomains.add(domain)) {
                                                val sdk = identifyAdSdk(domain)
                                                results.add(ScanResult(
                                                    packageName = packageName,
                                                    appName = appName,
                                                    adDomain = domain,
                                                    adSdk = sdk
                                                ))
                                            }
                                        }
                                    }
                                }
                                sb.clear()
                            }
                        }
                        // Handle trailing sequence
                        if (sb.length >= minLen) {
                            val line = sb.toString()
                            for (pattern in patterns) {
                                if (line.contains(pattern, ignoreCase = true)) {
                                    val domainMatch = Regex("([a-zA-Z0-9.-]+\\.${Regex.escape(pattern.split(".").takeLast(2).joinToString("."))})")
                                        .find(line)
                                    val domain = domainMatch?.value ?: pattern
                                    if (foundDomains.add(domain)) {
                                        val sdk = identifyAdSdk(domain)
                                        results.add(ScanResult(
                                            packageName = packageName,
                                            appName = appName,
                                            adDomain = domain,
                                            adSdk = sdk
                                        ))
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (_: Exception) {}

            // Also check lib directory for known SDK libraries
            val libDir = java.io.File(appInfo.nativeLibraryDir ?: "")
            if (libDir.exists()) {
                val libs = libDir.listFiles() ?: emptyArray()
                for (lib in libs) {
                    val libName = lib.name.lowercase()
                    val sdk = when {
                        libName.contains("libmsc") -> "Mintegral"
                        libName.contains("libsgmain") -> "Alibaba"
                        libName.contains("libtbs") -> "TBS/X5"
                        libName.contains("libweibosdk") -> "Weibo"
                        libName.contains("libwcrash") -> "CrashReport"
                        libName.contains("libamap") -> "Amap/Gaode"
                        libName.contains("libsg") -> "SecurityGuard"
                        else -> null
                    }
                    if (sdk != null && !foundDomains.contains(libName)) {
                        foundDomains.add(libName)
                        results.add(ScanResult(
                            packageName = packageName,
                            appName = appName,
                            adDomain = libName,
                            adSdk = sdk
                        ))
                    }
                }
            }

        } catch (e: Exception) {
            // Some packages may not be accessible
        }

        return results
    }

    private fun identifyAdSdk(domain: String): String {
        return when {
            domain.contains("google") -> "Google Ads"
            domain.contains("facebook") || domain.contains("fb") -> "Facebook Ads"
            domain.contains("umeng") -> "友盟 UMeng"
            domain.contains("gdt") || domain.contains("qq.com") -> "腾讯广告 GDT"
            domain.contains("baidu") -> "百度广告"
            domain.contains("toutiao") || domain.contains("byteoversea") -> "头条/穿山甲"
            domain.contains("kuaishou") -> "快手广告"
            domain.contains("sina") || domain.contains("weibo") -> "微博广告"
            domain.contains("inmobi") -> "InMobi"
            domain.contains("applovin") -> "AppLovin"
            domain.contains("unity") -> "Unity Ads"
            domain.contains("vungle") -> "Vungle"
            domain.contains("ironsrc") -> "IronSource"
            domain.contains("chartboost") -> "Chartboost"
            domain.contains("tapjoy") -> "Tapjoy"
            domain.contains("mopub") -> "MoPub"
            domain.contains("crashlytics") || domain.contains("firebase") -> "Firebase/Crashlytics"
            domain.contains("adjust") -> "Adjust"
            domain.contains("kochava") -> "Kochava"
            domain.contains("branch") -> "Branch.io"
            domain.contains("talkingdata") -> "TalkingData"
            domain.contains("admaster") -> "AdMaster"
            domain.contains("miaozhen") -> "秒针 Miaozhen"
            else -> "Unknown Ad SDK"
        }
    }

    private fun importRules(url: String) {
        scope.launch {
            updateNotification("正在导入规则...")
            try {
                val count = App.instance.repository.importMosdnsRules(url)
                updateNotification("已导入 $count 条规则")
            } catch (e: Exception) {
                updateNotification("规则导入失败: ${e.message}")
            }
            delay(3000)
            stopSelf()
        }
    }

    private fun updateNotification(text: String) {
        val notification = buildNotification(text)
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
