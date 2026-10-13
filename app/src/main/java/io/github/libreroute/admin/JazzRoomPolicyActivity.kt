package io.github.libreroute.admin

import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import io.github.libreroute.ui.CalmViews
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Settings and sanitized pool counts are available only through signed grants. */
class JazzRoomPolicyActivity : AppCompatActivity() {
    private val dialogs by lazy { io.github.libreroute.ui.DialogScreens(this) { key, args ->
        if (key == "replace") projection?.rooms?.firstOrNull { it.resource_id == args.getString("id") }?.let(::confirmReplacement)
    } }
    private val repository by lazy { AdminRepository.getInstance(this) }
    private val nodeId by lazy { intent.getStringExtra(ProtocolSetupActivity.EXTRA_SERVER_ID).orEmpty() }
    private val scrollPosition by lazy { io.github.libreroute.ui.ScreenScrollPosition(this, content.parent as ScrollView) }
    private lateinit var content: LinearLayout
    private lateinit var status: TextView
    private val vm by viewModels<JazzRoomPolicyViewModel>()
    private val busy get() = vm.state.value.running
    private val projection get() = vm.state.value.projection
    private var pendingMode: String?
        get() = vm.mode
        set(value) { vm.mode = value }
    private var pendingPairs: String?
        get() = vm.pairs
        set(value) { vm.pairs = value }
    private var pendingRooms: String?
        get() = vm.rooms
        set(value) { vm.rooms = value }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!repository.canPerform(nodeId, "get-jazz-room-pool")) { finish(); return }
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(16.dp(),16.dp(),16.dp(),24.dp()) }
        setContentView(ScrollView(this).apply { id = io.github.libreroute.ui.DialogScreens.stateId("jazz-policy-scroll") }.apply { addView(content) })
        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                vm.state.collect {
                    render()
                    it.error?.let { error -> status.text = "$error. Проверьте результат перед повторным изменением." }
                }
            }
        }
        if (!vm.initialized) refresh()
        dialogs.restore()
    }
    override fun onResume() { super.onResume(); if (!repository.canPerform(nodeId,"get-jazz-room-pool")) finish() }
    override fun onSaveInstanceState(out: Bundle) { out.putString("mode",pendingMode); out.putString("pairs",pendingPairs); out.putString("rooms",pendingRooms); super.onSaveInstanceState(out) }
    private fun refresh() { val repo = repository; runOperation { repo.getJazzRoomPool(it) } }
    private fun runOperation(commitDraft: Boolean = false, action: (ServerNode) -> Result<JazzPoolProjection>) {
        val node = repository.getServerNode(nodeId) ?: return
        vm.run(node, commitDraft, action)
    }
    private fun render() {
        if (projection != null) scrollPosition.beforeRebuild()
        content.removeAllViews()
        content.addView(CalmViews.row(this,"СберДжаз · комнаты",repository.getServerNode(nodeId)?.name.orEmpty()))
        button("Назад") { finish() }
        status = TextView(this).apply { text = if (busy) "Проверяем сервер…" else "Настройки и занятые места подтверждает сервер" }
        content.addView(status)
        button("Проверить результат") { refresh() }
        val view = projection ?: return
        val editable = repository.canPerform(nodeId,"set-jazz-room-policy")
        val policy = view.policy
        content.addView(CalmViews.row(this,"Размещение подключений","Одна пара — клиент и сервер. Новые комнаты создаются автоматически, когда нет свободных мест."))
        val modes = RadioGroup(this)
        val dedicated = RadioButton(this).apply { id = io.github.libreroute.ui.DialogScreens.stateId("dedicated"); text = "Отдельная комната для каждого подключения"; minHeight=48.dp(); isEnabled=editable&&!busy }
        val shared = RadioButton(this).apply { id = io.github.libreroute.ui.DialogScreens.stateId("shared"); text = "Общие комнаты"; minHeight=48.dp(); isEnabled=editable&&!busy }
        modes.addView(dedicated); modes.addView(shared); content.addView(modes)
        val mode = pendingMode ?: policy.placement_mode
        modes.check(if (mode == "shared_pool") shared.id else dedicated.id)
        val pairs = number("Пар в комнате (1–2)",pendingPairs ?: policy.max_pairs_per_room.toString(),editable)
        (pairs.parent as android.view.View).visibility = if (mode == "shared_pool") android.view.View.VISIBLE else android.view.View.GONE
        val rooms = number("Максимум комнат (1–16)",pendingRooms ?: policy.max_rooms.toString(),editable)
        modes.setOnCheckedChangeListener { _,id -> pendingMode=if(id==shared.id) "shared_pool" else "dedicated"; (pairs.parent as android.view.View).visibility=if(id==shared.id) android.view.View.VISIBLE else android.view.View.GONE }
        pairs.addTextChangedListener(watcher { pendingPairs=it }); rooms.addTextChangedListener(watcher { pendingRooms=it })
        content.addView(CalmViews.row(this,"Ревизии настроек","Запрошено ${policy.desired_revision} · применено ${policy.applied_revision}"))
        if (editable) button("Применить") {
            val chosen = if (modes.checkedRadioButtonId==shared.id) "shared_pool" else "dedicated"
            val p = if (chosen=="dedicated") 1 else pairs.text.toString().toIntOrNull()
            val r = rooms.text.toString().toIntOrNull()
            if (p == null || p !in 1..2 || r == null || r !in 1..16) { status.text="Укажите 1–2 пары и 1–16 комнат"; return@button }
            pendingMode=chosen; pendingPairs=p.toString(); pendingRooms=r.toString()
            val repo = repository
            runOperation(commitDraft = true) { repo.setJazzRoomPolicy(it,policy.copy(placement_mode=chosen,max_pairs_per_room=p,max_rooms=r)) }
        }
        content.addView(CalmViews.row(this,"Комнаты","${view.rooms.size} · сведения проверены сервером. Занятые места включают устройства без сети; число гостей провайдера не измеряется."))
        view.rooms.forEachIndexed { index, room ->
            val state=when(room.state){"ready"->if(room.full) "Заполнена" else "Готова";"draining"->"Новые подключения запрещены";"backend_check"->"Проверяем соединение";"external_result_unknown"->"Результат создания неизвестен";"retired"->"Выведена из использования";else->"Требует проверки"}
            content.addView(CalmViews.row(this,"Комната ${index+1} · $state","Выдано ${room.committed} · зарезервировано ${room.reserved} · мест ${room.capacity}"))
			val allocation = view.operations.firstOrNull { it.result_resource_id == room.resource_id && it.state in setOf("external_result_unknown", "reserved") }
			if (allocation != null) button("Проверить создание комнаты") { run { val repo = repository; runOperation { repo.reconcileJazzOperation(it, allocation) } } }
            if (repository.canPerform(nodeId,"drain-jazz-room") && room.state=="ready") button("Запретить новые подключения") { run { val repo = repository; runOperation { repo.changeJazzRoom(it,room,false) } } }
            val unfinished = view.operations.any { it.result_resource_id == room.resource_id && it.state == "partial" }
            if (repository.canPerform(nodeId,"replace-jazz-room") && room.state != "legacy_dedicated" && (room.state!="retired" || unfinished)) button(if (unfinished) "Продолжить замену" else "Создать замену") {
                confirmReplacement(room)
            }
        }
    }
    private fun confirmReplacement(room: JazzRoomView) {
        val dialog = AlertDialog.Builder(this).setMessage("Сервер заменит комнату и обновит маршруты её устройств через управляющий канал.")
            .setPositiveButton("Создать замену") { _, _ -> val repo = repository; runOperation { repo.changeJazzRoom(it, room, true) } }
            .setNegativeButton("Отмена", null).create()
        dialogs.show(dialog, "replace", Bundle().apply { putString("id", room.resource_id) })
    }

    private fun number(label: String,value: String,editable: Boolean): EditText {
        val field = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; content.addView(this); addView(TextView(this@JazzRoomPolicyActivity).apply { text=label }) }
        return EditText(this).apply { id = io.github.libreroute.ui.DialogScreens.stateId(label); inputType=InputType.TYPE_CLASS_NUMBER; setText(value); isEnabled=editable&&!busy; minHeight=48.dp(); field.addView(this) }
    }
    private fun button(label: String,action: ()->Unit) { content.addView(MaterialButton(this).apply { text=label; isAllCaps=false; isEnabled=!busy; minHeight=48.dp(); setOnClickListener { action() } },LinearLayout.LayoutParams(-1,56.dp())) }
    private fun watcher(changed: (String)->Unit) = object:android.text.TextWatcher { override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){};override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){changed(s.toString())};override fun afterTextChanged(s:android.text.Editable?){} }
    private fun Int.dp()=(this*resources.displayMetrics.density).toInt()
}
