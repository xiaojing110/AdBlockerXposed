package com.adblocker.xposed.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.adblocker.xposed.databinding.ItemAppBinding
import com.adblocker.xposed.ui.fragment.ScannerFragment.AppInfo

class AppScanAdapter(
    private val onClick: (AppInfo) -> Unit
) : ListAdapter<AppInfo, AppScanAdapter.ViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemAppBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(private val binding: ItemAppBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(app: AppInfo) {
            binding.tvAppName.text = app.appName
            binding.tvPackageName.text = app.packageName

            if (app.adCount > 0) {
                binding.tvAdCount.text = "${app.adCount} 个广告域名"
                binding.tvAdCount.setTextColor(
                    binding.root.context.getColor(android.R.color.holo_red_dark)
                )
            } else {
                binding.tvAdCount.text = "未扫描"
                binding.tvAdCount.setTextColor(
                    binding.root.context.getColor(android.R.color.darker_gray)
                )
            }

            // Try to get app icon
            try {
                val pm = binding.root.context.packageManager
                val icon = pm.getApplicationIcon(app.packageName)
                binding.ivAppIcon.setImageDrawable(icon)
            } catch (_: Exception) {
                binding.ivAppIcon.setImageResource(android.R.drawable.sym_def_app_icon)
            }

            binding.root.setOnClickListener { onClick(app) }
        }
    }

    private class DiffCallback : DiffUtil.ItemCallback<AppInfo>() {
        override fun areItemsTheSame(old: AppInfo, new: AppInfo) =
            old.packageName == new.packageName
        override fun areContentsTheSame(old: AppInfo, new: AppInfo) = old == new
    }
}
