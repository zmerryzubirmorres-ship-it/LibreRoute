package io.github.libreroute.admin

import android.app.Dialog
import android.os.Bundle
import android.text.InputType
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.activityViewModels
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import io.github.libreroute.ui.CalmDetailDialog

/** Prepared resources are reserved together; the recipient key is learned on redemption. */
class ChannelGroupInvitationDialogFragment : DialogFragment() {
    private val vm: AdminViewModel by activityViewModels()
    private var links: TextInputEditText? = null

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val userId = requireArguments().getString("user_id").orEmpty()
        val serverId = requireArguments().getString("server_id").orEmpty()
        val serverName = vm.serverNodes.value.firstOrNull { it.id == serverId }?.name ?: serverId
        return CalmDetailDialog(requireContext(), "Приглашение для объединённых каналов").apply {
            text("Сервер: $serverName")
            text("Вставьте от 2 до 4 ссылок, каждую с новой строки. Можно объединить комнаты СберДжаз и документы Яндекса. На новом телефоне появится одно подключение.")
            text("Каждая комната и документ должны быть отдельными и свободными. Ссылки на комнаты СберДжаз должны содержать пароль. Приглашение действует 15 минут и подходит одному новому телефону.")
            val field = TextInputEditText(context).apply {
                minLines = 4
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI or
                    InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                setText(savedInstanceState?.getString("links").orEmpty())
            }
            links = field
            val inputLayout = TextInputLayout(context).apply { hint = "Ссылки на каналы"; addView(field) }
            field.doAfterTextChanged { inputLayout.error = null }
            content.addView(inputLayout)
            action("Создать одно приглашение") {
                inputLayout.error = null
                runCatching {
                    val plan = ChannelGroupPlan.fromLinks(field.text?.toString().orEmpty())
                    val user = vm.users.value.firstOrNull { it.id == userId }
                        ?: error("Откройте список пользователей заново")
                    check(vm.accessOperation.value?.state != AccessOperationState.SUBMITTING) { "Дождитесь завершения текущей операции" }
                    vm.configureUserAccess(user, listOf(EnrollmentRoute(serverId, transport = "channel-group", channel_group_plan = plan)), null)
                }.onSuccess { this@ChannelGroupInvitationDialogFragment.dismiss() }
                    .onFailure { inputLayout.error = it.message }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("links", links?.text?.toString().orEmpty())
        super.onSaveInstanceState(outState)
    }

    override fun onDestroyView() { links = null; super.onDestroyView() }

    companion object {
        fun show(manager: FragmentManager, userId: String, serverId: String) {
            if (manager.findFragmentByTag("group-invitation") != null) return
            ChannelGroupInvitationDialogFragment().apply {
                arguments = Bundle().apply { putString("user_id", userId); putString("server_id", serverId) }
            }.show(manager, "group-invitation")
        }
    }
}
