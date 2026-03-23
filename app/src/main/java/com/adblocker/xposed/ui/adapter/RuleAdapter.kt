package com.adblocker.xposed.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.adblocker.xposed.data.model.AdRule
import com.adblocker.xposed.databinding.ItemRuleBinding

class RuleAdapter(
    private val onToggle: (AdRule) -> Unit,
    private val onDelete: (AdRule) -> Unit
) : ListAdapter<AdRule, RuleAdapter.ViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemRuleBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(private val binding: ItemRuleBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(rule: AdRule) {
            binding.tvDomain.text = rule.domain
            binding.tvSource.text = when (rule.source) {
                "manual" -> "✏️ 手动"
                "mosdns" -> "🌐 Mosdns"
                "scan" -> "🔍 扫描"
                "capture" -> "📡 抓包"
                "hosts" -> "📋 Hosts"
                else -> rule.source
            }
            binding.tvPackage.text = if (rule.packageName.isNotEmpty()) {
                "📦 ${rule.packageName}"
            } else {
                "📦 全局"
            }
            binding.tvHitCount.text = "命中: ${rule.hitCount}"
            binding.switchEnabled.isChecked = rule.isEnabled

            binding.switchEnabled.setOnCheckedChangeListener { _, _ ->
                onToggle(rule)
            }

            binding.btnDelete.setOnClickListener {
                onDelete(rule)
            }
        }
    }

    private class DiffCallback : DiffUtil.ItemCallback<AdRule>() {
        override fun areItemsTheSame(old: AdRule, new: AdRule) = old.id == new.id
        override fun areContentsTheSame(old: AdRule, new: AdRule) = old == new
    }
}
