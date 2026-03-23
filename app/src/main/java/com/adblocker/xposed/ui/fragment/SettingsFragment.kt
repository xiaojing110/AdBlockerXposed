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

        // Check LSPosed status (async via root)
        binding.tvLspStatus.text = "检查中..."
        viewLifecycleOwner.lifecycleScope.launch {
            val (status, detail) = checkLSPosedStatusDetailed()
            binding.tvLspStatus.text = status
            if (detail.isNotEmpty()) {
                binding.tvLspStatus.setOnClickListener {
                    showMsg(detail)
                }
            }
        }
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

    /**
     * Detailed LSPosed status check.
     * Returns (displayText, detailMessage).
     *
     * Checks in order:
     * 1. XposedBridge version (works in hook context)
     * 2. LSPosed config files via root (module enabled + scope)
     * 3. hook_activated flag (set by AdBlockHook when it runs)
     * 4. LSPosed Manager installed?
     */
    private suspend fun checkLSPosedStatusDetailed(): Pair<String, String> = withContext(Dispatchers.IO) {
        // Method 1: XposedBridge directly (only works inside hooked process)
        try {
            val bridge = Class.forName("de.robv.android.xposed.XposedBridge")
            val getXposedVersion = bridge.getDeclaredMethod("getXposedVersion")
            getXposedVersion.isAccessible = true
            val version = getXposedVersion.invoke(null) as? Int
            if (version != null && version > 0) {
                return@withContext Pair("✅ 已激活 (Xposed API $version)", "")
            }
        } catch (_: Throwable) {}

        // Method 2: Read LSPosed config via root shell
        try {
            val myPkg = requireContext().packageName
            // LSPosed config paths (v1.9.x+)
            val configPaths = listOf(
                "/data/adb/lspd/config/modules_config.json",
                "/data/adb/lspd/config_manager/modules_config.json"
            )

            for (configPath in configPaths) {
                val output = execSu("cat $configPath 2>/dev/null")
                if (output.isNotEmpty()) {
                    // Parse JSON to check if our module is enabled and has scope
                    val jsonStr = output.trim()
                    // Check if our package is in the modules config
                    if (jsonStr.contains(myPkg)) {
                        // Check if android (system framework) is in scope
                        val hasSystemScope = jsonStr.contains("\"android\"")
                        val hasScope = jsonStr.contains("\"scope\"") &&
                            (jsonStr.contains("\"scope\": []") || // empty = all apps
                             jsonStr.contains("\"$myPkg\""))      // or contains our pkg

                        if (hasSystemScope) {
                            return@withContext Pair("✅ 已激活 (含系统框架)", "")
                        } else {
                            return@withContext Pair(
                                "⚠️ 已激活 (未含系统框架)",
                                "请在LSPosed Manager中勾选 android(系统框架)"
                            )
                        }
                    }
                }
            }

            // Also check legacy path
            val legacyOutput = execSu("cat /data/adb/modules/*/lspd/config/modules.list 2>/dev/null")
            if (legacyOutput.contains(requireContext().packageName)) {
                return@withContext Pair("✅ 已激活", "")
            }
        } catch (_: Throwable) {}

        // Method 3: Check hook_activated flag (set by AdBlockHook when it runs)
        try {
            val prefs = requireContext().getSharedPreferences("adblocker_prefs", Context.MODE_PRIVATE)
            if (prefs.getBoolean("hook_activated", false)) {
                return@withContext Pair("✅ 已激活", "")
            }
        } catch (_: Throwable) {}

        // Method 4: Check if LSPosed Manager is installed
        try {
            val pm = requireContext().packageManager
            val lspPkgs = listOf("io.github.lsposed.manager", "org.lsposed.manager")
            for (pkg in lspPkgs) {
                try {
                    pm.getPackageInfo(pkg, 0)
                    // LSPosed Manager installed but module not detected
                    return@withContext Pair(
                        "❌ 未激活",
                        "请在LSPosed Manager中启用模块并勾选作用域"
                    )
                } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}

        // Method 5: Check if LSPosed framework directory exists
        try {
            val lspExists = execSu("test -d /data/adb/lspd && echo yes").trim()
            if (lspExists == "yes") {
                return@withContext Pair(
                    "❌ 未激活",
                    "LSPosed框架已安装，请在Manager中启用本模块"
                )
            }
        } catch (_: Throwable) {}

        return@withContext Pair("❌ 未激活", "请安装LSPosed框架")
    }

    /**
     * Execute a shell command with su (root).
     * Returns stdout output, or empty string on failure.
     */
    private fun execSu(cmd: String): String {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()
            output
        } catch (_: Throwable) {
            ""
        }
    }

    @Deprecated("Use checkLSPosedStatusDetailed instead")
    private fun checkLSPosedStatus(): Boolean {
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
