package com.adblocker.xposed.hook

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Advanced HTTP/HTTPS connection interceptor for ad blocking
 * Hooks at the HttpURLConnection and URLConnection level
 */
class HttpInterceptor : IXposedHookLoadPackage {

    companion object {
        private const val TAG = "AdBlocker_HTTP"

        // Expanded ad domain patterns
        private val AD_PATTERNS = listOf(
            // Google Ads
            "doubleclick.net", "googlesyndication.com", "googleadservices.com",
            "adsenseformobileapps.com", "admob.com", "adservice.google.com",
            // Facebook
            "facebook.com/tr", "facebook.com/fr", "fbcdn.net",
            // Common ad networks
            "adcolony.com", "applovin.com", "chartboost.com",
            "flurry.com", "inmobi.com", "mopub.com",
            "unity3d.com/ads", "vungle.com", "ironsrc.com",
            "tapjoy.com", "startapp.com", "supersonicads.com",
            // Analytics/Tracking
            "crashlytics.com", "fabric.io", "amplitude.com",
            "mixpanel.com", "segment.com", "branch.io",
            "adjust.com", "kochava.com", "appsflyer.com",
            // Chinese ad networks
            "umeng.com", "gdt.qq.com", "e.qq.com",
            "adashx.ut.taobao.com", "talkingdata.com",
            "admaster.com.cn", "miaozhen.com",
            // Regional
            "yandex.ru/analytics", "mc.yandex.ru",
            "metric.gstatic.com"
        )

        @Volatile
        private var blockedCount = 0
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName == "android" ||
            lpparam.packageName == "com.adblocker.xposed" ||
            lpparam.packageName.startsWith("com.android.systemui")) {
            return
        }

        hookURLConnection(lpparam)
        hookHttpClient(lpparam)
    }

    private fun hookURLConnection(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            // Hook URL.openConnection()
            XposedHelpers.findAndHookMethod(
                URL::class.java, "openConnection",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val url = param.thisObject as URL
                        val host = url.host?.lowercase() ?: return

                        if (isAdDomain(host) || matchesAdPattern(host)) {
                            blockedCount++
                            XposedBridge.log("$TAG [${lpparam.packageName}] Blocked: ${url}")
                            // Return a connection to 127.0.0.1 instead of throwing
                            val localhost = URL("http://127.0.0.1")
                            param.result = localhost.openConnection()
                        }
                    }
                }
            )
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: Failed to hook URL.openConnection: ${t.message}")
        }

        // Hook URLStreamHandler.openConnection
        try {
            XposedHelpers.findAndHookMethod(
                "java.net.URLStreamHandler", lpparam.classLoader,
                "openConnection", URL::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val url = param.args[0] as URL
                        val host = url.host?.lowercase() ?: return

                        if (isAdDomain(host) || matchesAdPattern(host)) {
                            blockedCount++
                            XposedBridge.log("$TAG [${lpparam.packageName}] Blocked stream: ${url}")
                            val localhost = URL("http://127.0.0.1")
                            param.result = localhost.openConnection()
                        }
                    }
                }
            )
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: Failed to hook URLStreamHandler: ${t.message}")
        }
    }

    private fun hookHttpClient(lpparam: XC_LoadPackage.LoadPackageParam) {
        // Try hooking Apache HttpClient if present
        try {
            val httpClientClass = XposedHelpers.findClass(
                "org.apache.http.impl.client.DefaultHttpClient",
                lpparam.classLoader
            )
            XposedHelpers.findAndHookMethod(
                httpClientClass, "execute",
                "org.apache.http.client.methods.HttpUriRequest",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val request = param.args[0]
                        val uriMethod = request.javaClass.getMethod("getURI")
                        val uri = uriMethod.invoke(request) as? java.net.URI ?: return
                        val host = uri.host?.lowercase() ?: return

                        if (isAdDomain(host) || matchesAdPattern(host)) {
                            blockedCount++
                            XposedBridge.log("$TAG [${lpparam.packageName}] Blocked HTTP: ${uri}")
                            throw java.io.IOException("Ad blocked")
                        }
                    }
                }
            )
        } catch (_: Throwable) {}
    }

    private fun isAdDomain(host: String): Boolean {
        // Check against common ad domain endings
        val adTlds = listOf(
            ".doubleclick.net", ".googlesyndication.com",
            ".googleadservices.com", ".admob.com",
            ".facebook.com", ".fbcdn.net"
        )
        for (tld in adTlds) {
            if (host.endsWith(tld)) return true
        }
        return false
    }

    private fun matchesAdPattern(host: String): Boolean {
        for (pattern in AD_PATTERNS) {
            if (host.contains(pattern, ignoreCase = true)) {
                return true
            }
        }
        return false
    }
}
