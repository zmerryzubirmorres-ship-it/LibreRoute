package io.github.libreroute.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import io.github.libreroute.R
import java.text.DateFormat
import java.util.Date

/** Only user-visible labels and controlled causes enter a message; never credentials or raw logs. */
object AdminContact {
    enum class Reason { INVITATION_EXPIRED, INVITATION_USED, ACCESS_REVOKED, JITSI_MODERATOR, JITSI_EXPIRED, MQTT }

    fun message(reason: Reason, routeName: String? = null, now: Long = System.currentTimeMillis()): String {
        val safeName = routeName.orEmpty().replace(Regex("https?://\\S+|[A-Za-z0-9_+/=-]{32,}"), "…")
            .replace(Regex("[\\r\\n\\t]"), " ").take(80)
        val explanation = when (reason) {
            Reason.INVITATION_EXPIRED -> "Приглашение истекло. Прошу выдать новое приглашение."
            Reason.INVITATION_USED -> "Приглашение уже использовано. Прошу проверить устройство и выдать новое приглашение."
            Reason.ACCESS_REVOKED -> "Сервер подтвердил отзыв доступа. Прошу проверить мои права и сообщить дальнейшие действия."
            Reason.JITSI_MODERATOR -> "Комната Jitsi не запущена. Прошу организатора открыть комнату в браузере и подтвердить готовность гостевого подключения."
            Reason.JITSI_EXPIRED -> "Токен организатора Jitsi истёк. Прошу организатора снова войти и запустить комнату. Передавать токен пользователю не требуется."
            Reason.MQTT -> "Не удалось подключиться к маршруту MQTT. Прошу проверить брокер, права канала и готовность удалённого узла."
        }
        return listOf("LibreRoute", safeName.takeIf { it.isNotBlank() }?.let { "Маршрут: $it" },
            "Время: ${DateFormat.getDateTimeInstance().format(Date(now))}", explanation).filterNotNull().joinToString("\n")
    }

    fun show(context: Context, reason: Reason, routeName: String? = null) {
        val text = message(reason, routeName)
        val screen = CalmDetailDialog(context, context.getString(R.string.calm_message_prepared))
        screen.text(text)
        screen.action(context.getString(R.string.calm_share_admin)) {
            val send = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text) }
            runCatching { context.startActivity(Intent.createChooser(send, context.getString(R.string.calm_share_admin))) }
                .onFailure { Toast.makeText(context, "Скопируйте текст и передайте его администратору", Toast.LENGTH_LONG).show() }
        }
        screen.action(context.getString(R.string.calm_copy_message), secondary = true) {
            (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(ClipData.newPlainText("LibreRoute", text))
            Toast.makeText(context, R.string.calm_message_copied, Toast.LENGTH_LONG).show()
        }
        screen.show()
    }
}
