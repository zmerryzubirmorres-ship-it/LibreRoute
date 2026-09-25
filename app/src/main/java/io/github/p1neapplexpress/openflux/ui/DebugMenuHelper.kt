package io.github.p1neapplexpress.openflux.ui

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.bottomsheet.BottomSheetDialog
import io.github.p1neapplexpress.openflux.BuildConfig
import io.github.p1neapplexpress.openflux.R
import io.github.p1neapplexpress.openflux.util.AppUpdateChecker
import io.github.p1neapplexpress.openflux.util.Logx
import io.github.p1neapplexpress.openflux.util.performAppHaptics

object DebugMenuHelper {

    fun show(activity: Activity) {
        val bottomSheet = BottomSheetDialog(activity)
        val sheetView = activity.layoutInflater.inflate(R.layout.bottom_sheet_debug_menu, null)
        bottomSheet.setContentView(sheetView)

        // 1. Force check update
        sheetView.findViewById<View>(R.id.card_debug_check_update).setOnClickListener {
            it.performAppHaptics(HapticFeedbackConstants.VIRTUAL_KEY)
            bottomSheet.dismiss()
            Toast.makeText(activity, "Проверка обновлений на GitHub…", Toast.LENGTH_SHORT).show()
            AppUpdateChecker.checkForUpdate(activity, force = true) { hasUpdate, versionOrError ->
                if (!hasUpdate) {
                    if (!AppUpdateChecker.isVersionTag(versionOrError)) {
                        Toast.makeText(activity, "Ошибка проверки: $versionOrError", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(activity, activity.getString(R.string.debug_up_to_date, versionOrError), Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        // 2. Test update dialog (simulation)
        sheetView.findViewById<View>(R.id.card_debug_test_update_dialog).setOnClickListener {
            it.performAppHaptics(HapticFeedbackConstants.VIRTUAL_KEY)
            bottomSheet.dismiss()
            AppUpdateChecker.showTestUpdateDialog(activity)
        }

        // 3. Reset 24h timer
        sheetView.findViewById<View>(R.id.card_debug_reset_update_timer).setOnClickListener {
            it.performAppHaptics(HapticFeedbackConstants.VIRTUAL_KEY)
            AppUpdateChecker.resetLastCheckTime(activity)
            Toast.makeText(activity, R.string.debug_timer_reset_toast, Toast.LENGTH_SHORT).show()
        }

        // 4. Generate test logs
        sheetView.findViewById<View>(R.id.card_debug_generate_logs).setOnClickListener {
            it.performAppHaptics(HapticFeedbackConstants.VIRTUAL_KEY)
            Logx.d("TestDebug", "Тестовый отладочный лог (DEBUG). Проверка цвета [D]")
            Logx.i("TestInfo", "Тестовый информационный лог (INFO). Проверка цвета [I]")
            Logx.w("TestWarning", "Тестовое предупреждение (WARNING). Проверка цвета [W]")
            Logx.e("TestError", "Тестовая ошибка (ERROR). Проверка цвета [E]", RuntimeException("Тестовый стектрейс исключения"))
            Toast.makeText(activity, R.string.debug_logs_generated_toast, Toast.LENGTH_SHORT).show()
        }

        // 5. Toggle verbose logging
        val tvVerboseStatus = sheetView.findViewById<TextView>(R.id.tv_debug_verbose_status)
        fun updateVerboseStatus() {
            val level = if (Logx.isVerbose) "DEBUG (подробный)" else "INFO (по умолчанию)"
            tvVerboseStatus.text = activity.getString(R.string.debug_toggle_verbose_desc, level)
        }
        updateVerboseStatus()

        sheetView.findViewById<View>(R.id.card_debug_toggle_verbose).setOnClickListener {
            it.performAppHaptics(HapticFeedbackConstants.VIRTUAL_KEY)
            Logx.setVerbose(!Logx.isVerbose)
            updateVerboseStatus()
            val state = if (Logx.isVerbose) "DEBUG" else "INFO"
            Toast.makeText(activity, "Уровень логов переключен на: $state", Toast.LENGTH_SHORT).show()
        }

        // 6. Test delete dialog
        sheetView.findViewById<View>(R.id.card_debug_test_delete_dialog).setOnClickListener {
            it.performAppHaptics(HapticFeedbackConstants.VIRTUAL_KEY)
            bottomSheet.dismiss()
            showTestDeleteDialog(activity)
        }

        // 7. System & build info
        val tvBuildInfo = sheetView.findViewById<TextView>(R.id.tv_debug_build_info)
        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"
        tvBuildInfo.text = "Fluxon v${BuildConfig.VERSION_NAME} (${BuildConfig.BUILD_TYPE}) • Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) • ABI: $abi"

        bottomSheet.show()
    }

    private fun showTestDeleteDialog(activity: Activity) {
        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val dialogView = activity.layoutInflater.inflate(R.layout.dialog_confirm_delete, null)
        dialog.setContentView(dialogView)

        dialogView.findViewById<TextView>(R.id.dialog_title).text = activity.getString(R.string.delete_config_title)
        dialogView.findViewById<TextView>(R.id.dialog_message).text =
            "«Тестовая конфигурация»\n${activity.getString(R.string.delete_config_msg)}"

        dialogView.findViewById<View>(R.id.btn_cancel).setOnClickListener {
            it.performAppHaptics(HapticFeedbackConstants.VIRTUAL_KEY)
            dialog.dismiss()
        }

        dialogView.findViewById<View>(R.id.btn_delete).setOnClickListener {
            it.performAppHaptics(HapticFeedbackConstants.VIRTUAL_KEY)
            dialog.dismiss()
            Toast.makeText(activity, "Тестовое удаление подтверждено", Toast.LENGTH_SHORT).show()
        }

        dialog.show()

        val width = (activity.resources.displayMetrics.widthPixels * 0.88).toInt()
        dialog.window?.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
    }
}
