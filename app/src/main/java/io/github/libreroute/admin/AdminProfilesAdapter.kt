package io.github.libreroute.admin

import android.content.res.ColorStateList
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

class AdminProfilesAdapter(
    private val onInviteClicked: (ManagedProfile) -> Unit,
    private val onToggleSuspendClicked: (ManagedProfile) -> Unit,
    private val onRevokeClicked: (ManagedProfile) -> Unit,
    private val onAuthSyncClicked: ((ManagedProfile) -> Unit)? = null
) : ListAdapter<ManagedProfile, AdminProfilesAdapter.ViewHolder>(DiffCallback) {

    var canRevoke: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                notifyDataSetChanged()
            }
        }

    object DiffCallback : DiffUtil.ItemCallback<ManagedProfile>() {
        override fun areItemsTheSame(oldItem: ManagedProfile, newItem: ManagedProfile): Boolean =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: ManagedProfile, newItem: ManagedProfile): Boolean =
            oldItem == newItem
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvName: TextView = view.findViewById(R.id.tv_profile_name)
        val tvStatusBadge: TextView = view.findViewById(R.id.tv_profile_status_badge)
        val tvDetails: TextView = view.findViewById(R.id.tv_profile_details)
        val btnInvite: MaterialButton = view.findViewById(R.id.btn_profile_invite)
        val btnToggleSuspend: MaterialButton = view.findViewById(R.id.btn_profile_toggle_suspend)
        val btnRevoke: MaterialButton = view.findViewById(R.id.btn_profile_revoke)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_managed_profile, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val profile = getItem(position)
        val context = holder.itemView.context

        holder.tvName.text = profile.name

        val statusText: String
        val statusColor: Int
        when {
            profile.isRevoked -> {
                statusText = context.getString(R.string.admin_status_revoked)
                statusColor = ContextCompat.getColor(context, R.color.colorError)
                holder.btnToggleSuspend.visibility = View.GONE
                holder.btnInvite.visibility = View.GONE
                holder.btnRevoke.visibility = View.GONE
            }
            profile.isSuspended -> {
                statusText = context.getString(R.string.admin_status_suspended)
                statusColor = ContextCompat.getColor(context, R.color.state_connecting)
                holder.btnToggleSuspend.visibility = View.VISIBLE
                holder.btnToggleSuspend.text = context.getString(R.string.admin_btn_resume)
                holder.btnInvite.visibility = View.GONE
                holder.btnRevoke.visibility = if (canRevoke) View.VISIBLE else View.GONE
            }
            else -> {
                statusText = context.getString(R.string.admin_status_ready)
                statusColor = ContextCompat.getColor(context, R.color.colorSuccess)
                holder.btnToggleSuspend.visibility = View.VISIBLE
                holder.btnToggleSuspend.text = context.getString(R.string.admin_btn_suspend)
                holder.btnInvite.visibility = View.VISIBLE
                holder.btnRevoke.visibility = if (canRevoke) View.VISIBLE else View.GONE
            }
        }

        holder.tvStatusBadge.text = statusText
        holder.tvStatusBadge.setTextColor(statusColor)

        val udpLabel = if (profile.udpEnabled) "вкл" else "выкл"
        val sessionLabel = if (profile.sessionNegotiated) "вкл" else "выкл"
        holder.tvDetails.text = "Ревизия ${profile.revision} • ${profile.transportType} • Сеанс: $sessionLabel • UDP: $udpLabel"

        holder.btnInvite.setOnClickListener { onInviteClicked(profile) }
        holder.btnToggleSuspend.setOnClickListener { onToggleSuspendClicked(profile) }
        holder.btnRevoke.setOnClickListener { onRevokeClicked(profile) }
        holder.itemView.setOnClickListener { onAuthSyncClicked?.invoke(profile) }
    }
}
