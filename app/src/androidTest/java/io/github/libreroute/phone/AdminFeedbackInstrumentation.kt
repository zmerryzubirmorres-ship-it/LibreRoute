package io.github.libreroute.phone

import android.app.Instrumentation
import android.view.ContextThemeWrapper
import android.widget.Button
import android.widget.LinearLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.google.android.material.button.MaterialButton
import io.github.libreroute.R
import io.github.libreroute.admin.AdminCommandControl
import io.github.libreroute.admin.AdminCommandFeedback
import io.github.libreroute.ui.DialogScreens

/** Real Android views with synthetic command states. Does not contact a server or change app data. */
internal fun Instrumentation.runAdminFeedbackChecks(): List<String> {
    var result: Result<Unit>? = null
    runOnMainSync { result = runCatching { checkAdminFeedback() } }
    requireNotNull(result).getOrThrow()
    return listOf("ADMIN_FEEDBACK_PASS: primary progress, secondary labels, workflow gaps, recreated views, restoration")
}

private fun Instrumentation.checkAdminFeedback() {
        val context = ContextThemeWrapper(targetContext, R.style.Theme_SoxMax)
        val owner = object : LifecycleOwner {
            val registry = LifecycleRegistry(this)
            override val lifecycle: Lifecycle get() = registry
        }
        owner.registry.currentState = Lifecycle.State.STARTED
        val root = LinearLayout(context)
        val control = AdminCommandControl()
        val primaryId = DialogScreens.stateId("person-configure-access")
        var pending = false
        fun button(label: String, id: Int = android.view.View.generateViewId()) = MaterialButton(context).apply {
            this.id = id; text = label; root.addView(this)
        }
        var primary = button("Настроить доступ", primaryId)
        var suspend = button("Приостановить доступ")
        var delete = button("Удалить с сервера…")
        val unavailable = button("Недоступно").apply { isEnabled = false }
        val clear = AdminCommandFeedback.bind(owner, root, control, progressViewIds = setOf(primaryId),
            pendingLabel = { if (pending) "Настраиваем доступ…" else null })
        fun draw() { root.viewTreeObserver.dispatchOnPreDraw() }
        fun assertBusy(label: String) {
            draw()
            check(primary.text.toString() == label) { "Primary: ${primary.text}" }
            check(suspend.text.toString() == "Приостановить доступ")
            check(delete.text.toString() == "Удалить с сервера…")
            check(listOf<Button>(primary, suspend, delete, unavailable).none { it.isEnabled })
        }
        try {
            pending = true
            assertBusy("Настраиваем доступ…")
            control.run("synthetic-server", "issue-portable-jazz", true, { _, _ -> error("Unexpected cancellation") }) {
                assertBusy("Проверяем доступ…")
                control.phase(AdminCommandControl.Phase.FORMING); assertBusy("Формируем команду…")
                control.phase(AdminCommandControl.Phase.SENDING); assertBusy("Выполняем…")
                control.stage("Подключаемся к серверу…"); assertBusy("Подключаемся к серверу…")
                root.removeAllViews()
                primary = button("Настроить доступ", primaryId)
                suspend = button("Приостановить доступ")
                delete = button("Удалить с сервера…")
                assertBusy("Подключаемся к серверу…")
            }
            // The workflow stays busy between individual signed commands.
            assertBusy("Настраиваем доступ…")
            pending = false; draw()
            check(primary.text.toString() == "Настроить доступ")
            check(listOf<Button>(primary, suspend, delete).all { it.isEnabled })
            check(!unavailable.isEnabled)
            check(suspend.text.toString() == "Приостановить доступ" && delete.text.toString() == "Удалить с сервера…")
        } finally { clear(); owner.registry.currentState = Lifecycle.State.DESTROYED }
}
