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
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

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
            val total = packages.size
            val scanned = AtomicInteger(0)
            val found = AtomicInteger(0)

            // Use parallel coroutines (4 workers) for faster scanning
            val workers = 4.coerceAtMost(packages.size)
            val chunkSize = (packages.size + workers - 1) / workers

            val jobs = packages.chunked(chunkSize).map { chunk ->
                launch {
                    for (pkg in chunk) {
                        val current = scanned.incrementAndGet()
                        if (current % 10 == 0 || current == 1) {
                            updateNotification("扫描中: $current/$total (发现 ${found.get()} 个)")
                        }

                        try {
                            val appInfo = pm.getApplicationInfo(pkg, 0)
                            val appName = pm.getApplicationLabel(appInfo).toString()
                            val results = scanPackageForAds(pkg, appName)
                            if (results.isNotEmpty()) {
                                dao.insertAll(results)
                                found.addAndGet(results.size)
                            }
                        } catch (_: Exception) {}
                    }
                }
            }

            jobs.forEach { it.join() }

            updateNotification("扫描完成: $total 个应用, 发现 ${found.get()} 个广告域名")
            delay(3000)
            stopSelf()
        }
    }

    /**
     * Optimized ad SDK scan.
     * Strategy:
     * 1. Scan DEX class names for known ad SDK class patterns (fastest)
     * 2. Scan native .so library names
     * 3. Lightweight binary string scan (limited to first 5MB for speed)
     */
    private suspend fun scanPackageForAds(
        packageName: String,
        appName: String
    ): List<ScanResult> {
        val results = mutableListOf<ScanResult>()
        val foundDomains = mutableSetOf<String>()

        try {
            val appInfo = pm.getApplicationInfo(packageName, 0)
            val apkFile = File(appInfo.sourceDir)
            if (!apkFile.exists()) return results

            // === 1. DEX class scan (fastest, most reliable) ===
            scanDexClasses(apkFile, packageName, appName, foundDomains, results)

            // === 2. Native library scan ===
            scanNativeLibs(appInfo, packageName, appName, foundDomains, results)

            // === 3. Lightweight binary scan (first 5MB only for speed) ===
            scanBinaryStrings(apkFile, packageName, appName, foundDomains, results)

        } catch (_: Exception) {}

        return results
    }

    /**
     * Scan DEX files for known ad SDK class name patterns.
     * Much faster than reading raw binary — class names in DEX are readable strings.
     */
    private fun scanDexClasses(
        apkFile: File,
        packageName: String,
        appName: String,
        foundDomains: MutableSet<String>,
        results: MutableList<ScanResult>
    ) {
        try {
            // Read DEX class name strings (they're stored as modified UTF-8)
            // We look for well-known ad SDK class path segments
            val dexPatterns = mapOf(
                "com/google/android/gms/ads" to "Google Ads",
                "com/google/ads" to "Google Ads",
                "com/facebook/ads" to "Facebook Ads",
                "com/facebook/ads/internal" to "Facebook Ads",
                "com/umeng" to "友盟 UMeng",
                "com/taobao/accs" to "Alibaba Push",
                "com/qq/e/ads" to "腾讯广告 GDT",
                "com/baidu/mobads" to "百度广告",
                "com/bytedance/sdk/openadsdk" to "头条/穿山甲",
                "com/kwad/sdk" to "快手广告",
                "com/inmobi" to "InMobi",
                "com/applovin" to "AppLovin",
                "com/unity3d/services/ads" to "Unity Ads",
                "com/vungle" to "Vungle",
                "com/ironsource" to "IronSource",
                "com/chartboost" to "Chartboost",
                "com/tapjoy" to "Tapjoy",
                "com/mopub" to "MoPub",
                "com/crashlytics" to "Firebase/Crashlytics",
                "com/google/firebase" to "Firebase",
                "com/adjust" to "Adjust",
                "com/kochava" to "Kochava",
                "io/branch" to "Branch.io",
                "com/talkingdata" to "TalkingData",
                "com/sensorsdata" to "Sensors Analytics",
                "com/miaozhen" to "秒针 Miaozhen",
                "com/admaster" to "AdMaster",
                "net/pubnative" to "PubNative",
                "com/criteo" to "Criteo",
                "com/yandex/metrica" to "Yandex Metrica",
                "com/amazon/device/ads" to "Amazon Ads",
                "com/pangle/ads" to "Pangle/穿山甲",
            )

            // Read APK as ZIP and extract classes.dex
            java.util.zip.ZipFile(apkFile).use { zip ->
                val dexEntries = zip.entries().toList().filter { it.name.endsWith(".dex") }

                for (entry in dexEntries) {
                    try {
                        // Read DEX file content as text-like bytes
                        val stream = zip.getInputStream(entry)
                        val bytes = stream.readBytes()
                        stream.close()

                        // Convert to string for pattern matching (DEX strings are modified UTF-8)
                        val text = String(bytes, Charsets.UTF_8)

                        for ((pattern, sdk) in dexPatterns) {
                            if (text.contains(pattern, ignoreCase = false)) {
                                val domain = pattern.replace("/", ".")
                                if (foundDomains.add(domain)) {
                                    results.add(ScanResult(
                                        packageName = packageName,
                                        appName = appName,
                                        adDomain = domain,
                                        adSdk = sdk
                                    ))
                                }
                            }
                        }
                    } catch (_: Exception) {}
                }
            }
        } catch (_: Exception) {}
    }

    /**
     * Scan native .so libraries for known ad SDK library names.
     */
    private fun scanNativeLibs(
        appInfo: android.content.pm.ApplicationInfo,
        packageName: String,
        appName: String,
        foundDomains: MutableSet<String>,
        results: MutableList<ScanResult>
    ) {
        try {
            val libDir = File(appInfo.nativeLibraryDir ?: return)
            if (!libDir.exists()) return

            val libs = libDir.listFiles() ?: return
            val nativeLibPatterns = mapOf(
                "libmsc" to "Mintegral",
                "libsgmain" to "Alibaba",
                "libtbs" to "TBS/X5",
                "libweibosdk" to "Weibo",
                "libamap" to "Amap/Gaode",
                "libsg" to "SecurityGuard",
                "libflutter" to "Flutter",
                "libadcolony" to "AdColony",
                "libyoc" to "Yoc",
                "libgdt" to "腾讯广告",
            )

            for (lib in libs) {
                val libName = lib.name.lowercase()
                for ((pattern, sdk) in nativeLibPatterns) {
                    if (libName.contains(pattern) && foundDomains.add(lib.name)) {
                        results.add(ScanResult(
                            packageName = packageName,
                            appName = appName,
                            adDomain = lib.name,
                            adSdk = sdk
                        ))
                        break
                    }
                }
            }
        } catch (_: Exception) {}
    }

    /**
     * Lightweight binary string scan — only first 5MB for speed.
     * Extracts printable ASCII sequences and matches against domain patterns.
     */
    private fun scanBinaryStrings(
        apkFile: File,
        packageName: String,
        appName: String,
        foundDomains: MutableSet<String>,
        results: MutableList<ScanResult>
    ) {
        try {
            val maxScanBytes = 5 * 1024 * 1024 // Only first 5MB
            val minStrLen = 10
            val adDomains = listOf(
                "doubleclick.net", "googlesyndication.com", "googleadservices.com",
                "admob.com", "facebook.com/tr", "fbcdn.net",
                "umeng.com", "qq.com", "baidu.com",
                "toutiao.com", "byteoversea.com", "kuaishou.com",
                "applovin.com", "unity3d.com", "vungle.com",
                "ironsource.com", "chartboost.com", "inmobi.com",
                "mopub.com", "adjust.com", "crashlytics.com",
                "talkingdata.com", "sensorsdata.cn"
            )

            var totalRead = 0
            val buffer = ByteArray(256 * 1024) // 256KB chunks
            apkFile.inputStream().buffered().use { fis ->
                var bytesRead: Int = 0
                while (totalRead < maxScanBytes && fis.read(buffer).also { bytesRead = it } > 0) {
                    totalRead += bytesRead
                    val sb = StringBuilder()
                    for (i in 0 until bytesRead) {
                        val b = buffer[i].toInt() and 0xFF
                        if (b in 0x20..0x7E) {
                            sb.append(b.toChar())
                        } else {
                            if (sb.length >= minStrLen) {
                                val line = sb.toString().lowercase()
                                for (domain in adDomains) {
                                    if (line.contains(domain) && foundDomains.add(domain)) {
                                        results.add(ScanResult(
                                            packageName = packageName,
                                            appName = appName,
                                            adDomain = domain,
                                            adSdk = identifyAdSdk(domain)
                                        ))
                                    }
                                }
                            }
                            sb.clear()
                        }
                    }
                    // Check remaining
                    if (sb.length >= minStrLen) {
                        val line = sb.toString().lowercase()
                        for (domain in adDomains) {
                            if (line.contains(domain) && foundDomains.add(domain)) {
                                results.add(ScanResult(
                                    packageName = packageName,
                                    appName = appName,
                                    adDomain = domain,
                                    adSdk = identifyAdSdk(domain)
                                ))
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {}
    }

    private fun identifyAdSdk(domain: String): String {
        return when {
            domain.contains("google") -> "Google Ads"
            domain.contains("facebook") || domain.contains("fb") -> "Facebook Ads"
            domain.contains("umeng") -> "友盟 UMeng"
            domain.contains("gdt") || domain.contains("qq.com") -> "腾讯广告 GDT"
            domain.contains("baidu") -> "百度广告"
            domain.contains("toutiao") || domain.contains("byteoversea") || domain.contains("pangle") -> "头条/穿山甲"
            domain.contains("kuaishou") -> "快手广告"
            domain.contains("sina") || domain.contains("weibo") -> "微博广告"
            domain.contains("inmobi") -> "InMobi"
            domain.contains("applovin") -> "AppLovin"
            domain.contains("unity") -> "Unity Ads"
            domain.contains("vungle") -> "Vungle"
            domain.contains("ironsource") -> "IronSource"
            domain.contains("chartboost") -> "Chartboost"
            domain.contains("tapjoy") -> "Tapjoy"
            domain.contains("mopub") -> "MoPub"
            domain.contains("crashlytics") || domain.contains("firebase") -> "Firebase/Crashlytics"
            domain.contains("adjust") -> "Adjust"
            domain.contains("kochava") -> "Kochava"
            domain.contains("branch") -> "Branch.io"
            domain.contains("talkingdata") -> "TalkingData"
            domain.contains("sensorsdata") -> "Sensors Analytics"
            domain.contains("admaster") -> "AdMaster"
            domain.contains("miaozhen") -> "秒针 Miaozhen"
            domain.contains("mintegral") -> "Mintegral"
            domain.contains("adcolony") -> "AdColony"
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

