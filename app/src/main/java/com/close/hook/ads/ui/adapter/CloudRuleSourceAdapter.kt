package com.close.hook.ads.ui.adapter

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.close.hook.ads.R
import com.close.hook.ads.data.model.CloudRuleSourceSummary
import com.close.hook.ads.databinding.ItemCloudRuleSourceBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CloudRuleSourceAdapter(
    private val onToggleEnabled: (CloudRuleSourceSummary, Boolean) -> Unit,
    private val onToggleAutoUpdate: (CloudRuleSourceSummary, Boolean) -> Unit,
    private val onSync: (CloudRuleSourceSummary) -> Unit,
    private val onEdit: (CloudRuleSourceSummary) -> Unit,
    private val onDelete: (CloudRuleSourceSummary) -> Unit
) : ListAdapter<CloudRuleSourceSummary, CloudRuleSourceAdapter.ViewHolder>(DIFF_CALLBACK) {

    private var workingIds: Set<Long> = emptySet()

    fun submitWorkingIds(ids: Set<Long>) {
        if (workingIds != ids) {
            workingIds = ids
            notifyDataSetChanged()
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemCloudRuleSourceBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position), getItem(position).id in workingIds)
    }

    inner class ViewHolder(
        private val binding: ItemCloudRuleSourceBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: CloudRuleSourceSummary, isWorking: Boolean) {
            val context = binding.root.context
            binding.textUrl.text = item.url
            binding.textCounts.text = context.getString(
                R.string.cloud_rule_counts,
                item.totalCount,
                item.domainCount,
                item.urlCount,
                item.keywordCount
            )
            binding.textInterval.text = context.getString(
                R.string.cloud_rule_update_interval_value,
                item.updateIntervalHours
            )
            binding.textLastCheck.text = context.getString(
                R.string.cloud_rule_last_check,
                formatTimestamp(item.lastCheckAt, context.getString(R.string.cloud_rule_never))
            )
            binding.textLastSuccess.text = context.getString(
                R.string.cloud_rule_last_success,
                formatTimestamp(item.lastSuccessAt, context.getString(R.string.cloud_rule_never))
            )

            val lastError = item.lastErrorMessage?.trim().orEmpty()
            binding.textLastError.isVisible = lastError.isNotEmpty()
            if (lastError.isNotEmpty()) {
                binding.textLastError.text = context.getString(R.string.cloud_rule_last_error, lastError)
            }

            binding.switchEnabled.setOnCheckedChangeListener(null)
            binding.switchEnabled.isChecked = item.enabled
            binding.switchEnabled.isEnabled = !isWorking
            binding.switchEnabled.setOnCheckedChangeListener { _, checked ->
                onToggleEnabled(item, checked)
            }

            binding.switchAutoUpdate.setOnCheckedChangeListener(null)
            binding.switchAutoUpdate.isChecked = item.autoUpdateEnabled
            binding.switchAutoUpdate.isEnabled = !isWorking
            binding.switchAutoUpdate.setOnCheckedChangeListener { _, checked ->
                onToggleAutoUpdate(item, checked)
            }

            binding.buttonSync.isEnabled = !isWorking
            binding.buttonEdit.isEnabled = !isWorking
            binding.buttonDelete.isEnabled = !isWorking

            binding.buttonSync.setOnClickListener { onSync(item) }
            binding.buttonEdit.setOnClickListener { onEdit(item) }
            binding.buttonDelete.setOnClickListener { onDelete(item) }
        }
    }

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<CloudRuleSourceSummary>() {
            override fun areItemsTheSame(
                oldItem: CloudRuleSourceSummary,
                newItem: CloudRuleSourceSummary
            ): Boolean = oldItem.id == newItem.id

            override fun areContentsTheSame(
                oldItem: CloudRuleSourceSummary,
                newItem: CloudRuleSourceSummary
            ): Boolean = oldItem == newItem
        }

        @SuppressLint("SimpleDateFormat")
        private val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

        private fun formatTimestamp(timestamp: Long?, fallback: String): String {
            return timestamp?.let { DATE_FORMAT.format(Date(it)) } ?: fallback
        }
    }
}
