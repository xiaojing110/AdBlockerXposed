package com.adblocker.xposed.ui.fragment

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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CaptureFragment : Fragment() {

    private var _binding: FragmentCaptureBinding? = null
    private val binding get() = _binding!!
    private lateinit var adapter: CaptureAdapter
    private var showBlockedOnly = false
    private var selectedPackage: String? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentCaptureBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupRecyclerView()
        setupFilters()
        setupSearch()
        loadCaptureData()
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

        val allChip = android.widget.Chip(requireContext()).apply {
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
            val chip = android.widget.Chip(requireContext()).apply {
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
