package io.github.libreroute.admin

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import io.github.libreroute.R

class AdminAssignmentsAdapter(
    private val onIssueQrClicked: (AdminAssignment) -> Unit,
    private val onRevokeClicked: (AdminAssignment) -> Unit,
    private val onItemClicked: ((AdminAssignment) -> Unit)? = null
) : ListAdapter<AdminAssignment, AdminAssignmentsAdapter.ViewHolder>(DiffCallback) {

    private var userNamesMap: Map<String, String> = emptyMap()

    fun setUserNames(map: Map<String, String>) {
        userNamesMap = map
        notifyDataSetChanged()
    }

    object DiffCallback : DiffUtil.ItemCallback<AdminAssignment>() {
        override fun areItemsTheSame(oldItem: AdminAssignment, newItem: AdminAssignment): Boolean =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: AdminAssignment, newItem: AdminAssignment): Boolean =
            oldItem == newItem
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvUserName: TextView = view.findViewById(R.id.tv_assignment_user_name)
        val tvStatusBadge: TextView = view.findViewById(R.id.tv_assignment_status_badge)
        val tvDevice: TextView = view.findViewById(R.id.tv_assignment_device)
        val tvRoute: TextView = view.findViewById(R.id.tv_assignment_route)
        val tvError: TextView = view.findViewById(R.id.tv_assignment_error)
        val btnQr: MaterialButton = view.findViewById(R.id.btn_assignment_qr)
        val btnRevoke: MaterialButton = view.findViewById(R.id.btn_assignment_revoke)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_admin_assignment, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)
        val context = holder.itemView.context

        val resolvedUserName = item.userName?.takeIf { it.isNotBlank() }
            ?: userNamesMap[item.userId]
            ?: item.userId
        holder.tvUserName.text = "$resolvedUserName (${item.userId})"

        val (statusText, statusColor) = when {
            item.isFailed -> "ОШИБКА" to ContextCompat.getColor(context, R.color.colorError)
            item.isPendingReconciliation -> "СВЕРКА" to ContextCompat.getColor(context, R.color.state_connecting)
            item.status.equals(AdminAssignment.STATUS_ACTIVE, ignoreCase = true) -> "АКТИВЕН" to ContextCompat.getColor(context, R.color.colorSuccess)
            item.status.equals(AdminAssignment.STATUS_READY, ignoreCase = true) -> "ГОТОВ" to ContextCompat.getColor(context, R.color.colorSuccess)
            item.status.equals(AdminAssignment.STATUS_INVITED, ignoreCase = true) -> "ПРИГЛАШЕН" to ContextCompat.getColor(context, R.color.m3_primary)
            item.status.equals(AdminAssignment.STATUS_PROVISIONING, ignoreCase = true) -> "ВЫДАЧА" to ContextCompat.getColor(context, R.color.state_connecting)
            item.status.equals(AdminAssignment.STATUS_SUSPENDED, ignoreCase = true) -> "ПРИОСТАНОВЛЕН" to ContextCompat.getColor(context, R.color.state_connecting)
            item.status.equals(AdminAssignment.STATUS_REVOKED, ignoreCase = true) -> "ОТОЗВАН" to ContextCompat.getColor(context, R.color.colorError)
            else -> item.status.uppercase() to ContextCompat.getColor(context, R.color.text_secondary)
        }

        holder.tvStatusBadge.text = statusText
        holder.tvStatusBadge.setTextColor(statusColor)

        val revNum = if (item.appliedRevision > 0L) item.appliedRevision else item.revision
        val devInfo = if (!item.deviceFingerprint.isNullOrBlank()) {
            "dev: ${item.deviceFingerprint.take(12)} • Ревизия #$revNum"
        } else {
            "${item.deviceId} • Ревизия #$revNum"
        }
        holder.tvDevice.text = devInfo
        holder.tvRoute.text = "${item.serverId} (${item.transport})"

        if (item.isFailed || !item.lastError.isNullOrBlank()) {
            holder.tvError.visibility = View.VISIBLE
            val backoffSuffix = if (item.isInBackoff) {
                val secLeft = item.nextRetryAt - (System.currentTimeMillis() / 1000)
                " (повтор через ${secLeft}с)"
            } else ""
            holder.tvError.text = (item.lastError ?: "Ошибка применения") + backoffSuffix
        } else {
            holder.tvError.visibility = View.GONE
        }

        val canIssueQr = item.status.equals(AdminAssignment.STATUS_READY, ignoreCase = true) ||
            item.status.equals(AdminAssignment.STATUS_INVITED, ignoreCase = true) ||
            item.status.equals(AdminAssignment.STATUS_ACTIVE, ignoreCase = true)
        holder.btnQr.visibility = if (canIssueQr) View.VISIBLE else View.GONE
        holder.btnQr.setOnClickListener { onIssueQrClicked(item) }

        val canRevoke = !item.status.equals(AdminAssignment.STATUS_REVOKED, ignoreCase = true)
        holder.btnRevoke.visibility = if (canRevoke) View.VISIBLE else View.GONE
        holder.btnRevoke.setOnClickListener { onRevokeClicked(item) }

        holder.itemView.setOnClickListener { onItemClicked?.invoke(item) }
    }
}
