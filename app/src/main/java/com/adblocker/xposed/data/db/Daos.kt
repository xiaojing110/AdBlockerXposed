package com.adblocker.xposed.data.db

import androidx.lifecycle.LiveData
import androidx.room.*
import com.adblocker.xposed.data.model.*

@Dao
interface AdRuleDao {
    @Query("SELECT * FROM ad_rules WHERE isEnabled = 1 ORDER BY hitCount DESC")
    fun getEnabledRules(): LiveData<List<AdRule>>

    @Query("SELECT * FROM ad_rules ORDER BY createdAt DESC")
    fun getAllRules(): LiveData<List<AdRule>>

    @Query("SELECT * FROM ad_rules WHERE isEnabled = 1")
    suspend fun getEnabledRulesSync(): List<AdRule>

    @Query("SELECT * FROM ad_rules WHERE domain LIKE '%' || :query || '%' OR packageName LIKE '%' || :query || '%'")
    fun searchRules(query: String): LiveData<List<AdRule>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(rule: AdRule): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rules: List<AdRule>)

    @Update
    suspend fun update(rule: AdRule)

    @Delete
    suspend fun delete(rule: AdRule)

    @Query("DELETE FROM ad_rules WHERE source = :source")
    suspend fun deleteBySource(source: String)

    @Query("UPDATE ad_rules SET hitCount = hitCount + 1 WHERE domain = :domain")
    suspend fun incrementHit(domain: String)

    @Query("SELECT COUNT(*) FROM ad_rules")
    fun getRuleCount(): LiveData<Int>

    @Query("SELECT COUNT(*) FROM ad_rules WHERE isEnabled = 1")
    suspend fun getEnabledRuleCount(): Int

    @Query("SELECT DISTINCT source FROM ad_rules")
    fun getSources(): LiveData<List<String>>
}

@Dao
interface CaptureLogDao {
    @Query("SELECT * FROM capture_logs ORDER BY timestamp DESC")
    fun getAllLogs(): LiveData<List<CaptureLog>>

    @Query("SELECT * FROM capture_logs WHERE packageName = :pkg ORDER BY timestamp DESC")
    fun getLogsByPackage(pkg: String): LiveData<List<CaptureLog>>

    @Query("SELECT * FROM capture_logs WHERE isBlocked = 1 ORDER BY timestamp DESC")
    fun getBlockedLogs(): LiveData<List<CaptureLog>>

    @Query("SELECT * FROM capture_logs WHERE url LIKE '%' || :keyword || '%' OR host LIKE '%' || :keyword || '%' ORDER BY timestamp DESC")
    fun searchLogs(keyword: String): LiveData<List<CaptureLog>>

    @Query("SELECT * FROM capture_logs WHERE packageName = :pkg AND isBlocked = 1 ORDER BY timestamp DESC")
    fun getBlockedLogsByPackage(pkg: String): LiveData<List<CaptureLog>>

    @Insert
    suspend fun insert(log: CaptureLog): Long

    @Query("DELETE FROM capture_logs WHERE timestamp < :before")
    suspend fun deleteOlderThan(before: Long)

    @Query("DELETE FROM capture_logs")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM capture_logs")
    fun getLogCount(): LiveData<Int>

    @Query("SELECT COUNT(*) FROM capture_logs WHERE isBlocked = 1")
    fun getBlockedCount(): LiveData<Int>

    @Query("SELECT DISTINCT packageName FROM capture_logs ORDER BY packageName")
    fun getPackageNames(): LiveData<List<String>>
}

@Dao
interface BlockedAppDao {
    @Query("SELECT * FROM blocked_apps ORDER BY addedAt DESC")
    fun getAllBlockedApps(): LiveData<List<BlockedApp>>

    @Query("SELECT * FROM blocked_apps WHERE isEnabled = 1")
    suspend fun getEnabledBlockedApps(): List<BlockedApp>

    @Query("SELECT packageName FROM blocked_apps WHERE isEnabled = 1")
    suspend fun getEnabledPackageNames(): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(app: BlockedApp)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(apps: List<BlockedApp>)

    @Delete
    suspend fun delete(app: BlockedApp)

    @Query("DELETE FROM blocked_apps WHERE packageName = :pkg")
    suspend fun deleteByPackage(pkg: String)

    @Query("SELECT EXISTS(SELECT 1 FROM blocked_apps WHERE packageName = :pkg AND isEnabled = 1)")
    suspend fun isBlocked(pkg: String): Boolean

    @Query("UPDATE blocked_apps SET isEnabled = :enabled WHERE packageName = :pkg")
    suspend fun setEnabled(pkg: String, enabled: Boolean)
}

@Dao
interface ScanResultDao {
    @Query("SELECT * FROM scan_results ORDER BY scanTime DESC")
    fun getAllResults(): LiveData<List<ScanResult>>

    @Query("SELECT * FROM scan_results WHERE packageName = :pkg")
    fun getResultsByPackage(pkg: String): LiveData<List<ScanResult>>

    @Query("SELECT DISTINCT packageName, appName FROM scan_results ORDER BY appName")
    fun getScannedApps(): LiveData<List<ScannedApp>>

    @Insert
    suspend fun insert(result: ScanResult): Long

    @Insert
    suspend fun insertAll(results: List<ScanResult>)

    @Query("DELETE FROM scan_results WHERE packageName = :pkg")
    suspend fun deleteByPackage(pkg: String)

    @Query("DELETE FROM scan_results")
    suspend fun deleteAll()

    @Query("SELECT COUNT(DISTINCT packageName) FROM scan_results")
    fun getScannedAppCount(): LiveData<Int>

    @Query("SELECT COUNT(*) FROM scan_results")
    fun getTotalAdCount(): LiveData<Int>
}
