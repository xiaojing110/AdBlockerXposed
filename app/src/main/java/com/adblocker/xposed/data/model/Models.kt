package com.adblocker.xposed.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "ad_rules")
data class AdRule(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val domain: String,
    val source: String = "manual",  // manual, mosdns, scan
    val packageName: String = "",   // which app this rule applies to
    val isEnabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val hitCount: Long = 0,
    val ruleType: String = "domain"  // domain, keyword, regex
)

@Entity(tableName = "capture_logs")
data class CaptureLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String,
    val url: String,
    val host: String,
    val method: String,
    val statusCode: Int,
    val contentType: String,
    val contentLength: Long,
    val headers: String,
    val requestBody: String,
    val responseBody: String,
    val timestamp: Long = System.currentTimeMillis(),
    val isBlocked: Boolean = false,
    val blockReason: String = ""
)

@Entity(tableName = "blocked_apps")
data class BlockedApp(
    @PrimaryKey val packageName: String,
    val appName: String,
    val isEnabled: Boolean = true,
    val addedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "scan_results")
data class ScanResult(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String,
    val appName: String,
    val adDomain: String,
    val adSdk: String,
    val scanTime: Long = System.currentTimeMillis()
)
