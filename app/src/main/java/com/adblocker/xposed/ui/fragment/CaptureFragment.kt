package com.adblocker.xposed.ui.fragment

import android.content.Context
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
import com.adblocker.xposed.databinding.FragmentCaptureBinding
import com.adblocker.xposed.ui.adapter.CaptureAdapter
import com.google.android.material.chip.Chip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CaptureFragment : Fragment() {

    private var _binding: FragmentCaptureBinding? = null
    private val binding get() = _binding!!
    private lateinit var adapter: CaptureAdapter
    private var showBlockedOnly = false
    private var selectedPackage: String? = null

    /** Currently selected packages for capture (checked = capture this app) */
    private val selectedCapturePackages = mutableSetOf<String>()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentCaptureBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        loadSelectedPackages()
        setupRecyclerView()
        setupFilters()
        setupSearch()
        setupAppSelector()
        loadCaptureData()
    }

    private fun loadSelectedPackages() {
        val prefs = requireContext().getSharedPreferences(App.PREFS_NAME, Context.MODE_PRIVATE)
        val saved = prefs.getStringSet("capture_selected_packages", emptySet()) ?: emptySet()
        selectedCapturePackages.clear()
        selectedCapturePackages.addAll(saved)
    }

    private fun saveSelectedPackages() {
        val prefs = requireContext().getSharedPreferences(App.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putStringSet("capture_selected_packages", selectedCapturePackages).apply()
    }

    private fun setupRecyclerView() {
        adapter = CaptureAdapter { log ->
            showCaptureDetails(log)
        }
        binding.recyclerCapture.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerCapture.adapter = adapter
    }

    private fun setupFilters() {
        binding.chipAll.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                showBlockedOnly = false
                loadCaptureData()
            }
        }

        binding.chipBlocked.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                showBlockedOnly = true
                loadCaptureData()
            }
        }

        binding.btnClear.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                withContext(Dispatchers.IO) {
                    App.instance.database.captureLogDao().deleteAll()
                }
                adapter.submitList(emptyList())
            }
        }
    }

    private fun setupSearch() {
        binding.searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?) = false
            override fun onQueryTextChange(newText: String?): Boolean {
                searchCapture(newText ?: "")
                return true
            }
        })
    }

    /**
     * Set up app selection button — shows a multi-select dialog for choosing
     * which apps to capture traffic from.
     */
    private fun setupAppSelector() {
        // Add "选择App" button listener
        binding.btnSelectApps.setOnClickListener {
            showAppSelectionDialog()
        }

        // Update the button text to show count
        updateAppSelectorButton()
    }

    private fun updateAppSelectorButton() {
        val count = selectedCapturePackages.size
        binding.btnSelectApps.text = if (count > 0) {
            "📱 选择App ($count 已选)"
        } else {
            "📱 选择App (全部)"
        }
    }

    private fun showAppSelectionDialog() {
        viewLifecycleOwner.lifecycleScope.launch {
            val apps = withContext(Dispatchers.IO) {
                val pm = requireContext().packageManager
                pm.getInstalledApplications(0)
                    .filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 }
                    .map { appInfo ->
                        Triple(
                            appInfo.packageName,
                            pm.getApplicationLabel(appInfo).toString(),
                            selectedCapturePackages.contains(appInfo.packageName)
                        )
                    }
                    .sortedBy { it.second }
            }

            val packageNames = apps.map { it.first }.toTypedArray()
            val displayNames = apps.map { "${it.second} (${it.first.substringAfterLast(".")})" }.toTypedArray()
            val checkedItems = apps.map { it.third }.toBooleanArray()

            androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle("📱 选择要抓包的App")
                .setMultiChoiceItems(displayNames, checkedItems) { _, which, isChecked ->
                    checkedItems[which] = isChecked
                }
                .setPositiveButton("确定") { _, _ ->
                    selectedCapturePackages.clear()
                    for (i in checkedItems.indices) {
                        if (checkedItems[i]) {
                            selectedCapturePackages.add(packageNames[i])
                        }
                    }
                    saveSelectedPackages()
                    updateAppSelectorButton()

                    // Show message
                    val msg = if (selectedCapturePackages.isEmpty()) {
                        "已取消所有选择，将抓取全部App"
                    } else {
                        "已选择 ${selectedCapturePackages.size} 个App"
                    }
                    android.widget.Toast.makeText(requireContext(), msg, android.widget.Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("取消", null)
                .setNeutralButton("全选/取消") { _, _ ->
                    val allSelected = checkedItems.all { it }
                    for (i in checkedItems.indices) {
                        checkedItems[i] = !allSelected
                    }
                    // Re-show dialog with toggled state
                    selectedCapturePackages.clear()
                    if (!allSelected) {
                        for (i in checkedItems.indices) {
                            selectedCapturePackages.add(packageNames[i])
                        }
                    }
                    saveSelectedPackages()
                    updateAppSelectorButton()
                    val msg = if (allSelected) "已取消所有选择" else "已全选 ${selectedCapturePackages.size} 个App"
                    android.widget.Toast.makeText(requireContext(), msg, android.widget.Toast.LENGTH_SHORT).show()
                }
                .show()
        }
    }

    private fun loadCaptureData() {
        val logDao = App.instance.database.captureLogDao()

        val liveData = when {
            showBlockedOnly && selectedPackage != null ->
                logDao.getBlockedLogsByPackage(selectedPackage!!)
            showBlockedOnly -> logDao.getBlockedLogs()
            selectedPackage != null -> logDao.getLogsByPackage(selectedPackage!!)
            else -> logDao.getAllLogs()
        }

        liveData.observe(viewLifecycleOwner) { logs ->
            adapter.submitList(logs)
            binding.tvCaptureCount.text = "共 ${logs.size} 条记录"
        }

        // Load package filter
        logDao.getPackageNames().observe(viewLifecycleOwner) { packages ->
            setupPackageFilter(packages)
        }
    }

    private fun setupPackageFilter(packages: List<String>) {
        // Simple package filter using chip group
        binding.chipGroupPackages.removeAllViews()

        val allChip = Chip(requireContext()).apply {
            text = "全部"
            isCheckable = true
            isChecked = selectedPackage == null
            setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) {
                    selectedPackage = null
                    loadCaptureData()
                }
            }
        }
        binding.chipGroupPackages.addView(allChip)

        packages.take(10).forEach { pkg ->
            val chip = Chip(requireContext()).apply {
                text = pkg.substringAfterLast(".")
                isCheckable = true
                isChecked = pkg == selectedPackage
                setOnCheckedChangeListener { _, isChecked ->
                    if (isChecked) {
                        selectedPackage = pkg
                        loadCaptureData()
                    }
                }
            }
            binding.chipGroupPackages.addView(chip)
        }
    }

    private fun searchCapture(keyword: String) {
        if (keyword.isEmpty()) {
            loadCaptureData()
            return
        }

        val logDao = App.instance.database.captureLogDao()
        logDao.searchLogs(keyword).observe(viewLifecycleOwner) { logs ->
            adapter.submitList(logs)
            binding.tvCaptureCount.text = "搜索结果: ${logs.size} 条"
        }
    }

    private fun showCaptureDetails(log: com.adblocker.xposed.data.model.CaptureLog) {
        val details = buildString {
            appendLine("📍 URL: ${log.url}")
            appendLine("🏠 Host: ${log.host}")
            appendLine("📦 App: ${log.packageName}")
            appendLine("📋 Method: ${log.method}")
            appendLine("📊 Status: ${log.statusCode}")
            appendLine("📄 Content-Type: ${log.contentType}")
            appendLine("📏 Size: ${log.contentLength}")
            if (log.isBlocked) {
                appendLine("🚫 已拦截: ${log.blockReason}")
            }
            if (log.headers.isNotEmpty()) {
                appendLine("\n--- Headers ---")
                appendLine(log.headers)
            }
            if (log.requestBody.isNotEmpty()) {
                appendLine("\n--- Request Body ---")
                appendLine(log.requestBody.take(1000))
            }
            if (log.responseBody.isNotEmpty()) {
                appendLine("\n--- Response Body ---")
                appendLine(log.responseBody.take(1000))
            }
        }

        android.app.AlertDialog.Builder(requireContext())
            .setTitle("抓包详情")
            .setMessage(details)
            .setPositiveButton("确定", null)
            .setNeutralButton("添加到规则") { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    App.instance.repository.insert(
                        com.adblocker.xposed.data.model.AdRule(
                            domain = log.host,
                            source = "capture",
                            packageName = log.packageName,
                            ruleType = "domain"
                        )
                    )
                }
            }
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
