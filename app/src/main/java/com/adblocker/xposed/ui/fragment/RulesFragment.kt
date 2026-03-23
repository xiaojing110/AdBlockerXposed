package com.adblocker.xposed.ui.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SearchView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.adblocker.xposed.App
import com.adblocker.xposed.data.model.AdRule
import com.adblocker.xposed.data.repository.AdRuleRepository
import com.adblocker.xposed.databinding.FragmentRulesBinding
import com.adblocker.xposed.ui.adapter.RuleAdapter
import kotlinx.coroutines.launch

class RulesFragment : Fragment() {

    private var _binding: FragmentRulesBinding? = null
    private val binding get() = _binding!!
    private lateinit var adapter: RuleAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentRulesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupRecyclerView()
        setupActions()
        setupSearch()
        loadRules()
    }

    private fun setupRecyclerView() {
        adapter = RuleAdapter(
            onToggle = { rule ->
                viewLifecycleOwner.lifecycleScope.launch {
                    App.instance.repository.update(rule.copy(isEnabled = !rule.isEnabled))
                }
            },
            onDelete = { rule ->
                viewLifecycleOwner.lifecycleScope.launch {
                    App.instance.repository.delete(rule)
                }
            }
        )
        binding.recyclerRules.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerRules.adapter = adapter
    }

    private fun setupActions() {
        // Add manual rule
        binding.fabAddRule.setOnClickListener {
            showAddRuleDialog()
        }

        // Import from URL
        binding.btnImportUrl.setOnClickListener {
            showImportDialog()
        }

        // Import presets
        binding.btnPresets.setOnClickListener {
            showPresetsDialog()
        }
    }

    private fun setupSearch() {
        binding.searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?) = false

            override fun onQueryTextChange(newText: String?): Boolean {
                if (newText.isNullOrEmpty()) {
                    loadRules()
                } else {
                    searchRules(newText)
                }
                return true
            }
        })
    }

    private fun loadRules() {
        App.instance.repository.getAllRules().observe(viewLifecycleOwner) { rules ->
            adapter.submitList(rules)
            binding.tvRuleCount.text = "共 ${rules.size} 条规则"

            val enabled = rules.count { it.isEnabled }
            binding.tvEnabledCount.text = "已启用 $enabled 条"
        }
    }

    private fun searchRules(query: String) {
        App.instance.repository.searchRules(query).observe(viewLifecycleOwner) { rules ->
            adapter.submitList(rules)
            binding.tvRuleCount.text = "搜索结果: ${rules.size} 条"
        }
    }

    private fun showAddRuleDialog() {
        val layout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 40, 60, 0)
        }

        val domainInput = EditText(requireContext()).apply {
            hint = "域名 (如 ads.example.com)"
        }
        layout.addView(domainInput)

        val packageInput = EditText(requireContext()).apply {
            hint = "应用包名 (可选)"
        }
        layout.addView(packageInput)

        AlertDialog.Builder(requireContext())
            .setTitle("添加规则")
            .setView(layout)
            .setPositiveButton("添加") { _, _ ->
                val domain = domainInput.text.toString().trim()
                if (domain.isNotEmpty()) {
                    viewLifecycleOwner.lifecycleScope.launch {
                        App.instance.repository.insert(
                            AdRule(
                                domain = domain.lowercase(),
                                source = "manual",
                                packageName = packageInput.text.toString().trim(),
                                ruleType = "domain"
                            )
                        )
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showImportDialog() {
        val input = EditText(requireContext()).apply {
            hint = "规则文件 URL"
            setText("https://raw.githubusercontent.com/privacy-protection-tools/anti-AD/master/anti-ad-domains.txt")
        }

        AlertDialog.Builder(requireContext())
            .setTitle("从URL导入规则")
            .setView(input)
            .setPositiveButton("导入") { _, _ ->
                val url = input.text.toString().trim()
                if (url.isNotEmpty()) {
                    viewLifecycleOwner.lifecycleScope.launch {
                        try {
                            val count = App.instance.repository.importMosdnsRules(url)
                            android.widget.Toast.makeText(
                                requireContext(),
                                "成功导入 $count 条规则",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        } catch (e: Exception) {
                            android.widget.Toast.makeText(
                                requireContext(),
                                "导入失败: ${e.message}",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showPresetsDialog() {
        val sources = AdRuleRepository.RULE_SOURCES
        val names = sources.keys.toTypedArray()
        val descriptions = arrayOf(
            "anti-AD (Mosdns推荐)",
            "AdGuard DNS",
            "Steven Black Hosts",
            "OISD Small",
            "OISD Big",
            "Hagezi Pro",
            "Hagezi Multi"
        )

        AlertDialog.Builder(requireContext())
            .setTitle("选择规则源")
            .setItems(descriptions) { _, which ->
                val url = sources[names[which]] ?: return@setItems
                viewLifecycleOwner.lifecycleScope.launch {
                    try {
                        val count = if (url.contains("hosts")) {
                            App.instance.repository.importHostsRules(url)
                        } else {
                            App.instance.repository.importMosdnsRules(url)
                        }
                        android.widget.Toast.makeText(
                            requireContext(),
                            "成功导入 $count 条规则",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    } catch (e: Exception) {
                        android.widget.Toast.makeText(
                            requireContext(),
                            "导入失败: ${e.message}",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
