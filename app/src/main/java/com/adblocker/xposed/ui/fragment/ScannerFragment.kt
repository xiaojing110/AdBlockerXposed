package com.adblocker.xposed.ui.fragment

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.SearchView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.adblocker.xposed.App
import com.adblocker.xposed.databinding.FragmentScannerBinding
import com.adblocker.xposed.service.AdBlockService
import com.adblocker.xposed.ui.adapter.AppScanAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ScannerFragment : Fragment() {

    private var _binding: FragmentScannerBinding? = null
    private val binding get() = _binding!!
    private lateinit var adapter: AppScanAdapter
    private var allApps = listOf<AppInfo>()

    /** Prevent repeated popup when LiveData fires multiple updates */
    private var isShowingDetails = false
    /** Debounce rapid clicks */
    private var lastClickTime = 0L

    data class AppInfo(
        val packageName: String,
        val appName: String,
        val adCount: Int = 0,
        val adDomains: List<String> = emptyList()
    )

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentScannerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupRecyclerView()
        setupSearch()
        setupScanButton()
        loadApps()
        loadStats()
    }

    private fun setupRecyclerView() {
        adapter = AppScanAdapter { appInfo ->
            // Debounce: ignore clicks within 500ms
            val now = System.currentTimeMillis()
            if (now - lastClickTime < 500) return@AppScanAdapter
            lastClickTime = now

            // Prevent repeated popups
            if (isShowingDetails) return@AppScanAdapter
            isShowingDetails = true

            showAppDetails(appInfo)
        }
        binding.recyclerApps.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerApps.adapter = adapter
    }

    private fun setupSearch() {
        binding.searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?) = false

            override fun onQueryTextChange(newText: String?): Boolean {
                filterApps(newText ?: "")
                return true
            }
        })
    }

    private fun setupScanButton() {
        binding.btnScanAll.setOnClickListener {
            scanAllApps()
        }
    }

    private fun loadApps() {
        viewLifecycleOwner.lifecycleScope.launch {
            binding.progressBar.visibility = View.VISIBLE

            val apps = withContext(Dispatchers.IO) {
                val pm = requireContext().packageManager
                pm.getInstalledApplications(0)
                    .filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 }
                    .map { appInfo ->
                        AppInfo(
                            packageName = appInfo.packageName,
                            appName = pm.getApplicationLabel(appInfo).toString()
                        )
                    }
                    .sortedBy { it.appName }
            }

            allApps = apps
            adapter.submitList(apps)
            binding.progressBar.visibility = View.GONE
            binding.tvAppCount.text = "已安装 ${apps.size} 个应用"
        }
    }

    private fun loadStats() {
        val scanDao = App.instance.database.scanResultDao()
        scanDao.getScannedAppCount().observe(viewLifecycleOwner) { count ->
            binding.tvScannedCount.text = "已扫描 $count 个应用"
        }
        scanDao.getTotalAdCount().observe(viewLifecycleOwner) { count ->
            binding.tvAdFoundCount.text = "发现 $count 个广告域名"
        }
    }

    private fun filterApps(query: String) {
        val filtered = if (query.isEmpty()) {
            allApps
        } else {
            allApps.filter {
                it.appName.contains(query, ignoreCase = true) ||
                it.packageName.contains(query, ignoreCase = true)
            }
        }
        adapter.submitList(filtered)
    }

    private fun scanAllApps() {
        val packages = allApps.map { it.packageName }.toTypedArray()
        val intent = Intent(requireContext(), AdBlockService::class.java).apply {
            action = AdBlockService.ACTION_SCAN
            putExtra("packages", packages)
        }
        requireContext().startForegroundService(intent)
    }

    private fun showAppDetails(appInfo: AppInfo) {
        // Show scan results for this app — use observeOnce to prevent repeated popups
        val scanDao = App.instance.database.scanResultDao()

        // One-shot observer: only fires once then removes itself
        val liveData = scanDao.getResultsByPackage(appInfo.packageName)
        var dialog: android.app.AlertDialog? = null

        val observer = object : androidx.lifecycle.Observer<List<com.adblocker.xposed.data.model.ScanResult>> {
            override fun onChanged(results: List<com.adblocker.xposed.data.model.ScanResult>) {
                // Remove observer immediately to prevent re-firing
                liveData.removeObserver(this)

                val domains = results.joinToString("\n") { "• ${it.adDomain} (${it.adSdk})" }
                if (domains.isNotEmpty()) {
                    dialog = android.app.AlertDialog.Builder(requireContext())
                        .setTitle("${appInfo.appName}")
                        .setMessage("检测到的广告域名:\n\n$domains")
                        .setPositiveButton("添加到屏蔽列表") { d, _ ->
                            d.dismiss()
                            isShowingDetails = false
                            viewLifecycleOwner.lifecycleScope.launch {
                                val repo = App.instance.repository
                                results.forEach { result ->
                                    repo.insert(
                                        com.adblocker.xposed.data.model.AdRule(
                                            domain = result.adDomain,
                                            source = "scan",
                                            packageName = result.packageName,
                                            ruleType = "domain"
                                        )
                                    )
                                }
                            }
                        }
                        .setNegativeButton("关闭") { d, _ ->
                            d.dismiss()
                            isShowingDetails = false
                        }
                        .setOnDismissListener {
                            isShowingDetails = false
                        }
                        .show()
                } else {
                    dialog = android.app.AlertDialog.Builder(requireContext())
                        .setTitle(appInfo.appName)
                        .setMessage("未发现广告域名。请先点击\"扫描全部\"进行扫描。")
                        .setPositiveButton("确定") { d, _ ->
                            d.dismiss()
                            isShowingDetails = false
                        }
                        .setOnDismissListener {
                            isShowingDetails = false
                        }
                        .show()
                }
            }
        }

        liveData.observe(viewLifecycleOwner, observer)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
