package com.adblocker.xposed.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.adblocker.xposed.data.model.CaptureLog
import com.adblocker.xposed.databinding.ItemCaptureBinding
import java.text.SimpleDateFormat
import java.util.*

class CaptureAdapter(
    private val onClick: (CaptureLog) -> Unit
) : ListAdapter<CaptureLog, CaptureAdapter.ViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemCaptureBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(private val binding: ItemCaptureBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(log: CaptureLog) {
            binding.tvHost.text = log.host
            binding.tvUrl.text = log.url.take(80)
            binding.tvPackage.text = log.packageName.substringAfterLast(".")
            binding.tvMethod.text = log.method

            if (log.isBlocked) {
                binding.tvStatus.text = "🚫 已拦截"
                binding.tvStatus.setTextColor(
                    binding.root.context.getColor(android.R.color.holo_red_dark)
                )
            } else {
                binding.tvStatus.text = "✅ ${log.statusCode}"
                binding.tvStatus.setTextColor(
                    binding.root.context.getColor(android.R.color.holo_green_dark)
                )
            }

            val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
            binding.tvTime.text = sdf.format(Date(log.timestamp))

            binding.root.setOnClickListener { onClick(log) }
        }
    }

    private class DiffCallback : DiffUtil.ItemCallback<CaptureLog>() {
        override fun areItemsTheSame(old: CaptureLog, new: CaptureLog) = old.id == new.id
        override fun areContentsTheSame(old: CaptureLog, new: CaptureLog) = old == new
    }
}
