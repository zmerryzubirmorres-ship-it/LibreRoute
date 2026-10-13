package io.github.libreroute.ui

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.res.Configuration
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.viewModels
import androidx.lifecycle.repeatOnLifecycle
import androidx.core.view.isVisible
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import io.github.libreroute.R
import io.github.libreroute.data.*
import io.github.libreroute.util.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TrafficRulesActivity : AppCompatActivity() {
    private data class InstalledApp(val key: String, val name: String, val uid: Int?, val system: Boolean, val icon: Drawable?)
    private data class Row(val key: String, val name: String, val route: String, val icon: Drawable?, val site: Boolean, val checked: Boolean, val assignedAction: String?)
    private var section = "apps"
    private var action = "tunnel"
    private var profileId = ZapretProfile.DEFAULT.id
    private var appSearch = ""
    private var siteSearch = ""
    private var appGroup = "all"
    private var siteGroup = "all"
    private var binding = false
    private var inventoryReady = false
    private var loading = true
    private val operation by viewModels<ScreenOperationViewModel>()
    private val inventory by viewModels<InstalledAppsViewModel>()
    private val applyingPreset get() = operation.state.value.running
    private val dialogs by lazy { DialogScreens(this) { key, _ ->
        when (key) { "help" -> showHelp(); "preset" -> showRussianPreset(); "reset" -> resetSelection() }
    } }
    private var apps: List<InstalledApp> = emptyList()
    private var appState = AppRoutingState()
    private var siteState = SiteRoutingState()
    private var profiles = ZapretProfileState()
    private val selectedApps = linkedSetOf<String>()
    private val selectedSites = linkedSetOf<String>()
    private lateinit var adapter: RowsAdapter
    private var visibleRows: List<Row> = emptyList()
    private var addDialog: BottomSheetDialog? = null
    private var addInput: TextInputEditText? = null

    private val profileEditor = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) result.data?.getStringExtra("profile_id")?.let {
            profileId = it
            action = "zapret"
        }
        render()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemePreferences(this).applyTheme()
        setContentView(R.layout.activity_traffic_rules)
        section = savedInstanceState?.getString("section") ?: intent.getStringExtra("section") ?: "apps"
        if (section !in setOf("apps", "sites")) section = "apps"
        action = savedInstanceState?.getString("action") ?: "tunnel"
        profileId = savedInstanceState?.getString("profile") ?: ZapretProfile.DEFAULT.id
        appSearch = savedInstanceState?.getString("app_search").orEmpty()
        siteSearch = savedInstanceState?.getString("site_search").orEmpty()
        appGroup = savedInstanceState?.getString("app_group")?.takeIf { it in setOf("all", "direct", "zapret", "tunnel", "inherit") } ?: "all"
        siteGroup = savedInstanceState?.getString("site_group")?.takeIf { it in setOf("all", "direct", "zapret", "tunnel") } ?: "all"
        selectedApps.addAll(savedInstanceState?.getStringArrayList("selected_apps").orEmpty())
        selectedSites.addAll(savedInstanceState?.getStringArrayList("selected_sites").orEmpty())
        if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            findViewById<View>(R.id.routing_header).layoutParams.height = dp(116)
        }
        adapter = RowsAdapter { row -> toggleSelection(row) }
        adapter.stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
        findViewById<RecyclerView>(R.id.routing_list).apply {
            layoutManager = LinearLayoutManager(this@TrafficRulesActivity)
            adapter = this@TrafficRulesActivity.adapter
        }
        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }
        findViewById<View>(R.id.routing_profiles).setOnClickListener {
            startActivity(Intent(this, ZapretProfilesActivity::class.java))
        }
        findViewById<View>(R.id.routing_help).setOnClickListener { showHelp() }
        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                operation.state.collect { state ->
                    state.result?.let { result ->
                        operation.consumed()
                        Toast.makeText(this@TrafficRulesActivity, result.exceptionOrNull()?.message ?: getString(R.string.routing_saved), Toast.LENGTH_LONG).show()
                        render()
                    }
                    renderRows()
                }
            }
        }
        findViewById<MaterialButtonToggleGroup>(R.id.routing_sections).addOnButtonCheckedListener { _, id, checked ->
            if (checked && !binding) {
                section = if (id == R.id.routing_sites) "sites" else "apps"
                binding = true
                findViewById<TextInputEditText>(R.id.routing_search).setText(if (section == "apps") appSearch else siteSearch)
                binding = false
                render()
            }
        }
        findViewById<MaterialButtonToggleGroup>(R.id.routing_actions).addOnButtonCheckedListener { _, id, checked ->
            if (checked && !binding) {
                action = when (id) { R.id.routing_direct -> "direct"; R.id.routing_zapret -> "zapret"; else -> "tunnel" }
                renderAssignment()
            }
        }
        findViewById<MaterialSwitch>(R.id.routing_enabled).setOnCheckedChangeListener { _, enabled ->
            if (!binding) runEdit {
                if (section == "apps") {
                    require(!enabled || Build.VERSION.SDK_INT >= 29) { getString(R.string.app_rules_unsupported) }
                    AppRoutingPreferences(this).store(appState.copy(enabled = enabled), profiles)
                } else SiteRoutingPreferences(this).store(siteState.copy(enabled = enabled), profiles)
            }
        }
        findViewById<MaterialCheckBox>(R.id.routing_show_system).apply {
            isChecked = !SplitTunnelPreferences(this@TrafficRulesActivity).hideSystemApps
            setOnCheckedChangeListener { _, checked ->
                SplitTunnelPreferences(this@TrafficRulesActivity).hideSystemApps = !checked
                renderRows()
            }
        }
        findViewById<TextInputEditText>(R.id.routing_search).apply {
            setText(if (section == "apps") appSearch else siteSearch)
            doAfterTextChanged {
                if (!binding) {
                    if (section == "apps") appSearch = it.toString() else siteSearch = it.toString()
                    renderRows()
                }
            }
        }
        findViewById<ChipGroup>(R.id.routing_filters).setOnCheckedStateChangeListener { _, ids ->
            if (!binding && ids.isNotEmpty()) {
                val group = when (ids.first()) {
                    R.id.routing_filter_direct -> "direct"
                    R.id.routing_filter_zapret -> "zapret"
                    R.id.routing_filter_tunnel -> "tunnel"
                    R.id.routing_filter_inherit -> "inherit"
                    else -> "all"
                }
                if (section == "apps") appGroup = group else siteGroup = group
                hideKeyboard()
                renderRows()
            }
        }
        listOf(R.id.routing_filter_direct to R.color.calm_success, R.id.routing_filter_zapret to R.color.calm_warning,
            R.id.routing_filter_tunnel to R.color.calm_info, R.id.routing_filter_inherit to R.color.text_secondary).forEach { (id, color) ->
            findViewById<Chip>(id).apply {
                chipIcon = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(ContextCompat.getColor(context, color)); setSize(dp(10), dp(10)) }
                chipIconTint = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(context, color))
                isChipIconVisible = true
            }
        }
        findViewById<View>(R.id.routing_select_all).setOnClickListener { hideKeyboard(); selection().addAll(visibleRows.map { it.key }); renderRows() }
        findViewById<View>(R.id.routing_clear_selection).setOnClickListener { selection().clear(); renderRows() }
        findViewById<View>(R.id.routing_apply).setOnClickListener { applySelection() }
        findViewById<View>(R.id.routing_reset).setOnClickListener { resetSelection() }
        findViewById<View>(R.id.routing_new_profile).setOnClickListener { openProfileEditor(create = true) }
        findViewById<View>(R.id.routing_edit_profile).setOnClickListener { openProfileEditor(create = false) }
        findViewById<MaterialAutoCompleteTextView>(R.id.routing_profile).setOnItemClickListener { _, _, position, _ ->
            profileId = profiles.profiles[position].id
            renderAssignment()
        }
        findViewById<View>(R.id.routing_add_site).setOnClickListener { showAddSite() }
        findViewById<View>(R.id.routing_russian_preset).setOnClickListener { showRussianPreset() }
        findViewById<MaterialSwitch>(R.id.routing_default_tunnel).setOnCheckedChangeListener { _, tunnel ->
            if (!binding) runEdit {
                val prefs = SiteRoutingPreferences(this)
                prefs.store(prefs.load().copy(defaultTunnel = tunnel), ZapretProfilePreferences(this).load())
                Toast.makeText(this, R.string.routing_saved, Toast.LENGTH_LONG).show()
            }
        }
        loadInventory()
        if (savedInstanceState?.getBoolean("adding_site") == true) showAddSite(savedInstanceState.getString("new_domain").orEmpty())
        dialogs.restore()
    }

    override fun onResume() { super.onResume(); render() }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("section", section); outState.putString("action", action); outState.putString("profile", profileId)
        outState.putString("app_search", appSearch); outState.putString("site_search", siteSearch)
        outState.putString("app_group", appGroup); outState.putString("site_group", siteGroup)
        outState.putStringArrayList("selected_apps", ArrayList(selectedApps))
        outState.putStringArrayList("selected_sites", ArrayList(selectedSites))
        outState.putBoolean("adding_site", addDialog?.isShowing == true)
        outState.putString("new_domain", addInput?.text?.toString())
        super.onSaveInstanceState(outState)
    }

    private fun loadInventory() {
        inventory.ensureLoaded()
        applyInventory(inventory.state.value)
        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                inventory.state.collect { state ->
                    applyInventory(state)
                    if (state.failed) Toast.makeText(this@TrafficRulesActivity, R.string.app_rules_error, Toast.LENGTH_LONG).show()
                    renderRows()
                }
            }
        }
    }

    private fun applyInventory(state: InstalledAppsViewModel.State) {
        loading = state.loading
        inventoryReady = state.apps != null
        state.apps?.let { list -> apps = list.map { InstalledApp(it.key, it.name, it.uid, it.system, it.icon) } }
    }

    private fun selection() = if (section == "apps") selectedApps else selectedSites

    private fun render() {
        runCatching {
            profiles = ZapretProfilePreferences(this).load()
            appState = AppRoutingPreferences(this).load().also { it.validate(profiles) }
            siteState = SiteRoutingPreferences(this).load().also { it.validate(profiles) }
        }.onFailure { Toast.makeText(this, it.message, Toast.LENGTH_LONG).show() }
        if (profiles.profiles.none { it.id == profileId }) profileId = ZapretProfile.DEFAULT.id
        selectedSites.retainAll(siteState.rules.map { it.domain }.toSet())
        binding = true
        findViewById<MaterialButtonToggleGroup>(R.id.routing_sections).check(if (section == "apps") R.id.routing_apps else R.id.routing_sites)
        findViewById<MaterialSwitch>(R.id.routing_default_tunnel).isChecked = siteState.defaultTunnel
        findViewById<TextView>(R.id.routing_default_hint).setText(
            if (siteState.defaultTunnel) R.string.routing_default_hint_tunnel else R.string.routing_default_hint_direct)
        findViewById<View>(R.id.routing_standalone_hint).isVisible =
            TunnelRepository(this).getSelected()?.let(DirectDpiProfile::isDirect) == true
        findViewById<MaterialSwitch>(R.id.routing_enabled).apply {
            setText(if (section == "apps") R.string.routing_enabled_apps else R.string.routing_enabled_sites)
            isChecked = if (section == "apps") appState.enabled else siteState.enabled
            isEnabled = section == "sites" || Build.VERSION.SDK_INT >= 29 || appState.enabled
        }
        findViewById<View>(R.id.routing_show_system).isVisible = section == "apps"
        findViewById<View>(R.id.routing_add_site).isVisible = section == "sites"
        findViewById<TextView>(R.id.routing_hint).setText(when {
            section == "apps" && Build.VERSION.SDK_INT < 29 -> R.string.app_rules_unsupported
            if (section == "apps") appState.enabled else siteState.enabled -> R.string.routing_on_hint
            else -> R.string.routing_off_hint
        })
        binding = false
        renderRows()
    }

    private fun routeLabel(action: String?, profile: String = ""): String {
        if (action == null) return getString(R.string.app_rules_inherit)
        val label = resources.getStringArray(R.array.site_actions)[SiteRule.ACTIONS.indexOf(action)]
        return if (action == "zapret") getString(R.string.site_action_profile, label, profiles.profiles.first { it.id == profile }.name) else label
    }

    private fun renderRows() {
        val query = (if (section == "apps") appSearch else siteSearch).trim()
        val group = if (section == "apps") appGroup else siteGroup
        binding = true
        findViewById<View>(R.id.routing_filter_inherit).isVisible = section == "apps"
        findViewById<ChipGroup>(R.id.routing_filters).check(when (group) {
            "direct" -> R.id.routing_filter_direct; "zapret" -> R.id.routing_filter_zapret
            "tunnel" -> R.id.routing_filter_tunnel; "inherit" -> R.id.routing_filter_inherit
            else -> R.id.routing_filter_all
        })
        binding = false
        val rows = if (section == "apps") {
            val installedKeys = apps.map { it.key }.toSet()
            val missing = appState.rules.filter { it.packageName !in installedKeys }.map { InstalledApp(it.packageName, it.packageName, null, false, null) }
            val rules = appState.rules.associateBy { it.packageName }
            val showSystem = findViewById<MaterialCheckBox>(R.id.routing_show_system).isChecked
            (apps + if (inventoryReady) missing else emptyList()).filter {
                (showSystem || !it.system || it.key in rules || it.key in selectedApps) && (it.name.contains(query, true) || it.key.contains(query, true))
            }.sortedWith(compareByDescending<InstalledApp> { it.key in rules }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }).map {
                val rule = rules[it.key]
                val route = routeLabel(rule?.action, rule?.profile.orEmpty())
                Row(it.key, it.name, if (it.uid == null) "$route · ${getString(R.string.app_rules_not_installed)}" else route, it.icon, false, it.key in selectedApps, rule?.action)
            }
        } else siteState.rules.sortedBy { it.domain }.filter { it.domain.contains(query, true) }.map {
            Row(it.domain, it.domain, routeLabel(it.action, it.profile), null, true, it.domain in selectedSites, it.action)
        }
        visibleRows = rows.filter { group == "all" || (it.assignedAction ?: "inherit") == group }
        adapter.submitList(visibleRows)
        findViewById<View>(R.id.routing_loading).isVisible = section == "apps" && loading
        findViewById<TextView>(R.id.routing_empty).apply {
            isVisible = visibleRows.isEmpty() && !(section == "apps" && loading)
            setText(if (query.isNotEmpty()) R.string.routing_no_search_matches else if (group != "all") R.string.routing_empty_group else if (section == "apps") R.string.routing_empty_apps else R.string.routing_empty_sites)
        }
        findViewById<TextView>(R.id.routing_visible_count).text = getString(R.string.routing_visible_count, visibleRows.size)
        findViewById<TextView>(R.id.routing_selected_count).text = getString(R.string.routing_count, selection().size)
        findViewById<View>(R.id.routing_select_all).isEnabled = visibleRows.isNotEmpty()
        findViewById<View>(R.id.routing_russian_preset).isEnabled = inventoryReady && !applyingPreset
        findViewById<View>(R.id.routing_clear_selection).isEnabled = selection().isNotEmpty()
        renderAssignment()
    }

    private fun renderAssignment() {
        findViewById<View>(R.id.routing_assignment).isVisible = selection().isNotEmpty()
        binding = true
        findViewById<MaterialButtonToggleGroup>(R.id.routing_actions).check(when (action) {
            "direct" -> R.id.routing_direct; "zapret" -> R.id.routing_zapret; else -> R.id.routing_tunnel
        })
        findViewById<View>(R.id.routing_profile_row).isVisible = action == "zapret"
        findViewById<MaterialAutoCompleteTextView>(R.id.routing_profile).apply {
            setAdapter(ArrayAdapter(this@TrafficRulesActivity, android.R.layout.simple_dropdown_item_1line, profiles.profiles.map { it.name }))
            setText(profiles.profiles.first { it.id == profileId }.name, false)
        }
        findViewById<View>(R.id.routing_edit_profile).contentDescription = getString(if (profileId == ZapretProfile.DEFAULT.id) R.string.zapret_copy else R.string.routing_edit_profile)
        findViewById<View>(R.id.routing_new_profile).isEnabled = profiles.profiles.size < 32
        findViewById<View>(R.id.routing_apply).isEnabled = selection().isNotEmpty() && (section == "sites" || inventoryReady)
        findViewById<View>(R.id.routing_reset).contentDescription = getString(if (section == "apps") R.string.routing_inherit else R.string.routing_remove_sites)
        binding = false
    }

    private fun toggleSelection(row: Row) {
        hideKeyboard()
        val selected = selection()
        if (row.key in selected) selected.remove(row.key) else {
            if (selected.isEmpty()) {
                val rule = if (section == "apps") appState.rules.firstOrNull { it.packageName == row.key }?.let { it.action to it.profile }
                    else siteState.rules.firstOrNull { it.domain == row.key }?.let { it.action to it.profile }
                if (rule != null) { action = rule.first; if (rule.second.isNotEmpty()) profileId = rule.second }
            }
            selected.add(row.key)
        }
        renderRows()
    }

    private fun runEdit(edit: () -> Unit) {
        runCatching(edit).onFailure { Toast.makeText(this, it.message, Toast.LENGTH_LONG).show() }
        render()
    }

    private fun applySelection() {
        runEdit {
            val currentProfiles = ZapretProfilePreferences(this).load()
            if (section == "apps") {
                check(inventoryReady)
                val prefs = AppRoutingPreferences(this)
                val updated = prefs.load().assign(selectedApps, action, profileId, currentProfiles)
                appRulesByUid(updated, apps.mapNotNull { app -> app.uid?.let { app.key to it } }.toMap())
                prefs.store(updated, currentProfiles)
            } else {
                val prefs = SiteRoutingPreferences(this)
                prefs.store(prefs.load().assign(selectedSites, action, profileId, currentProfiles), currentProfiles)
            }
            selection().clear()
            Toast.makeText(this, R.string.routing_saved, Toast.LENGTH_LONG).show()
        }
    }

    private fun resetSelection() {
        fun reset() = runEdit {
            if (section == "apps") {
                val prefs = AppRoutingPreferences(this)
                prefs.store(prefs.load().assign(selectedApps, null, "", profiles), profiles)
            } else {
                val prefs = SiteRoutingPreferences(this)
                prefs.store(prefs.load().copy(rules = prefs.load().rules.filterNot { it.domain in selectedSites }), profiles)
            }
            selection().clear()
        }
        if (section == "apps") reset() else MaterialAlertDialogBuilder(this).setMessage(R.string.routing_delete_sites_confirm)
            .setNegativeButton(android.R.string.cancel, null).setPositiveButton(R.string.site_delete) { _, _ -> reset() }.create().let { dialogs.show(it, "reset") }
    }

    private fun openProfileEditor(create: Boolean) {
        profileEditor.launch(Intent(this, ZapretProfileEditActivity::class.java).apply {
            if (!create) { putExtra("profile_id", profileId); putExtra("copy", profileId == ZapretProfile.DEFAULT.id) }
        })
    }

    private fun showHelp() {
        val dialog = MaterialAlertDialogBuilder(this).setTitle(R.string.routing_help).setMessage(R.string.routing_help_text)
            .setPositiveButton(android.R.string.ok, null).setNeutralButton(R.string.routing_legacy) { _, _ ->
                startActivity(Intent(this, SplitTunnelActivity::class.java))
            }.create()
        dialogs.show(dialog, "help")
    }

    private fun showRussianPreset() {
        if (applyingPreset) return
        hideKeyboard()
        MaterialAlertDialogBuilder(this).setTitle(R.string.routing_russian_preset)
            .setMessage(R.string.routing_russian_preset_description)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.routing_preset_apply) { _, _ ->
                if (!inventoryReady || applyingPreset) return@setPositiveButton
                val installedUids = apps.mapNotNull { app -> app.uid?.let { app.key to it } }.toMap()
                val app = applicationContext
                operation.run {
                    val profiles = ZapretProfilePreferences(app).load()
                    val sitePrefs = SiteRoutingPreferences(app)
                    val appPrefs = AppRoutingPreferences(app)
                    val sites = TrafficRoutingPresets.russianSites(sitePrefs.load(), DomainRulesPreferences.getPresetRu(app), profiles)
                    val apps = if (Build.VERSION.SDK_INT >= 29) TrafficRoutingPresets.russianApps(appPrefs.load(), RussianAppsPreset.PACKAGE_NAMES, installedUids, profiles) else null
                    sitePrefs.store(sites, profiles)
                    apps?.let { appPrefs.store(it, profiles) }
                    "saved"
                }
            }.create().let { dialogs.show(it, "preset", Bundle()) }
    }

    private fun showAddSite(draft: String = "") {
        val dialog = BottomSheetDialog(this)
        val content = layoutInflater.inflate(R.layout.bottom_sheet_add_site, null)
        dialog.setContentView(content)
        val input = content.findViewById<TextInputEditText>(R.id.input_domain_value).apply { setText(draft) }
        addDialog = dialog; addInput = input
        content.findViewById<View>(R.id.btn_cancel_add_site).setOnClickListener { dialog.dismiss() }
        content.findViewById<View>(R.id.btn_confirm_add_site).setOnClickListener {
            runCatching {
                val rule = SiteRule(SiteRule.clean(input.text.toString()), "tunnel")
                val prefs = SiteRoutingPreferences(this)
                prefs.store(prefs.load().save(rule, null, ZapretProfilePreferences(this).load()), profiles)
                selectedSites.add(rule.domain)
                action = "tunnel"
            }.fold(onSuccess = { dialog.dismiss(); render() }, onFailure = {
                content.findViewById<TextInputLayout>(R.id.layout_domain_input).error = it.message
            })
        }
        dialog.setOnDismissListener { if (addDialog === dialog) { addDialog = null; addInput = null } }
        dialog.show()
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun hideKeyboard() {
        val input = findViewById<View>(R.id.routing_search)
        getSystemService(android.view.inputmethod.InputMethodManager::class.java)?.hideSoftInputFromWindow(input.windowToken, 0)
        input.clearFocus()
    }

    override fun onDestroy() {
        addDialog?.dismiss()
        super.onDestroy()
    }

    private class RowsAdapter(private val toggle: (Row) -> Unit) : ListAdapter<Row, RowsAdapter.Holder>(object : DiffUtil.ItemCallback<Row>() {
        override fun areItemsTheSame(old: Row, new: Row) = old.key == new.key && old.site == new.site
        override fun areContentsTheSame(old: Row, new: Row) = old == new
    }) {
        class Holder(view: View) : RecyclerView.ViewHolder(view)
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_traffic_rule, parent, false))
        override fun onBindViewHolder(holder: Holder, position: Int) {
            val row = getItem(position)
            holder.itemView.findViewById<TextView>(R.id.app_name).text = row.name
            val color = ContextCompat.getColor(holder.itemView.context, when (row.assignedAction) {
                "direct" -> R.color.calm_success; "zapret" -> R.color.calm_warning
                "tunnel" -> R.color.calm_info; else -> R.color.text_secondary
            })
            val density = holder.itemView.resources.displayMetrics.density
            holder.itemView.findViewById<TextView>(R.id.app_package).apply {
                text = row.route
                setTextColor(color)
                background = GradientDrawable().apply { cornerRadius = 6 * density; setColor(ColorUtils.setAlphaComponent(color, 25)) }
            }
            holder.itemView.findViewById<View>(R.id.row_action_marker).background = GradientDrawable().apply {
                cornerRadius = 2 * density; setColor(color)
            }
            holder.itemView.findViewById<ImageView>(R.id.app_icon).apply {
                if (row.icon != null) setImageDrawable(row.icon) else setImageResource(if (row.site) R.drawable.ic_domain else R.drawable.ic_apps)
            }
            holder.itemView.findViewById<MaterialCheckBox>(R.id.app_checkbox).apply {
                isChecked = row.checked
                contentDescription = row.name
            }
            holder.itemView.setOnClickListener {
                val index = holder.bindingAdapterPosition
                if (index != RecyclerView.NO_POSITION) toggle(getItem(index))
            }
        }
    }
}
