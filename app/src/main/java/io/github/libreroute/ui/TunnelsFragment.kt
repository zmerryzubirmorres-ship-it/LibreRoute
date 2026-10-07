package io.github.libreroute.ui

import android.app.Activity.RESULT_OK
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.ViewGroup
import android.view.Window
import android.view.animation.AccelerateDecelerateInterpolator
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import android.text.Editable
import android.text.TextWatcher
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.activityViewModels
import androidx.transition.ChangeBounds
import androidx.transition.Fade
import androidx.transition.TransitionManager
import androidx.transition.TransitionSet
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import io.github.g00fy2.quickie.QRResult
import io.github.g00fy2.quickie.ScanQRCode
import io.github.libreroute.R
import io.github.libreroute.util.TunnelErrorClassifier
import io.github.libreroute.util.AppSettings
import io.github.libreroute.util.QrGenerator
import io.github.libreroute.data.TransportType
import io.github.libreroute.data.Tunnel
import io.github.libreroute.data.TunnelHealth
import io.github.libreroute.data.TunnelState
import io.github.libreroute.event.AppEvent
import io.github.libreroute.event.EventBus
import io.github.libreroute.event.JitsiAuthReason
import io.github.libreroute.util.QrDecoder
import io.github.libreroute.util.ThemePreferences
import io.github.libreroute.util.TunnelLinkParser
import io.github.libreroute.util.toUptimeHms
import io.github.libreroute.util.performAppHaptics
import io.github.libreroute.admin.JitsiAuthActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.util.Locale

class TunnelsFragment : BaseFragment() {

    private val vm: TunnelsViewModel by activityViewModels()

    private lateinit var connectButton: View
    private lateinit var configSelector: View
    private lateinit var configDot: View
    private lateinit var tunnelName: TextView
    private lateinit var chevron: ImageView
    private lateinit var configPing: TextView
    private lateinit var statusText: TextView
    private lateinit var homeStateTitle: TextView
    private lateinit var uptimeContainer: View
    private lateinit var uptimeText: TextView
    private lateinit var metricPing: TextView
    private lateinit var metricDownload: TextView
    private lateinit var metricUpload: TextView
    private lateinit var metricHint: TextView
    private val binding get() = this
    private lateinit var appSettings: AppSettings
    private lateinit var cardAdminConsole: MaterialCardView
    private lateinit var adminConsoleRoleBadge: TextView
    private lateinit var cardSplitTunnel: MaterialCardView
    private lateinit var splitTunnelIcon: ImageView
    private lateinit var splitTunnelTitle: TextView
    private lateinit var splitTunnelSubtitle: TextView
    private var lastSplitSnapshot: String? = null

    private var currentVisualState: TunnelState? = null
    private var isInitialStateBinding = true
    private var popup: PopupWindow? = null
    private var configSheetDialog: BottomSheetDialog? = null
    private var selectedCalmTab = R.id.nav_home

    private val jitsiAuthLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            EventBus.clearPendingJitsiIssue()
            vm.restartCurrent()
        }
    }

    private fun setupCalmNavigation(root: View, saved: Bundle?) {
        val navigation = root.findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(R.id.userNavigation)
          selectedCalmTab = saved?.getInt("calm_tab", R.id.nav_home) ?: R.id.nav_home
          if (selectedCalmTab == R.id.nav_routes || selectedCalmTab == R.id.nav_add) selectedCalmTab = R.id.nav_home
          navigation.setOnItemSelectedListener { item ->
              if (item.itemId == R.id.nav_add) {
                  // The center action is deliberately an action, not a persistent tab.
                  // It mirrors Amnezia: every user configuration starts here.
                  showAddQrBottomSheet()
                  navigation.post { navigation.selectedItemId = R.id.nav_home }
                  return@setOnItemSelectedListener false
              }
              selectedCalmTab = item.itemId
            root.findViewById<View>(R.id.homePanel).isVisible = item.itemId == R.id.nav_home
            root.findViewById<View>(R.id.configSelector).isVisible = item.itemId == R.id.nav_home
            root.findViewById<View>(R.id.routesPanel).isVisible = item.itemId == R.id.nav_routes
            root.findViewById<View>(R.id.settingsPanel).isVisible = item.itemId == R.id.nav_settings
            if (item.itemId == R.id.nav_routes) renderRoutes()
            if (item.itemId == R.id.nav_settings) renderSettingsHub()
            true
        }
        updateRoutesTab()
        navigation.selectedItemId = selectedCalmTab
        root.findViewById<View>(R.id.openAuthCheck).setOnClickListener {
            val selected = vm.selected.value
            if (selected?.transportType == "jitsi") showJitsiRoomHelp(selected)
            else (activity as? MainActivity)?.openPendingYandexAuthIssue()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("calm_tab", selectedCalmTab)
    }

    private fun updateRoutesTab() {
        val navigation = view?.findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(R.id.userNavigation) ?: return
        // Routes remain reachable from the configuration selector; the primary
        // bottom bar follows the Amnezia pattern (home, add, settings).
        navigation.menu.findItem(R.id.nav_routes).isVisible = false
        if (selectedCalmTab == R.id.nav_routes) navigation.selectedItemId = R.id.nav_home
    }

    private fun renderRoutes() {
        val rows = view?.findViewById<android.widget.LinearLayout>(R.id.routesPanelRows) ?: return
        if (selectedCalmTab != R.id.nav_routes) return
        rows.removeAllViews()
        vm.tunnels.value.forEach { item ->
            val tunnel = item.tunnel
            val state = when (vm.getTunnelHealth(tunnel.id)) {
                TunnelHealth.AVAILABLE -> "Доступен"
                TunnelHealth.CHECKING -> "Проверяем"
                TunnelHealth.UNAVAILABLE -> "Не удалось проверить"
                TunnelHealth.UNKNOWN -> "Нет данных"
            }
            val selected = tunnel.id == vm.selectedTunnelId
            rows.addView(CalmViews.row(requireContext(), tunnel.name, "${if (selected) "Выбран · " else ""}$state", selected) {
                vm.selectTunnel(tunnel)
                vm.checkSelectedHealth(force = true)
            }.apply { setOnLongClickListener { showItemContextMenu(it, tunnel); true } })
        }
        rows.addView(CalmViews.row(requireContext(), "Импортировать приглашение", "QR-код, изображение или ссылка") { showAddQrBottomSheet() })
    }

    private fun renderSettingsHub() {
        val rows = view?.findViewById<android.widget.LinearLayout>(R.id.settingsPanelRows) ?: return
          rows.removeAllViews()
          val repo = io.github.libreroute.admin.AdminRepository.getInstance(requireContext())
          val role = repo.getRole()
          val canManageServers = role == io.github.libreroute.admin.AdminRole.OWNER ||
              role == io.github.libreroute.admin.AdminRole.OPERATOR ||
              role == io.github.libreroute.admin.AdminRole.VIEWER
          rows.addView(CalmViews.row(requireContext(), "Серверы", "Добавление и настройка серверов") {
              val target = if (canManageServers) io.github.libreroute.admin.AdminActivity::class.java
              else io.github.libreroute.admin.AdminSetupActivity::class.java
              startActivity(Intent(requireContext(), target).apply {
                  if (!canManageServers) putExtra("connect_server", true)
              })
          })
          rows.addView(CalmViews.row(requireContext(), "Оформление", "Тема и элементы интерфейса") {
              startActivity(Intent(requireContext(), AppearanceActivity::class.java))
          })
        rows.addView(CalmViews.row(requireContext(), "Дополнительно", "DNS, приложения, MTU, журналы и резервная копия") {
            startActivity(Intent(requireContext(), SettingsActivity::class.java))
        })
          val adminSubtitle = when (role) {
            io.github.libreroute.admin.AdminRole.OWNER -> "Владелец · управление серверами и доступом"
            io.github.libreroute.admin.AdminRole.OPERATOR -> "Оператор · назначенные серверы"
            io.github.libreroute.admin.AdminRole.VIEWER -> "Наблюдатель · просмотр состояния"
            io.github.libreroute.admin.AdminRole.PENDING -> "Запрос прав ожидает подтверждения"
            io.github.libreroute.admin.AdminRole.NONE -> "Настроить сервер по SSH или принять приглашение"
        }
          rows.addView(CalmViews.row(requireContext(), "Права администратора", adminSubtitle) {
            val target = if (role == io.github.libreroute.admin.AdminRole.OWNER || role == io.github.libreroute.admin.AdminRole.OPERATOR || role == io.github.libreroute.admin.AdminRole.VIEWER)
                io.github.libreroute.admin.AdminActivity::class.java else io.github.libreroute.admin.AdminSetupActivity::class.java
            startActivity(Intent(requireContext(), target))
        })
        rows.addView(CalmViews.row(requireContext(), "Импортировать приглашение", "Получить маршруты по приглашению") { showAddQrBottomSheet() })
    }

    private fun renderAuthAttention() {
        val button = view?.findViewById<com.google.android.material.button.MaterialButton>(R.id.openAuthCheck) ?: return
        val selected = vm.selected.value
        val jitsi = selected?.transportType == "jitsi"
        val roomUrl = selected?.takeIf { jitsi }?.let { routeUrl(it) }.orEmpty()
        // Guests do not need a local moderator JWT. Only an actual room/auth refusal needs attention.
        val pendingJitsi = jitsi && EventBus.pendingJitsiIssue()?.roomUrl == roomUrl
        val pendingYandex = selected?.transportType in setOf("yandex", "vyandex") &&
            selected?.let { routeUrl(it) } == EventBus.pendingYandexAuthIssue()?.documentUrl
        button.isVisible = pendingYandex || (jitsi && vm.active.value !is TunnelState.Running)
        button.text = if (jitsi) "Открыть комнату Jitsi" else "Открыть проверку"
        if (pendingYandex || pendingJitsi) {
            renderConnectAccent(R.color.state_connecting)
            statusText.text = if (pendingJitsi) "Нужен запуск или обновление комнаты" else "Нужна проверка"
            val jitsiIssue = EventBus.pendingJitsiIssue()
            view?.findViewById<TextView>(R.id.homeEvent)?.text = if (pendingJitsi && jitsiIssue?.reason == JitsiAuthReason.TOKEN_EXPIRED)
                "Токен организатора истёк. Организатору нужно снова войти через браузер, затем проверьте комнату"
                else if (pendingJitsi)
                "Попросите администратора запустить комнату, затем проверьте подключение ещё раз"
                else "Откройте страницу и завершите проверку. Затем повторите подключение"
            metricPing.text = "—"; metricDownload.text = "—"; metricUpload.text = "—"
            metricHint.text = "Измерение недоступно"
        }
    }

    private fun routeUrl(tunnel: Tunnel): String {
        val payload = tunnel.transportConnPayload
        return payload.firstOrNull { it.startsWith("--url=") }?.substringAfter('=')
            ?: payload.indexOf("--url").takeIf { it >= 0 }?.let { payload.getOrNull(it + 1) }.orEmpty()
    }

    private fun showJitsiRoomHelp(tunnel: Tunnel) {
        val url = routeUrl(tunnel)
        if (!io.github.libreroute.util.ServiceRouteEndpoint.isValid(TransportType.jitsi, url)) {
            Toast.makeText(requireContext(), "Проверьте адрес комнаты в настройках маршрута", Toast.LENGTH_LONG).show()
            return
        }
        val issue = EventBus.pendingJitsiIssue()
        val screen = CalmDetailDialog(requireContext(), "Комната Jitsi Meet")
        if (issue?.reason == JitsiAuthReason.TOKEN_EXPIRED) {
            screen.text("Токен организатора истёк. Обычному участнику токен не нужен: попросите организатора снова войти через Google или GitHub и запустить комнату.")
            screen.action("Сообщить администратору", secondary = true) {
                AdminContact.show(requireContext(), AdminContact.Reason.JITSI_EXPIRED, tunnel.name)
            }
        } else {
            screen.text("Комната ещё не подготовлена. Организатор входит через Google или GitHub в обычном браузере и запускает эту комнату; гость подключается без модераторского JWT.")
            screen.action("Сообщить администратору", secondary = true) {
                AdminContact.show(requireContext(), AdminContact.Reason.JITSI_MODERATOR, tunnel.name)
            }
        }
        screen.text("После входа вернитесь в LibreRoute и нажмите «Проверить снова». Если сервис отдаёт проверяемую ссылку, её можно передать через системное меню; cookies и пароли приложение не читает.")
          screen.action("Открыть в браузере") {
              screen.dismiss()
              lifecycleScope.launch {
                  vm.stopInternal(preserveActiveState = false)
                  jitsiAuthLauncher.launch(Intent(requireContext(), JitsiAuthActivity::class.java)
                      .putExtra(JitsiAuthActivity.EXTRA_ROOM_URL, url))
              }
          }
        screen.action("Проверить снова", secondary = true) {
            screen.dismiss()
            EventBus.clearPendingJitsiIssue()
            vm.restartCurrent()
        }
        screen.show()
    }

    private val splitTunnelLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        checkSplitSettingsAndAutoRestart()
    }

    private val vpnPermission = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) vm.startCurrent()
        else Toast.makeText(requireContext(), R.string.vpn_permission_required, Toast.LENGTH_LONG).show()
    }

    private fun handleAdminLink(raw: String): Boolean {
        val admin = raw.startsWith("libreroute://admin-request", true) || raw.startsWith("libreroute://admin-invite", true) || raw.startsWith("libreroute://admin-grant", true)
        if (admin) {
            startActivity(Intent(requireContext(), io.github.libreroute.admin.AdminSetupActivity::class.java).putExtra("admin_link", raw)); return true
        }
        if (raw.startsWith("libreroute://admin-transfer-ack", true)) {
            startActivity(Intent(requireContext(), io.github.libreroute.admin.AdminActivity::class.java).putExtra("admin_transfer_ack", raw)); return true
        }
        return false
    }

    private val qrScanner = registerForActivityResult(ScanQRCode()) { result ->
        val raw = (result as? QRResult.QRSuccess)?.content?.rawValue
            ?: return@registerForActivityResult
        if (handleAdminLink(raw)) return@registerForActivityResult
        if (raw.startsWith("libreroute://invitation", ignoreCase = true)) {
            handleInvitationLink(raw)
            return@registerForActivityResult
        }
        val tunnels = TunnelLinkParser.parseTunnels(raw, requireContext())
        if (tunnels.isNotEmpty()) {
            for (t in tunnels) vm.addTunnel(t)
            val msg = if (tunnels.size > 1) "Импортирован профиль (${tunnels.size} серверов)" else getString(R.string.config_saved)
            Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(requireContext(), R.string.qr_scan_failed, Toast.LENGTH_LONG).show()
        }
    }

    private val pickQrImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        val ctx = requireContext()
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val decoded = QrDecoder.decodeFromUri(ctx, uri)
            withContext(Dispatchers.Main) {
                if (decoded != null && handleAdminLink(decoded)) return@withContext
                if (decoded != null && decoded.startsWith("libreroute://invitation", ignoreCase = true)) {
                    handleInvitationLink(decoded)
                    return@withContext
                }
                val tunnels = if (decoded != null) TunnelLinkParser.parseTunnels(decoded, ctx) else emptyList()
                if (tunnels.isNotEmpty()) {
                    for (t in tunnels) vm.addTunnel(t)
                    val msg = if (tunnels.size > 1) "Импортирован профиль (${tunnels.size} серверов)" else getString(R.string.config_saved)
                    Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(ctx, R.string.qr_scan_failed, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun showAddQrBottomSheet() {
        val dialog = BottomSheetDialog(requireContext())
        val sheetView = layoutInflater.inflate(R.layout.bottom_sheet_add_qr, null)
        dialog.setContentView(sheetView)

        sheetView.findViewById<View>(R.id.option_scan_camera).setOnClickListener {
            it.performAppHaptics(HapticFeedbackConstants.VIRTUAL_KEY)
            dialog.dismiss()
            qrScanner.launch(null)
        }

        sheetView.findViewById<View>(R.id.option_pick_gallery).setOnClickListener {
            it.performAppHaptics(HapticFeedbackConstants.VIRTUAL_KEY)
            dialog.dismiss()
            pickQrImage.launch("image/*")
        }

        sheetView.findViewById<View>(R.id.option_import_link).setOnClickListener {
            it.performAppHaptics(HapticFeedbackConstants.VIRTUAL_KEY)
            dialog.dismiss()
            showImportLinkDialog()
        }

        sheetView.findViewById<View>(R.id.option_manual)?.setOnClickListener {
            it.performAppHaptics(HapticFeedbackConstants.VIRTUAL_KEY)
            dialog.dismiss()
            parentFragmentManager.beginTransaction()
                .setCustomAnimations(
                    R.anim.slide_in_bottom,
                    R.anim.fade_out,
                    R.anim.fade_in,
                    R.anim.slide_out_bottom
                )
                .replace(R.id.main, AddTunFragment.new())
                .addToBackStack("manual")
                .commit()
        }



        dialog.show()
    }

    private fun showImportLinkDialog() {
        val context = requireContext()
        val dialog = com.google.android.material.bottomsheet.BottomSheetDialog(context)
        val view = layoutInflater.inflate(R.layout.bottom_sheet_import_link, null)
        dialog.setContentView(view)

        val inputEdit = view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.input_link_value)
        val btnCancel = view.findViewById<View>(R.id.btn_cancel_import)
        val btnConfirm = view.findViewById<View>(R.id.btn_confirm_import)

        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val clipText = clipboard?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()?.trim()
        clipText?.let {
            if (it.startsWith("libreroute://", ignoreCase = true) || it.startsWith("{") || it.startsWith("vless://") || it.startsWith("vmess://") || it.startsWith("http")) {
                inputEdit.setText(it)
                inputEdit.setSelection(it.length)
            }
        }

        btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        btnConfirm.setOnClickListener {
            val text = inputEdit.text?.toString()?.trim()
            if (text != null && handleAdminLink(text)) {
                dialog.dismiss()
                return@setOnClickListener
            }
            if (text != null && text.startsWith("libreroute://invitation", ignoreCase = true)) {
                dialog.dismiss()
                handleInvitationLink(text)
                return@setOnClickListener
            }
            val tunnels = TunnelLinkParser.parseTunnels(text, context)
            if (tunnels.isNotEmpty()) {
                dialog.dismiss()
                for (t in tunnels) vm.addTunnel(t)
                val msg = if (tunnels.size > 1) "Импортирован профиль (${tunnels.size} серверов)" else getString(R.string.config_saved)
                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, R.string.import_link_invalid, Toast.LENGTH_LONG).show()
            }
        }

        dialog.show()
        inputEdit.requestFocus()
    }

    private val accessResult = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) vm.clearAccessRevocation()
        vm.refresh()
        if (result.resultCode == android.app.Activity.RESULT_OK && result.data?.getBooleanExtra("connect", false) == true) requestVpnAndStart()
          else if (vm.tunnels.value.size > 1) view?.findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(R.id.userNavigation)?.selectedItemId = R.id.nav_home
    }

    private fun handleInvitationLink(link: String) {
        accessResult.launch(Intent(requireContext(), AccessActivity::class.java).putExtra("invitation", link))
    }

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?) =
        i.inflate(R.layout.fragment_tunnels, c, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        connectButton = view.findViewById(R.id.connectButton)
        configSelector = view.findViewById(R.id.configSelector)
        configDot = view.findViewById(R.id.configDot)
        tunnelName = view.findViewById(R.id.tunnelName)
        chevron = view.findViewById(R.id.chevron)
        configPing = view.findViewById(R.id.configPing)
        statusText = view.findViewById(R.id.statusText)
        homeStateTitle = view.findViewById(R.id.homeStateTitle)
        uptimeContainer = view.findViewById(R.id.uptimeContainer)
        uptimeText = view.findViewById(R.id.uptimeText)
        metricPing = view.findViewById(R.id.metricPing)
        metricDownload = view.findViewById(R.id.metricDownload)
        metricUpload = view.findViewById(R.id.metricUpload)
        metricHint = view.findViewById(R.id.metricHint)
        appSettings = AppSettings(requireContext())

        connectButton.setOnClickListener {
            it.performAppHaptics(HapticFeedbackConstants.VIRTUAL_KEY)
            if (vm.accessRevoked.value) {
                showAddQrBottomSheet()
                return@setOnClickListener
            }
            when (vm.active.value) {
                is TunnelState.Running, is TunnelState.Reconnecting, is TunnelState.WaitingForNetwork, is TunnelState.Connecting -> vm.stop()
                is TunnelState.Idle, is TunnelState.Error -> if (vm.selected.value == null) showAddQrBottomSheet() else requestVpnAndStart()
                else -> vm.stop()
            }
        }

        configSelector.setOnClickListener {
            it.performAppHaptics(HapticFeedbackConstants.VIRTUAL_KEY)
            showConfigBottomSheet()
        }


        view.findViewById<View>(R.id.btnSettings)?.setOnClickListener {
            it.performAppHaptics(HapticFeedbackConstants.VIRTUAL_KEY)
            startActivity(Intent(requireContext(), SettingsActivity::class.java))
        }

        view.findViewById<View>(R.id.btnLogs)?.setOnClickListener {
            it.performAppHaptics(HapticFeedbackConstants.VIRTUAL_KEY)
            startActivity(Intent(requireContext(), LogsActivity::class.java))
        }
        cardAdminConsole = view.findViewById(R.id.cardAdminConsole)
        adminConsoleRoleBadge = view.findViewById(R.id.adminConsoleRoleBadge)
        cardAdminConsole.setOnClickListener {
            it.performAppHaptics(HapticFeedbackConstants.VIRTUAL_KEY)
            startActivity(Intent(requireContext(), io.github.libreroute.admin.AdminActivity::class.java))
        }
        updateAdminConsoleCard()

        cardSplitTunnel = view.findViewById(R.id.cardSplitTunnel)
        splitTunnelIcon = view.findViewById(R.id.splitTunnelIcon)
        splitTunnelTitle = view.findViewById(R.id.splitTunnelTitle)
        splitTunnelSubtitle = view.findViewById(R.id.splitTunnelSubtitle)

        cardSplitTunnel.setOnClickListener {
            it.performAppHaptics(HapticFeedbackConstants.VIRTUAL_KEY)
            splitTunnelLauncher.launch(Intent(requireContext(), SplitTunnelActivity::class.java))
        }

        lastSplitSnapshot = getSplitSnapshot()
        updateSplitTunnelCard()

        view.findViewById<View>(R.id.addButton).setOnClickListener {
            it.performAppHaptics(HapticFeedbackConstants.VIRTUAL_KEY)
            showAddQrBottomSheet()
        }
        view.findViewById<View>(R.id.connectServerButton).setOnClickListener {
            it.performAppHaptics(HapticFeedbackConstants.VIRTUAL_KEY)
            startActivity(Intent(requireContext(), io.github.libreroute.admin.AdminSetupActivity::class.java).putExtra("connect_server", true))
        }

        setupCalmNavigation(view, savedInstanceState)
        observe()
    }

    override fun onResume() {
        super.onResume()
        vm.refresh()
        vm.checkSelectedHealth(force = false)
        renderAuthAttention()
        updateConfigPing()
        checkSplitSettingsAndAutoRestart()
        updateAdminConsoleCard()
    }

    private fun getSplitSnapshot(): String {
        val ctx = context ?: return ""
        val splitPrefs = io.github.libreroute.util.SplitTunnelPreferences(ctx)
        val domainPrefs = io.github.libreroute.util.DomainRulesPreferences(ctx)
        val ipPrefs = io.github.libreroute.util.IpRulesPreferences(ctx)
        return "${splitPrefs.isEnabled}:${splitPrefs.mode}:${splitPrefs.bypassApps.size}:${splitPrefs.bypassApps.hashCode()}:" +
                "${splitPrefs.proxyApps.size}:${splitPrefs.proxyApps.hashCode()}:${domainPrefs.mode}:" +
                "${domainPrefs.domains.size}:${domainPrefs.domains.hashCode()}:${ipPrefs.rules.size}:${ipPrefs.rules.hashCode()}"
    }

    private fun checkSplitSettingsAndAutoRestart() {
        val currentSnapshot = getSplitSnapshot()
        val changed = lastSplitSnapshot != null && lastSplitSnapshot != currentSnapshot
        lastSplitSnapshot = currentSnapshot
        updateSplitTunnelCard()

        if (changed) {
            val currentState = vm.active.value
            if (currentState.isActive) {
                vm.restartCurrent()
                Toast.makeText(
                    requireContext(),
                    R.string.split_tunnel_restarted,
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun updateSplitTunnelCard() {
        if (!::cardSplitTunnel.isInitialized || !::splitTunnelSubtitle.isInitialized) return
        val ctx = context ?: return
        val splitPrefs = io.github.libreroute.util.SplitTunnelPreferences(ctx)
        val isEnabled = splitPrefs.isEnabled

        if (!isEnabled) {
            splitTunnelIcon.setColorFilter(ContextCompat.getColor(ctx, R.color.text_tertiary))
            splitTunnelSubtitle.text = getString(R.string.split_tunnel_card_disabled)
            return
        }

        splitTunnelIcon.setColorFilter(ContextCompat.getColor(ctx, R.color.m3_primary))
        val isBypass = splitPrefs.mode == io.github.libreroute.util.SplitTunnelPreferences.MODE_BYPASS
        val installed = runCatching {
            ctx.packageManager.getInstalledApplications(0).map { it.packageName }.toSet()
        }.getOrNull()

        val appCount = if (installed != null) {
            if (isBypass) splitPrefs.bypassApps.count { it in installed }
            else splitPrefs.proxyApps.count { it in installed }
        } else {
            if (isBypass) splitPrefs.bypassApps.size
            else splitPrefs.proxyApps.size
        }
        val domainPrefs = io.github.libreroute.util.DomainRulesPreferences(ctx)
        val domainCount = domainPrefs.domains.size
        val ipPrefs = io.github.libreroute.util.IpRulesPreferences(ctx)
        val ipCount = ipPrefs.rules.size

        val prefix = if (isBypass) {
            getString(R.string.split_tunnel_mode_bypass_prefix)
        } else {
            getString(R.string.split_tunnel_mode_proxy_prefix)
        }

        val parts = mutableListOf<String>()
        if (appCount > 0) {
            parts.add(getString(R.string.split_tunnel_summary_apps, appCount))
        }
        if (domainCount > 0) {
            parts.add(getString(R.string.split_tunnel_summary_sites, domainCount))
        }
        if (ipCount > 0) {
            parts.add(getString(R.string.split_tunnel_summary_ips, ipCount))
        }

        splitTunnelSubtitle.text = if (parts.isEmpty()) {
            "$prefix: ${getString(R.string.split_tunnel_card_empty)}"
        } else {
            "$prefix: ${parts.joinToString(", ")}"
        }
    }

    private fun updateAdminConsoleCard() {
        if (!::cardAdminConsole.isInitialized) return
        val ctx = context ?: return
        val adminRepo = io.github.libreroute.admin.AdminRepository.getInstance(ctx)
        val hasAccess = adminRepo.hasAdminReadAccess()
        cardAdminConsole.isVisible = hasAccess
        if (hasAccess) {
            adminConsoleRoleBadge.text = adminRepo.getRole().name
        }
    }

    fun connectFromInvitation() = requestVpnAndStart()

    private fun requestVpnAndStart() {
        checkBatteryOptimizationAndProceed {
            val intent = VpnService.prepare(requireActivity())
            if (intent != null) vpnPermission.launch(intent) else vm.startCurrent()
        }
    }

    private fun checkBatteryOptimizationAndProceed(onProceed: () -> Unit) {
        val ctx = context ?: run { onProceed(); return }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
            val isIgnored = pm?.isIgnoringBatteryOptimizations(ctx.packageName) == true
            if (!isIgnored && !appSettings.batteryOptPrompted) {
                appSettings.batteryOptPrompted = true
                MaterialAlertDialogBuilder(ctx)
                    .setTitle(R.string.battery_opt_prompt_title)
                    .setMessage(R.string.battery_opt_prompt_message)
                    .setPositiveButton(R.string.battery_opt_prompt_action) { _, _ ->
                        try {
                            val intent = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                data = Uri.parse("package:${ctx.packageName}")
                            }
                            startActivity(intent)
                        } catch (_: Exception) {
                            try {
                                startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                            } catch (_: Exception) {}
                        }
                        onProceed()
                    }
                    .setNegativeButton(R.string.battery_opt_prompt_later) { _, _ ->
                        onProceed()
                    }
                    .show()
                return
            }
        }
        onProceed()
    }

    private fun observe() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.syncRunningServiceState()
                launch { vm.active.collect { applyState(it); updateConfigPing() } }
                launch { vm.uptimeSeconds.collect { renderUptime(it) } }
                launch { vm.selected.collect { renderSelected(it) } }
                launch {
                    combine(vm.rxSpeed, vm.txSpeed) { rx, tx -> Pair(rx, tx) }
                        .collect { (rx, tx) -> renderSpeed(rx, tx) }
                }
                launch { vm.tunnelHealth.collect { renderHealth(it) } }
                launch { vm.pingMap.collect { updateConfigPing() } }
                launch { vm.active.collect { updateNetworkMetricsState(it) } }
                launch { vm.accessRevoked.collect { applyState(vm.active.value) } }
                launch { vm.tunnels.collect { updateRoutesTab(); renderRoutes() } }
                launch { vm.healthMap.collect { renderRoutes() } }
                launch { vm.selected.collect { renderRoutes(); applyState(vm.active.value) } }
                launch { vm.speedSampleAvailable.collect { updateNetworkMetricsState(vm.active.value) } }
                launch {
                    val adminRepo = io.github.libreroute.admin.AdminRepository.getInstance(requireContext())
                    adminRepo.roleFlow.collect { role ->
                        val hasAccess = adminRepo.hasAdminReadAccess()
                        cardAdminConsole.isVisible = hasAccess
                        if (hasAccess) {
                            adminConsoleRoleBadge.text = role.name
                        }
                    }
                }
            }
        }
    }

    private fun renderSpeed(rxBytesSec: Long, txBytesSec: Long) {
        updateNetworkMetricsState(vm.active.value, rxBytesSec, txBytesSec)
    }

    private fun formatMegabits(bytesPerSecond: Long): String {
        val megabits = bytesPerSecond * 8.0 / 1_000_000.0
        return when {
            megabits < 0.1 -> "0,0 Мбит/с"
            megabits < 10.0 -> String.format(Locale.US, "%.1f Мбит/с", megabits).replace('.', ',')
            else -> String.format(Locale.US, "%.0f Мбит/с", megabits)
        }
    }

    private fun updateNetworkMetricsState(
        state: TunnelState,
        rxBytesSec: Long = vm.rxSpeed.value,
        txBytesSec: Long = vm.txSpeed.value
    ) {
        if (!::metricPing.isInitialized) return
        val running = state is TunnelState.Running
        view?.findViewById<View>(R.id.networkMetricsCard)?.isVisible = running
        val ping = vm.selectedTunnelId?.let { vm.getTunnelPing(it) }
        metricPing.text = if (running && ping != null && ping > 0) getString(R.string.ping_ms_format, ping) else "—"
        metricDownload.text = if (running && vm.speedSampleAvailable.value) formatMegabits(rxBytesSec) else "—"
        metricUpload.text = if (running && vm.speedSampleAvailable.value) formatMegabits(txBytesSec) else "—"
        metricHint.text = when {
            state is TunnelState.Idle -> getString(R.string.network_metrics_after_connect)
            state is TunnelState.WaitingForNetwork || state is TunnelState.Error -> "Измерение недоступно"
            !running -> "Подключаемся…"
            !vm.speedSampleAvailable.value -> getString(R.string.network_metrics_waiting)
            else -> "Трафик приложения через туннель · Мбит/с"
        }
        renderAuthAttention()
    }

    private fun renderSelected(tunnel: Tunnel?) {
        tunnelName.text = tunnel?.name ?: getString(R.string.no_configs)
        renderHealth(vm.tunnelHealth.value)
        updateConfigPing()
    }

    private fun updateConfigPing() {
        if (!::configPing.isInitialized || !::configSelector.isInitialized) return
        val isRunning = vm.active.value is TunnelState.Running
        updateNetworkMetricsState(vm.active.value)
        val showPing = false // dedicated metric row
        val selectedId = vm.selectedTunnelId
        val ping = selectedId?.let { vm.getTunnelPing(it) }
        val shouldShow = isRunning && showPing && ping != null && ping > 0

        if (configPing.isVisible != shouldShow) {
            val selectorGroup = configSelector as? ViewGroup
            if (selectorGroup != null && isAdded && !isInitialStateBinding) {
                val transition = TransitionSet().apply {
                    ordering = TransitionSet.ORDERING_TOGETHER
                    addTransition(ChangeBounds().apply {
                        duration = 240L
                        interpolator = DecelerateInterpolator()
                    })
                    addTransition(Fade().apply {
                        duration = 200L
                    })
                }
                TransitionManager.beginDelayedTransition(selectorGroup, transition)
            }
            configPing.isVisible = shouldShow
        }

        if (shouldShow) {
            val p = ping ?: return
            configPing.text = getString(R.string.ping_ms_format, p)
            updateNetworkMetricsState(vm.active.value)
            val colorRes = when {
                p < 150 -> R.color.state_running
                p < 350 -> R.color.state_connecting
                else -> R.color.state_error
            }
            configPing.setTextColor(ContextCompat.getColor(requireContext(), colorRes))
        }
    }

    private fun renderHealth(health: TunnelHealth) {
        if (vm.selected.value == null) {
            configDot.background?.setTint(
                ContextCompat.getColor(requireContext(), R.color.state_error)
            )
            return
        }
        val colorRes = when (health) {
            TunnelHealth.AVAILABLE -> R.color.state_running
            TunnelHealth.UNAVAILABLE -> R.color.state_error
            TunnelHealth.CHECKING -> R.color.state_connecting
            TunnelHealth.UNKNOWN -> R.color.state_idle
        }
        configDot.background?.setTint(
            ContextCompat.getColor(requireContext(), colorRes)
        )
        renderRunningHealth(health)
    }

    private fun renderRunningHealth(health: TunnelHealth) {
        if (vm.active.value !is TunnelState.Running || !isAdded) return
        statusText.text = if (health == TunnelHealth.UNAVAILABLE) "Проверяем соединение" else "Подключено"
        view?.findViewById<TextView>(R.id.homeEvent)?.text = when (health) {
            TunnelHealth.AVAILABLE -> "Интернет доступен через выбранный маршрут"
            TunnelHealth.UNAVAILABLE -> "Не удалось получить ответ через маршрут. Попробуйте проверить ещё раз"
            else -> "Проверяем доступность интернета"
        }
        renderAuthAttention()
    }

    private fun showConfigBottomSheet() {
        vm.probeAllTunnels()

        val dialog = BottomSheetDialog(requireContext())
        configSheetDialog = dialog
        val sheetView = layoutInflater.inflate(R.layout.bottom_sheet_configs, null)
        dialog.setContentView(sheetView)

        val subtitleView = sheetView.findViewById<TextView>(R.id.configs_sheet_subtitle)
        val btnClose = sheetView.findViewById<View>(R.id.btn_close_configs)
        val searchContainer = sheetView.findViewById<TextInputLayout>(R.id.search_configs_container)
        val searchInput = sheetView.findViewById<TextInputEditText>(R.id.search_configs_input)
        val configsRecycler = sheetView.findViewById<RecyclerView>(R.id.configs_recycler)
        val emptyState = sheetView.findViewById<View>(R.id.configs_empty_state)
        val btnAddBottom = sheetView.findViewById<View>(R.id.btn_add_config_bottom)

        val onAddClick = View.OnClickListener {
            it.performAppHaptics(HapticFeedbackConstants.VIRTUAL_KEY)
            dialog.dismiss()
            showAddQrBottomSheet()
        }
        btnAddBottom.setOnClickListener(onAddClick)
        btnClose.setOnClickListener {
            it.performAppHaptics(HapticFeedbackConstants.VIRTUAL_KEY)
            dialog.dismiss()
        }

        val adapter = ConfigsAdapter(
            onSelect = { tunnel ->
                vm.selectTunnel(tunnel)
                dialog.dismiss()
            },
            onMore = { anchor, tunnel ->
                showItemContextMenu(anchor, tunnel)
            }
        )
        configsRecycler.layoutManager = LinearLayoutManager(requireContext())
        configsRecycler.adapter = adapter

        var currentFilter = ""

        fun populateList(filterQuery: String = currentFilter) {
            currentFilter = filterQuery
            val allTunnels = vm.tunnels.value.map { it.tunnel }
            subtitleView.text = getString(R.string.total_configs_format, allTunnels.size)

            searchContainer.isVisible = allTunnels.size >= 4

            val filtered = if (filterQuery.isBlank()) {
                allTunnels
            } else {
                allTunnels.filter {
                    it.name.contains(filterQuery, ignoreCase = true) ||
                    it.transportType.contains(filterQuery, ignoreCase = true)
                }
            }

            if (filtered.isEmpty()) {
                emptyState.isVisible = true
                adapter.submitList(emptyList())
                return
            } else {
                emptyState.isVisible = false
            }

            val selectedId = vm.selectedTunnelId
            val healthMap = vm.healthMap.value
            val items = filtered.map { tunnel ->
                TunnelConfigItem(
                    tunnel = tunnel,
                    isSelected = tunnel.id == selectedId,
                    health = healthMap[tunnel.id] ?: vm.getTunnelHealth(tunnel.id)
                )
            }
            adapter.submitList(items)
        }

        populateList()

        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                populateList(s?.toString()?.trim() ?: "")
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        chevron.animate().rotation(180f).setDuration(220L).setInterpolator(DecelerateInterpolator()).start()

        val sheetCollectorsJob = viewLifecycleOwner.lifecycleScope.launch {
            launch {
                vm.healthMap.collect {
                    populateList()
                }
            }
            launch {
                vm.tunnels.collect {
                    populateList()
                }
            }
        }

        dialog.setOnDismissListener {
            sheetCollectorsJob.cancel()
            chevron.animate().rotation(0f).setDuration(180L).setInterpolator(DecelerateInterpolator()).start()
            configSheetDialog = null
        }

        dialog.show()
    }

    data class TunnelConfigItem(
        val tunnel: Tunnel,
        val isSelected: Boolean,
        val health: TunnelHealth
    )

    private class TunnelConfigDiffCallback : DiffUtil.ItemCallback<TunnelConfigItem>() {
        override fun areItemsTheSame(oldItem: TunnelConfigItem, newItem: TunnelConfigItem): Boolean =
            oldItem.tunnel.id == newItem.tunnel.id

        override fun areContentsTheSame(oldItem: TunnelConfigItem, newItem: TunnelConfigItem): Boolean =
            oldItem == newItem
    }

    private class ConfigsAdapter(
        private val onSelect: (Tunnel) -> Unit,
        private val onMore: (View, Tunnel) -> Unit
    ) : ListAdapter<TunnelConfigItem, ConfigsAdapter.ConfigViewHolder>(TunnelConfigDiffCallback()) {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ConfigViewHolder {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_config_card, parent, false) as MaterialCardView
            return ConfigViewHolder(v)
        }

        override fun onBindViewHolder(holder: ConfigViewHolder, position: Int) {
            holder.bind(getItem(position))
        }

        inner class ConfigViewHolder(private val card: MaterialCardView) : RecyclerView.ViewHolder(card) {
            private val iconView = card.findViewById<ImageView>(R.id.config_transport_icon)
            private val nameView = card.findViewById<TextView>(R.id.config_name)
            private val transportLabel = card.findViewById<TextView>(R.id.config_transport_label)
            private val dot = card.findViewById<View>(R.id.config_status_dot)
            private val checkIcon = card.findViewById<ImageView>(R.id.config_check_icon)
            private val btnMore = card.findViewById<ImageView>(R.id.btn_config_more)

            fun bind(item: TunnelConfigItem) {
                val tunnel = item.tunnel
                nameView.text = tunnel.name

                when (TransportType.from(tunnel.transportType)) {
                    TransportType.directDpi -> {
                        iconView.setImageResource(R.drawable.ic_split_tunnel)
                        iconView.imageTintList = ContextCompat.getColorStateList(card.context, R.color.text_primary)
                        transportLabel.text = "Прямой обход DPI · zapret"
                    }
                    TransportType.mqtt, TransportType.jitsi -> {
                        iconView.setImageResource(R.drawable.ic_split_tunnel)
                        iconView.imageTintList = ContextCompat.getColorStateList(card.context, R.color.text_primary)
                        transportLabel.text = card.context.getString(if (tunnel.transportType == "mqtt") R.string.mqtt_backend else R.string.jitsi_backend)
                    }
                    TransportType.yandex -> {
                        iconView.imageTintList = null
                        iconView.setImageResource(R.drawable.yandex_docs)
                        transportLabel.text = card.context.getString(R.string.yandex_docs_backend)
                    }
                    TransportType.vyandex -> {
                        iconView.imageTintList = null
                        iconView.setImageResource(R.drawable.volga)
                        transportLabel.text = card.context.getString(R.string.vyandex_backend)
                    }
                    TransportType.max -> {
                        iconView.setImageResource(R.drawable.max_msg)
                        iconView.imageTintList = ContextCompat.getColorStateList(card.context, R.color.text_primary)
                        transportLabel.text = card.context.getString(R.string.max_messenger_backend)
                    }
                    TransportType.cups -> {
                        iconView.imageTintList = null
                        iconView.setImageResource(R.drawable.ic_cups)
                        transportLabel.text = card.context.getString(R.string.cups_backend)
                    }
                    TransportType.mailru -> {
                        iconView.imageTintList = null
                        iconView.setImageResource(R.drawable.ic_mailru)
                        transportLabel.text = card.context.getString(R.string.mailru_backend)
                    }
                }

                val density = card.resources.displayMetrics.density
                if (item.isSelected) {
                    card.setStrokeColor(ContextCompat.getColor(card.context, R.color.m3_primary))
                    card.strokeWidth = (1.5f * density).toInt()
                    card.setCardBackgroundColor(ContextCompat.getColor(card.context, R.color.m3_surface_container_high))
                    checkIcon.isVisible = true
                    nameView.setTypeface(null, Typeface.BOLD)
                } else {
                    card.setStrokeColor(ContextCompat.getColor(card.context, R.color.m3_outline_variant))
                    card.strokeWidth = (1f * density).toInt()
                    card.setCardBackgroundColor(ContextCompat.getColor(card.context, R.color.m3_surface_container))
                    checkIcon.isVisible = false
                    nameView.setTypeface(null, Typeface.NORMAL)
                }

                val colorRes = when (item.health) {
                    TunnelHealth.AVAILABLE -> R.color.state_running
                    TunnelHealth.UNAVAILABLE -> R.color.state_error
                    TunnelHealth.CHECKING -> R.color.state_connecting
                    TunnelHealth.UNKNOWN -> R.color.state_idle
                }
                dot.background?.setTint(ContextCompat.getColor(card.context, colorRes))

                card.setOnClickListener {
                    it.performAppHaptics(HapticFeedbackConstants.VIRTUAL_KEY)
                    onSelect(tunnel)
                }
                btnMore.setOnClickListener {
                    it.performAppHaptics(HapticFeedbackConstants.VIRTUAL_KEY)
                    onMore(it, tunnel)
                }
                card.setOnLongClickListener {
                    it.performAppHaptics(HapticFeedbackConstants.LONG_PRESS)
                    onMore(btnMore, tunnel)
                    true
                }
            }
        }
    }

    private fun showItemContextMenu(anchor: View, tunnel: Tunnel) {
        val dialog = BottomSheetDialog(requireContext())
        val view = layoutInflater.inflate(R.layout.bottom_sheet_config_options, null)
        dialog.setContentView(view)

        val nameView = view.findViewById<TextView>(R.id.sheet_config_name)
        val subView = view.findViewById<TextView>(R.id.sheet_config_subtitle)
        val iconView = view.findViewById<ImageView>(R.id.sheet_config_icon)

        nameView.text = tunnel.name
        when (TransportType.from(tunnel.transportType)) {
            TransportType.directDpi -> {
                iconView.setImageResource(R.drawable.ic_split_tunnel)
                subView.text = "Прямой обход DPI · zapret"
            }
            TransportType.mqtt, TransportType.jitsi -> {
                iconView.setImageResource(R.drawable.ic_split_tunnel)
                subView.text = getString(if (tunnel.transportType == "mqtt") R.string.mqtt_backend else R.string.jitsi_backend)
            }
            TransportType.yandex -> {
                iconView.imageTintList = null
                iconView.setImageResource(R.drawable.yandex_docs)
                subView.text = getString(R.string.yandex_docs_backend)
            }
            TransportType.vyandex -> {
                iconView.imageTintList = null
                iconView.setImageResource(R.drawable.volga)
                subView.text = getString(R.string.vyandex_backend)
            }
            TransportType.max -> {
                iconView.setImageResource(R.drawable.max_msg)
                iconView.imageTintList = ContextCompat.getColorStateList(requireContext(), R.color.text_primary)
                subView.text = getString(R.string.max_messenger_backend)
            }
            TransportType.cups -> {
                iconView.imageTintList = null
                iconView.setImageResource(R.drawable.ic_cups)
                subView.text = getString(R.string.cups_backend)
            }
            TransportType.mailru -> {
                iconView.imageTintList = null
                iconView.setImageResource(R.drawable.ic_mailru)
                subView.text = getString(R.string.mailru_backend)
            }
        }

        view.findViewById<View>(R.id.menu_edit).setOnClickListener {
            it.performAppHaptics(HapticFeedbackConstants.VIRTUAL_KEY)
            dialog.dismiss()
            configSheetDialog?.dismiss()
            parentFragmentManager.beginTransaction()
                .setCustomAnimations(
                    R.anim.slide_in_bottom,
                    R.anim.fade_out,
                    R.anim.fade_in,
                    R.anim.slide_out_bottom
                )
                .replace(R.id.main, AddTunFragment.edit(tunnel))
                .addToBackStack("edit")
                .commit()
        }

        view.findViewById<View>(R.id.menu_share_qr)?.setOnClickListener {
            it.performAppHaptics(HapticFeedbackConstants.VIRTUAL_KEY)
            dialog.dismiss()
            configSheetDialog?.dismiss()
            QrShareDialog.show(requireContext(), tunnel)
        }

        view.findViewById<View>(R.id.menu_delete).setOnClickListener {
            it.performAppHaptics(HapticFeedbackConstants.VIRTUAL_KEY)
            dialog.dismiss()
            confirmDelete(tunnel)
        }

        dialog.show()
    }

    private fun confirmDelete(tunnel: Tunnel) {
        val active = vm.active.value
        if (active.isActive && active.tunnel == tunnel) {
            Toast.makeText(requireContext(), R.string.cannot_delete_active, Toast.LENGTH_SHORT).show()
            return
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.delete_config_title)
            .setMessage("«${tunnel.name}»\n${getString(R.string.delete_config_msg)}")
            .setPositiveButton(R.string.action_delete) { dialog, _ ->
                vm.removeTunnel(tunnel)
                configSheetDialog?.dismiss()
                popup?.dismiss()
                Toast.makeText(requireContext(), R.string.config_deleted, Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel) { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    private fun showErrorDialog(rawOrTitle: String, detail: String? = null) {
        val title: String
        val msg: String
        if (!detail.isNullOrBlank()) {
            title = rawOrTitle
            msg = detail
        } else {
            val classified = TunnelErrorClassifier.classify(rawOrTitle)
            title = classified.title
            msg = classified.message
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(title)
            .setMessage(msg)
            .setPositiveButton(R.string.retry) { dialog, _ ->
                dialog.dismiss()
                requestVpnAndStart()
            }
            .setNeutralButton(R.string.view_logs) { dialog, _ ->
                dialog.dismiss()
                startActivity(Intent(requireContext(), LogsActivity::class.java))
            }
            .setNegativeButton(R.string.cancel) { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    private fun setConnectButtonLabel(value: String) {
        view?.findViewById<TextView>(R.id.connectButtonLabel)?.text = value
        connectButton.contentDescription = value
    }

    private fun renderConnectAccent(colorRes: Int) {
        val color = ContextCompat.getColor(requireContext(), colorRes)
        (connectButton as com.google.android.material.card.MaterialCardView).strokeColor =
            if (colorRes == R.color.m3_primary) ContextCompat.getColor(requireContext(), R.color.m3_outline_variant) else color
        view?.findViewById<ImageView>(R.id.connectPowerIcon)?.setColorFilter(color)
        statusText.setTextColor(if (colorRes == R.color.m3_primary) ContextCompat.getColor(requireContext(), R.color.text_secondary) else color)
    }

    private fun applyState(state: TunnelState) {
        currentVisualState = state
        isInitialStateBinding = false
        val (title, message, action) = when (state) {
            is TunnelState.Idle -> Triple("Отключено", if (vm.selected.value == null) "Импортируйте приглашение или подключите свой сервер" else "Выберите маршрут и подключитесь", if (vm.selected.value == null) "Импортировать приглашение" else "Подключить")
            is TunnelState.Running -> Triple("Подключено", "Проверяем доступность интернета", "Отключить")
            is TunnelState.WaitingForNetwork -> Triple("Сеть недоступна", "Проверьте Wi-Fi или мобильную сеть", "Отмена")
            is TunnelState.Error -> Triple("Не удалось подключиться", "Проверьте сеть или выберите другой маршрут. Подробности доступны в журнале", "Повторить")
            is TunnelState.Reconnecting -> Triple("Восстанавливаем соединение", "Маршрут временно недоступен", "Отмена")
            else -> Triple("Подключаемся", "Подготавливаем маршрут. Можно отменить подключение", "Отмена")
        }
        statusText.text = title
        homeStateTitle.text = when (state) {
            is TunnelState.Idle -> "Защита отключена"
            is TunnelState.Running -> "Защита включена"
            is TunnelState.WaitingForNetwork -> "Сеть недоступна"
            is TunnelState.Error -> "Не удалось подключиться"
            is TunnelState.Reconnecting -> "Восстанавливаем соединение"
            else -> "Подключаем защищённый маршрут"
        }
        statusText.setTextColor(ContextCompat.getColor(requireContext(), if (state is TunnelState.Error) R.color.state_error else R.color.text_primary))
        view?.findViewById<TextView>(R.id.homeEvent)?.text = message
        setConnectButtonLabel(action)
        renderConnectAccent(when (state) {
            is TunnelState.Running -> R.color.state_running
            is TunnelState.Error -> R.color.state_error
            is TunnelState.Idle -> R.color.m3_primary
            else -> R.color.state_connecting
        })
        uptimeContainer.isVisible = state is TunnelState.Running
        updateNetworkMetricsState(state)
        if (vm.accessRevoked.value) {
            statusText.text = "Доступ отозван"
            view?.findViewById<TextView>(R.id.homeEvent)?.text = "Свяжитесь с администратором, чтобы получить новое приглашение"
            setConnectButtonLabel("Импортировать приглашение")
        }
        renderRunningHealth(vm.tunnelHealth.value)
        renderAuthAttention()
    }

    private fun renderUptime(seconds: Long) {
        uptimeText.text = "Подключено: ${seconds.coerceAtLeast(0).toUptimeHms()}"
        uptimeContainer.isVisible = vm.active.value is TunnelState.Running
    }

    override fun onDestroyView() {
        popup?.dismiss()
        popup = null
        currentVisualState = null
        isInitialStateBinding = true
        super.onDestroyView()
    }

    override fun onNewEvent(ev: AppEvent) {
        if (ev is AppEvent.YandexAuthIssue || ev is AppEvent.JitsiModeratorRequired) renderAuthAttention()
        if (ev is AppEvent.AccessRevoked) {
            statusText.text = "Доступ отозван"
            view?.findViewById<TextView>(R.id.homeEvent)?.text = "Свяжитесь с администратором, чтобы получить новое приглашение"
             setConnectButtonLabel("Импортировать приглашение")

        }
    }

    companion object {
        fun new() = TunnelsFragment()
    }
}
