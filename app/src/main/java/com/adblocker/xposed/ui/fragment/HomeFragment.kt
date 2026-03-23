package com.adblocker.xposed.ui.fragment

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.adblocker.xposed.App
import com.adblocker.xposed.R
import com.adblocker.xposed.databinding.FragmentHomeBinding
import com.adblocker.xposed.service.AdBlockService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupUI()
        loadStats()
    }

    private fun setupUI() {
        val prefs = requireContext().getSharedPreferences(App.PREFS_NAME, Context.MODE_PRIVATE)

        // Enable/Disable toggle
        val enabled = prefs.getBoolean(App.KEY_ENABLED, true)
        binding.switchEnable.isChecked = enabled
        binding.switchEnable.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(App.KEY_ENABLED, isChecked).apply()
            updateStatusCard(isChecked)
        }

        updateStatusCard(enabled)

        // Import mosdns rules button
        binding.btnImportMosdns.setOnClickListener {
            importMosdnsRules()
        }

        // Quick scan button
        binding.btnQuickScan.setOnClickListener {
            quickScan()
        }

        // Capture toggle
        val captureEnabled = prefs.getBoolean(App.KEY_CAPTURE_ENABLED, false)
        binding.switchCapture.isChecked = captureEnabled
        binding.switchCapture.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(App.KEY_CAPTURE_ENABLED, isChecked).apply()
        }
    }

    private fun updateStatusCard(enabled: Boolean) {
        if (enabled) {
            binding.cardStatus.setCardBackgroundColor(
                requireContext().getColor(R.color.status_active)
            )
            binding.tvStatus.text = "🛡️ 防护已开启"
            binding.tvStatusDesc.text = "正在拦截广告请求"
        } else {
            binding.cardStatus.setCardBackgroundColor(
                requireContext().getColor(R.color.status_inactive)
            )
            binding.tvStatus.text = "⚪ 防护已关闭"
            binding.tvStatusDesc.text = "点击开关启用广告拦截"
        }
    }

    private fun loadStats() {
        viewLifecycleOwner.lifecycleScope.launch {
            val dao = App.instance.database.adRuleDao()
            val logDao = App.instance.database.captureLogDao()

            // Rule count
            dao.getRuleCount().observe(viewLifecycleOwner) { count ->
                binding.tvRuleCount.text = count.toString()
            }

            // Blocked count
            logDao.getBlockedCount().observe(viewLifecycleOwner) { count ->
                binding.tvBlockedCount.text = count.toString()
            }

            // Log count
            logDao.getLogCount().observe(viewLifecycleOwner) { count ->
                binding.tvCaptureCount.text = count.toString()
            }

            // Load last update time
            withContext(Dispatchers.IO) {
                val prefs = requireContext().getSharedPreferences(App.PREFS_NAME, Context.MODE_PRIVATE)
                val lastUpdate = prefs.getLong("last_rule_update", 0)
                if (lastUpdate > 0) {
                    val sdf = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
                    withContext(Dispatchers.Main) {
                        binding.tvLastUpdate.text = "更新于: ${sdf.format(Date(lastUpdate))}"
                    }
                }
            }
        }
    }

    private fun importMosdnsRules() {
        val prefs = requireContext().getSharedPreferences(App.PREFS_NAME, Context.MODE_PRIVATE)
        val url = prefs.getString(App.KEY_MOSDNS_URL, "") ?: ""

        if (url.isEmpty()) {
            // Use default anti-AD rules
            val intent = Intent(requireContext(), AdBlockService::class.java).apply {
                action = AdBlockService.ACTION_IMPORT_RULES
                putExtra(AdBlockService.EXTRA_URL,
                    "https://raw.githubusercontent.com/privacy-protection-tools/anti-AD/master/anti-ad-domains.txt")
            }
            requireContext().startForegroundService(intent)
        } else {
            val intent = Intent(requireContext(), AdBlockService::class.java).apply {
                action = AdBlockService.ACTION_IMPORT_RULES
                putExtra(AdBlockService.EXTRA_URL, url)
            }
            requireContext().startForegroundService(intent)
        }

        prefs.edit().putLong("last_rule_update", System.currentTimeMillis()).apply()
    }

    private fun quickScan() {
        val pm = requireContext().packageManager
        val packages = pm.getInstalledApplications(0)
            .filter { pm.getLaunchIntentForPackage(it.packageName) != null }
            .map { it.packageName }
            .toTypedArray()

        val intent = Intent(requireContext(), AdBlockService::class.java).apply {
            action = AdBlockService.ACTION_SCAN
            putExtra("packages", packages)
        }
        requireContext().startForegroundService(intent)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
