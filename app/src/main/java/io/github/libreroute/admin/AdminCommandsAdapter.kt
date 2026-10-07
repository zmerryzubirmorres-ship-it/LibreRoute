package io.github.libreroute.admin

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import io.github.libreroute.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AdminCommandsAdapter : ListAdapter<AdminCommandRecord, AdminCommandsAdapter.ViewHolder>(DiffCallback) {

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    object DiffCallback : DiffUtil.ItemCallback<AdminCommandRecord>() {
        override fun areItemsTheSame(oldItem: AdminCommandRecord, newItem: AdminCommandRecord): Boolean =
            oldItem.opId == newItem.opId

        override fun areContentsTheSame(oldItem: AdminCommandRecord, newItem: AdminCommandRecord): Boolean =
            oldItem == newItem
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvType: TextView = view.findViewById(R.id.tv_cmd_type)
        val tvChannel: TextView = view.findViewById(R.id.tv_cmd_channel)
        val tvStatus: TextView = view.findViewById(R.id.tv_cmd_status)
        val tvOpId: TextView = view.findViewById(R.id.tv_cmd_opid)
        val tvTime: TextView = view.findViewById(R.id.tv_cmd_time)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_command_record, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val cmd = getItem(position)
        val context = holder.itemView.context

        holder.tvType.text = cmd.commandType.uppercase()
        holder.tvChannel.text = when (cmd.channel) {
            "document" -> context.getString(R.string.admin_channel_document)
            "ssh_vds" -> context.getString(R.string.admin_channel_vds)
            "ssh_netcraze" -> context.getString(R.string.admin_channel_netcraze)
            else -> cmd.channel
        }

        val statusColor = when (cmd.status) {
            AdminCommandRecord.STATUS_VERIFIED, AdminCommandRecord.STATUS_APPLIED -> ContextCompat.getColor(context, R.color.colorSuccess)
            AdminCommandRecord.STATUS_FAILED -> ContextCompat.getColor(context, R.color.colorError)
            else -> ContextCompat.getColor(context, R.color.state_connecting)
        }
        holder.tvStatus.text = "${cmd.status} (r${cmd.revision})"
        holder.tvStatus.setTextColor(statusColor)

        holder.tvOpId.text = "op: ${cmd.opId.take(13)}..."
        holder.tvTime.text = timeFormat.format(Date(cmd.timestamp))
    }
}
