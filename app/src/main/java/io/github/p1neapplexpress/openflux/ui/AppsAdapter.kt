package io.github.p1neapplexpress.openflux.ui

import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.checkbox.MaterialCheckBox
import io.github.p1neapplexpress.openflux.R
import io.github.p1neapplexpress.openflux.data.AppItem
import io.github.p1neapplexpress.openflux.util.performAppHaptics

class AppsAdapter(
    private val onAppSelectionChanged: (AppItem) -> Unit
) : ListAdapter<AppItem, AppsAdapter.AppViewHolder>(AppDiffCallback()) {

    companion object {
        const val PAYLOAD_CHECKED = "payload_checked"
    }

    private class AppDiffCallback : DiffUtil.ItemCallback<AppItem>() {
        override fun areItemsTheSame(oldItem: AppItem, newItem: AppItem): Boolean =
            oldItem.packageName == newItem.packageName

        override fun areContentsTheSame(oldItem: AppItem, newItem: AppItem): Boolean =
            oldItem.name == newItem.name && oldItem.isSelected == newItem.isSelected

        override fun getChangePayload(oldItem: AppItem, newItem: AppItem): Any? {
            return if (oldItem.isSelected != newItem.isSelected && oldItem.name == newItem.name) {
                PAYLOAD_CHECKED
            } else {
                null
            }
        }
    }

    private var allApps: List<AppItem> = emptyList()
    private var currentQuery: String = ""

    var onListFiltered: ((count: Int) -> Unit)? = null

    fun submitApps(apps: List<AppItem>) {
        allApps = ArrayList(apps)
        applyFilter()
    }

    fun filter(query: String) {
        currentQuery = query.trim().lowercase()
        applyFilter()
    }

    private fun applyFilter() {
        val filtered = if (currentQuery.isEmpty()) {
            allApps
        } else {
            allApps.filter {
                it.name.lowercase().contains(currentQuery) ||
                it.packageName.lowercase().contains(currentQuery)
            }
        }
        submitList(filtered)
        onListFiltered?.invoke(filtered.size)
    }

    fun getDisplayedApps(): List<AppItem> = currentList

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_split_app, parent, false)
        return AppViewHolder(view)
    }

    override fun onBindViewHolder(holder: AppViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    override fun onBindViewHolder(holder: AppViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.contains(PAYLOAD_CHECKED)) {
            holder.bindChecked(getItem(position))
        } else {
            super.onBindViewHolder(holder, position, payloads)
        }
    }

    inner class AppViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val icon: ImageView = itemView.findViewById(R.id.app_icon)
        private val name: TextView = itemView.findViewById(R.id.app_name)
        private val pkg: TextView = itemView.findViewById(R.id.app_package)
        private val checkBox: MaterialCheckBox = itemView.findViewById(R.id.app_checkbox)

        fun bind(item: AppItem) {
            name.text = item.name
            pkg.text = item.packageName
            if (item.icon != null) {
                icon.setImageDrawable(item.icon)
            } else {
                icon.setImageResource(R.mipmap.ic_launcher_round)
            }
            bindChecked(item)

            itemView.setOnClickListener {
                it.performAppHaptics(HapticFeedbackConstants.VIRTUAL_KEY)
                item.isSelected = !item.isSelected
                checkBox.isChecked = item.isSelected
                onAppSelectionChanged(item)
            }
        }

        fun bindChecked(item: AppItem) {
            checkBox.isChecked = item.isSelected
        }
    }
}
