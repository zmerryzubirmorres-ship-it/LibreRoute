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
import com.google.android.material.progressindicator.LinearProgressIndicator
import io.github.libreroute.R

class AdminServerNodesAdapter(
    private val onPingClicked: (ServerNode) -> Unit,
    private val onManageClicked: (ServerNode) -> Unit
) : ListAdapter<ServerNode, AdminServerNodesAdapter.ViewHolder>(DiffCallback) {

    private var serverClientsMap: Map<String, Int> = emptyMap()

    fun setServerClientsCount(map: Map<String, Int>) {
        serverClientsMap = map
        notifyDataSetChanged()
    }

    object DiffCallback : DiffUtil.ItemCallback<ServerNode>() {
        override fun areItemsTheSame(oldItem: ServerNode, newItem: ServerNode): Boolean =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: ServerNode, newItem: ServerNode): Boolean =
            oldItem == newItem
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvFlag: TextView = view.findViewById(R.id.tv_node_flag)
        val tvName: TextView = view.findViewById(R.id.tv_node_name)
        val tvEndpoint: TextView = view.findViewById(R.id.tv_node_endpoint)
        val tvCore: TextView = view.findViewById(R.id.tv_node_core)
        val tvStatusBadge: TextView = view.findViewById(R.id.tv_node_status_badge)
        val tvLatency: TextView = view.findViewById(R.id.tv_node_latency)
        val tvClients: TextView = view.findViewById(R.id.tv_node_clients)
        val layoutMetrics: View = view.findViewById(R.id.layout_node_metrics)
        val tvCpuLabel: TextView = view.findViewById(R.id.tv_node_cpu_label)
        val tvCpuVal: TextView = view.findViewById(R.id.tv_node_cpu_val)
        val pbCpu: LinearProgressIndicator = view.findViewById(R.id.pb_node_cpu)
        val tvRamLabel: TextView = view.findViewById(R.id.tv_node_ram_label)
        val tvRamVal: TextView = view.findViewById(R.id.tv_node_ram_val)
        val pbRam: LinearProgressIndicator = view.findViewById(R.id.pb_node_ram)
        val tvError: TextView = view.findViewById(R.id.tv_node_error)
        val btnPing: MaterialButton = view.findViewById(R.id.btn_node_ping)
        val btnManage: MaterialButton = view.findViewById(R.id.btn_node_manage)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_admin_server_node, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)
        val context = holder.itemView.context

        holder.tvFlag.text = when (item.nodeType) {
            ServerNodeType.ROUTER_KEENETIC -> "📡"
            ServerNodeType.ROUTER_OPENWRT -> "📶"
            ServerNodeType.CUSTOM -> "⚙"
            else -> "🖥"
        }

        holder.tvName.text = item.name
        val osSuffix = if (!item.inventory?.osName.isNullOrBlank()) " (${item.inventory?.osName})" else ""
        holder.tvEndpoint.text = "${item.host}:${item.port} • ${item.nodeType.displayName}$osSuffix"
        holder.tvCore.text = coreVersionSummary(item.coreObservation)

        val (statusText, statusColor) = when (item.status) {
            ServerNodeStatus.ONLINE -> "В СЕТИ" to ContextCompat.getColor(context, R.color.colorSuccess)
            ServerNodeStatus.DEGRADED -> "ЗАМЕДЛЕН" to ContextCompat.getColor(context, R.color.state_connecting)
            ServerNodeStatus.OFFLINE -> "ОФЛАЙН" to ContextCompat.getColor(context, R.color.colorError)
            ServerNodeStatus.UNKNOWN -> "НЕ ПРОВЕРЕН" to ContextCompat.getColor(context, R.color.text_secondary)
        }
        holder.tvStatusBadge.text = statusText
        holder.tvStatusBadge.setTextColor(statusColor)

        val inv = item.inventory
        if (inv != null && inv.probedAt > 0) {
            holder.tvLatency.text = if (item.status == ServerNodeStatus.ONLINE) "⚡ Онлайн" else "⚡ Опрошен"
            holder.tvLatency.setTextColor(ContextCompat.getColor(context, R.color.colorSuccess))
        } else {
            holder.tvLatency.text = "⚡ Не опрошен"
            holder.tvLatency.setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
        }

        val clientsCount = serverClientsMap[item.id] ?: 0
        holder.tvClients.text = if (clientsCount == 1) "👥 1 клиент" else "👥 $clientsCount клиентов"

        if (inv != null && inv.probeError == null && (inv.cpuCores > 0 || inv.ramTotalMb > 0)) {
            holder.layoutMetrics.visibility = View.VISIBLE
            holder.tvError.visibility = View.GONE

            if (inv.cpuCores > 0) {
                holder.tvCpuLabel.text = "CPU (${inv.cpuCores} cores, ${inv.arch})"
                holder.tvCpuVal.text = "Готов"
                holder.pbCpu.progress = 25
            } else {
                holder.tvCpuLabel.text = "CPU"
                holder.tvCpuVal.text = "—"
                holder.pbCpu.progress = 0
            }

            if (inv.ramTotalMb > 0) {
                val usedMb = (inv.ramTotalMb - inv.ramFreeMb).coerceAtLeast(0)
                val percent = ((usedMb.toDouble() / inv.ramTotalMb.toDouble()) * 100).toInt().coerceIn(0, 100)
                holder.tvRamVal.text = "$usedMb / ${inv.ramTotalMb} MB ($percent%)"
                holder.pbRam.progress = percent
            } else {
                holder.tvRamVal.text = "—"
                holder.pbRam.progress = 0
            }
        } else if (inv?.probeError != null) {
            holder.layoutMetrics.visibility = View.GONE
            holder.tvError.visibility = View.VISIBLE
            holder.tvError.text = "Ошибка опроса: ${inv.probeError}"
        } else {
            holder.layoutMetrics.visibility = View.GONE
            holder.tvError.visibility = View.GONE
        }

        holder.btnPing.setOnClickListener { onPingClicked(item) }
        holder.btnManage.setOnClickListener { onManageClicked(item) }
    }
}
