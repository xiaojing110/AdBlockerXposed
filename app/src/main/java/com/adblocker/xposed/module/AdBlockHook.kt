package com.adblocker.xposed.module

import android.app.AndroidAppHelper
import android.content.Context
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL

class AdBlockHook : IXposedHookLoadPackage {

    companion object {
        private const val TAG = "AdBlockerXposed"
        private const val PREFS_NAME = "adblocker_prefs"
        private const val KEY_ENABLED = "enabled"

        // Domain block list loaded in memory
        @Volatile
        private var blockedDomains: Set<String> = emptySet()

        @Volatile
        private var enabled = true

        @Volatile
        private var captureEnabled = false

        @Volatile
        private var keywords: List<String> = emptyList()

        // Packages to skip
        private val SKIP_PACKAGES = setOf(
            "android", "com.android.systemui", "com.adblocker.xposed",
            "com.topjohnwu.magisk", "io.github.lsposed.manager"
        )

        fun reloadConfig(context: Context) {
            try {
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                enabled = prefs.getBoolean(KEY_ENABLED, true)
                captureEnabled = prefs.getBoolean("capture_enabled", false)
                val kw = prefs.getString("keywords", "") ?: ""
                keywords = kw.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            } catch (_: Throwable) {}
        }
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        val packageName = lpparam.packageName

        // Skip system packages and ourselves
        if (SKIP_PACKAGES.contains(packageName)) return
        if (packageName.startsWith("com.android.")) return

        try {
            hookNetworkCalls(lpparam)
            hookDnsResolution(lpparam)
            hookWebView(lpparam)
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: Error hooking $packageName: ${t.message}")
        }
    }

    /**
     * Hook HttpURLConnection and OkHttp network calls
     */
    private fun hookNetworkCalls(lpparam: XC_LoadPackage.LoadPackageParam) {
        // Hook HttpURLConnection
        try {
            XposedHelpers.findAndHookConstructor(
                HttpURLConnection::class.java,
                URL::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!enabled) return
                        val url = param.args[0] as URL
                        val host = url.host.lowercase()

                        if (shouldBlock(host)) {
                            XposedBridge.log("$TAG: Blocked URL: $url")
                            incrementHit(host)
                            throw IOException("Ad blocked by AdBlockerXposed: $host")
                        }

                        if (captureEnabled) {
                            logCapture(lpparam.packageName, url.toString(), host, "GET")
                        }
                    }
                }
            )
        } catch (_: Throwable) {}

        // Hook OkHttp
        hookOkHttp(lpparam)

        // Hook Apache HTTP (legacy)
        hookApacheHttp(lpparam)
    }

    private fun hookOkHttp(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val requestBuilderClass = XposedHelpers.findClass(
                "okhttp3.Request\$Builder", lpparam.classLoader
            )
            XposedHelpers.findAndHookMethod(
                requestBuilderClass, "build",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!enabled) return
                        try {
                            val urlField = requestBuilderClass.getDeclaredField("url")
                            urlField.isAccessible = true
                            val urlBuilder = urlField.get(param.thisObject)
                            if (urlBuilder != null) {
                                val urlStr = urlBuilder.toString()
                                val host = try { URL(urlStr).host.lowercase() } catch (_: Throwable) { "" }

                                if (host.isNotEmpty() && shouldBlock(host)) {
                                    XposedBridge.log("$TAG: Blocked OkHttp: $urlStr")
                                    incrementHit(host)
                                    throw IOException("Ad blocked by AdBlockerXposed: $host")
                                }

                                if (captureEnabled && host.isNotEmpty()) {
                                    logCapture(lpparam.packageName, urlStr, host, "OkHttp")
                                }
                            }
                        } catch (_: IOException) {
                            throw
                        } catch (_: Throwable) {}
                    }
                }
            )
        } catch (_: Throwable) {}
    }

    private fun hookApacheHttp(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val uriClass = XposedHelpers.findClass(
                "org.apache.http.client.methods.HttpUriRequest", lpparam.classLoader
            )
            // Hook execute methods on various clients
            val clientClasses = listOf(
                "org.apache.http.impl.client.DefaultHttpClient",
                "org.apache.http.impl.client.AbstractHttpClient"
            )
            for (cls in clientClasses) {
                try {
                    val clientClass = XposedHelpers.findClass(cls, lpparam.classLoader)
                    XposedHelpers.findAndHookMethod(
                        clientClass, "execute", uriClass,
                        object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                if (!enabled) return
                                try {
                                    val request = param.args[0]
                                    val getUri = request.javaClass.getMethod("getURI")
                                    val uri = getUri.invoke(request) as java.net.URI
                                    val host = uri.host?.lowercase() ?: return

                                    if (shouldBlock(host)) {
                                        XposedBridge.log("$TAG: Blocked Apache HTTP: $uri")
                                        incrementHit(host)
                                        throw IOException("Ad blocked by AdBlockerXposed: $host")
                                    }
                                } catch (_: IOException) {
                                    throw
                                } catch (_: Throwable) {}
                            }
                        }
                    )
                } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}
    }

    /**
     * Hook DNS resolution to block ad domains at DNS level
     */
    private fun hookDnsResolution(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            XposedHelpers.findAndHookMethod(
                InetAddress::class.java, "getAllByName", String::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!enabled) return
                        val host = (param.args[0] as String).lowercase()

                        if (shouldBlock(host)) {
                            XposedBridge.log("$TAG: Blocked DNS: $host")
                            incrementHit(host)
                            // Return loopback instead of throwing (less disruptive)
                            param.result = arrayOf(InetAddress.getByName("127.0.0.1"))
                        }
                    }
                }
            )
        } catch (_: Throwable) {}

        // Also hook InetAddress.getByName
        try {
            XposedHelpers.findAndHookMethod(
                InetAddress::class.java, "getByName", String::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!enabled) return
                        val host = (param.args[0] as String).lowercase()

                        if (shouldBlock(host)) {
                            XposedBridge.log("$TAG: Blocked DNS getByName: $host")
                            incrementHit(host)
                            param.result = InetAddress.getByName("127.0.0.1")
                        }
                    }
                }
            )
        } catch (_: Throwable) {}
    }

    /**
     * Hook WebView to block ad loading in web views
     */
    private fun hookWebView(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val webViewClass = XposedHelpers.findClass(
                "android.webkit.WebView", lpparam.classLoader
            )
            XposedHelpers.findAndHookMethod(
                webViewClass, "loadUrl", String::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!enabled) return
                        val url = param.args[0] as? String ?: return
                        try {
                            val host = URL(url).host.lowercase()
                            if (shouldBlock(host)) {
                                XposedBridge.log("$TAG: Blocked WebView: $url")
                                incrementHit(host)
                                param.result = null // Prevent loading
                            }
                        } catch (_: Throwable) {}
                    }
                }
            )
        } catch (_: Throwable) {}
    }

    /**
     * Check if a domain should be blocked
     */
    private fun shouldBlock(host: String): Boolean {
        // Check exact domain match
        if (blockedDomains.contains(host)) return true

        // Check parent domains (e.g., sub.ads.example.com matches ads.example.com)
        var domain = host
        while (domain.contains(".")) {
            val dotIndex = domain.indexOf(".")
            domain = domain.substring(dotIndex + 1)
            if (blockedDomains.contains(domain)) return true
        }

        // Check keywords
        for (keyword in keywords) {
            if (host.contains(keyword, ignoreCase = true)) return true
        }

        return false
    }

    private fun incrementHit(domain: String) {
        // Store hit count asynchronously via shared prefs
        try {
            val context = AndroidAppHelper.currentApplication()
            val prefs = context.getSharedPreferences("adblocker_hits", Context.MODE_PRIVATE)
            val count = prefs.getInt("hit_$domain", 0)
            prefs.edit().putInt("hit_$domain", count + 1).apply()
        } catch (_: Throwable) {}
    }

    private fun logCapture(packageName: String, url: String, host: String, method: String) {
        // Store capture data via shared prefs for the app to read
        try {
            val context = AndroidAppHelper.currentApplication()
            val prefs = context.getSharedPreferences("adblocker_capture", Context.MODE_PRIVATE)
            val timestamp = System.currentTimeMillis()
            val key = "cap_${timestamp}_${host.hashCode()}"
            val data = "$packageName|$url|$host|$method|$timestamp"
            prefs.edit().putString(key, data).apply()
        } catch (_: Throwable) {}
    }
}
