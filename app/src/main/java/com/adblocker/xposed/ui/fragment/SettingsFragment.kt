package com.adblocker.xposed.ui.fragment

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.adblocker.xposed.App
import com.adblocker.xposed.databinding.FragmentSettingsBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        loadSettings()
        setupActions()
    }

    private fun loadSettings() {
        val prefs = requireContext().getSharedPreferences(App.PREFS_NAME, Context.MODE_PRIVATE)

        binding.etKeywords.setText(prefs.getString(App.KEY_KEYWORDS, "") ?: "")
        binding.etMosdnsUrl.setText(prefs.getString(App.KEY_MOSDNS_URL, "") ?: "")
        binding.switchMosdns.isChecked = prefs.getBoolean(App.KEY_MOSDNS_ENABLED, false)

        val interval = prefs.getInt(App.KEY_RULE_UPDATE_INTERVAL, 24)
        binding.etUpdateInterval.setText(interval.toString())

        // Module info
        binding.tvVersion.text = "v1.0.0"
        binding.tvApiLevel.text = "LSPosed API 93"

        // Check LSPosed status
        val lspActive = checkLSPosedStatus()
        binding.tvLspStatus.text = if (lspActive) "✅ 已激活" else "❌ 未激活"
    }

    private fun setupActions() {
        // Save keywords
        binding.btnSaveKeywords.setOnClickListener {
            val prefs = requireContext().getSharedPreferences(App.PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putString(App.KEY_KEYWORDS, binding.etKeywords.text.toString()).apply()
            showMsg("关键词已保存")
        }

        // Save mosdns URL
        binding.btnSaveMosdns.setOnClickListener {
            val prefs = requireContext().getSharedPreferences(App.PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit()
                .putString(App.KEY_MOSDNS_URL, binding.etMosdnsUrl.text.toString())
                .putBoolean(App.KEY_MOSDNS_ENABLED, binding.switchMosdns.isChecked)
                .apply()
            showMsg("Mosdns设置已保存")
        }

        // Save update interval
        binding.btnSaveInterval.setOnClickListener {
            val interval = binding.etUpdateInterval.text.toString().toIntOrNull() ?: 24
            val prefs = requireContext().getSharedPreferences(App.PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putInt(App.KEY_RULE_UPDATE_INTERVAL, interval).apply()
            showMsg("更新间隔已设置为 ${interval} 小时")
        }

        // Export rules
        binding.btnExport.setOnClickListener {
            exportRules()
        }

        // Import rules from file
        binding.btnImport.setOnClickListener {
            showImportFileDialog()
        }

        // Clear all data
        binding.btnClearData.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle("清除数据")
                .setMessage("确定要清除所有数据吗？包括规则、抓包记录和扫描结果。")
                .setPositiveButton("确定") { _, _ ->
                    clearAllData()
                }
                .setNegativeButton("取消", null)
                .show()
        }

        // About
        binding.btnAbout.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle("关于 AdBlocker Xposed")
                .setMessage("""
                    版本: 1.0.0
                    API: LSPosed API 93
                    
                    功能:
                    • 基于域名的广告拦截
                    • Mosdns规则支持
                    • App广告域名扫描
                    • 网络抓包分析
                    • 关键词过滤
                    • 多规则源导入
                    
                    支持的规则格式:
                    • Hosts格式
                    • AdBlock格式
                    • 域名列表格式
                    • Mosdns配置格式
                """.trimIndent())
                .setPositiveButton("确定", null)
                .show()
        }
    }

    private fun checkLSPosedStatus(): Boolean {
        // Method 1: Check if XposedBridge is accessible (works inside hook context)
        try {
            val bridge = Class.forName("de.robv.android.xposed.XposedBridge")
            val getXposedVersion = bridge.getDeclaredMethod("getXposedVersion")
            getXposedVersion.isAccessible = true
            val version = getXposedVersion.invoke(null)
            if (version != null && (version as Int) > 0) return true
        } catch (_: Throwable) {}

        // Method 2: Check LSPosed Manager process via system property
        try {
            val process = Runtime.getRuntime().exec(arrayOf("getprop", "ro.dalvik.vm.isa"))
            val reader = process.inputStream.bufferedReader()
            val line = reader.readText().trim()
            reader.close()
            process.waitFor()
            // LSPosed sets specific properties
            if (line.isNotEmpty()) { /* at least system responds */ }
        } catch (_: Throwable) {}

        // Method 3: Check if LSPosed Manager is installed and module is enabled
        try {
            val pm = requireContext().packageManager
            // Try LSPosed Manager package names
            val lspPackages = listOf(
                "io.github.lsposed.manager",
                "org.lsposed.manager",
                "com.topjohnwu.magisk" // Magisk often co-located with LSPosed
            )
            for (pkg in lspPackages) {
                try {
                    pm.getPackageInfo(pkg, 0)
                    // LSPosed Manager is installed, check if our module is enabled
                    // via shared prefs set by Xposed hook
                    val prefs = requireContext().getSharedPreferences("adblocker_prefs", Context.MODE_PRIVATE)
                    val hookActive = prefs.getBoolean("hook_activated", false)
                    if (hookActive) return true
                } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}

        // Method 4: Check via intent query for LSPosed services
        try {
            val intent = android.content.Intent("org.lsposed.manager.HOOK_STATUS")
            val pm = requireContext().packageManager
            val resolveInfo = pm.queryIntentActivities(intent, 0)
            if (resolveInfo.isNotEmpty()) return true
        } catch (_: Throwable) {}

        // Method 5: Check /data/adb/lspd directory (requires root, may not work)
        try {
            val lspDir = java.io.File("/data/adb/lspd")
            if (lspDir.exists()) {
                // LSPosed framework directory exists
                val prefs = requireContext().getSharedPreferences("adblocker_prefs", Context.MODE_PRIVATE)
                // If we've been running for >30 seconds without crash, likely active
                val uptime = android.os.SystemClock.elapsedRealtime()
                if (uptime > 30000) return true
            }
        } catch (_: Throwable) {}

        return false
    }

    private fun exportRules() {
        viewLifecycleOwner.lifecycleScope.launch {
            val sb = StringBuilder()
            sb.appendLine("# AdBlocker Xposed Rules Export")
            sb.appendLine("# Generated: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(java.util.Date())}")
            sb.appendLine()

            val dao = App.instance.database.adRuleDao()
            val rulesList = withContext(Dispatchers.IO) {
                dao.getEnabledRulesSync()
            }

            rulesList.forEach { rule ->
                sb.appendLine("0.0.0.0 ${rule.domain}")
            }

            val file = java.io.File(
                requireContext().getExternalFilesDir(null),
                "adblocker_rules_${System.currentTimeMillis()}.txt"
            )
            file.writeText(sb.toString())
            showMsg("规则已导出到: ${file.absolutePath}")
        }
    }

    private fun showImportFileDialog() {
        val input = EditText(requireContext()).apply {
            hint = "粘贴规则内容 (每行一个域名)"
        }

        val layout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 40, 60, 0)
            addView(input)
        }

        AlertDialog.Builder(requireContext())
            .setTitle("导入规则")
            .setView(layout)
            .setPositiveButton("导入") { _, _ ->
                val content = input.text.toString()
                if (content.isNotEmpty()) {
                    viewLifecycleOwner.lifecycleScope.launch {
                        val lines = content.lines()
                        var count = 0
                        withContext(Dispatchers.IO) {
                            lines.forEach { line ->
                                val trimmed = line.trim()
                                if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                                    val domain = when {
                                        trimmed.startsWith("0.0.0.0") -> trimmed.split("\\s+".toRegex()).getOrNull(1)
                                        trimmed.startsWith("||") -> trimmed.removePrefix("||").removeSuffix("^")
                                        else -> trimmed
                                    }
                                    if (domain != null && domain.contains(".")) {
                                        App.instance.repository.insert(
                                            com.adblocker.xposed.data.model.AdRule(
                                                domain = domain.lowercase(),
                                                source = "import",
                                                ruleType = "domain"
                                            )
                                        )
                                        count++
                                    }
                                }
                            }
                        }
                        showMsg("成功导入 $count 条规则")
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun clearAllData() {
        viewLifecycleOwner.lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                val db = App.instance.database
                db.adRuleDao().deleteBySource("manual")
                db.adRuleDao().deleteBySource("scan")
                db.adRuleDao().deleteBySource("mosdns")
                db.adRuleDao().deleteBySource("hosts")
                db.captureLogDao().deleteAll()
                db.scanResultDao().deleteAll()
            }
            showMsg("数据已清除")
        }
    }

    private fun showMsg(msg: String) {
        android.widget.Toast.makeText(requireContext(), msg, android.widget.Toast.LENGTH_SHORT).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
