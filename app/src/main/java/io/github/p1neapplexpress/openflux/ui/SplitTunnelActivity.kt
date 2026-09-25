package io.github.p1neapplexpress.openflux.ui

import android.content.Context

import android.content.Intent

import android.content.pm.ApplicationInfo

import android.content.pm.PackageManager

import android.content.pm.ResolveInfo

import android.os.Bundle

import android.view.HapticFeedbackConstants

import android.view.LayoutInflater

import android.view.View

import android.view.ViewGroup

import android.view.inputmethod.EditorInfo

import android.view.inputmethod.InputMethodManager

import android.widget.CheckBox

import android.widget.EditText

import android.widget.ImageView

import android.widget.ProgressBar

import android.widget.TextView

import android.widget.Toast

import androidx.activity.OnBackPressedCallback

import androidx.appcompat.app.AppCompatActivity

import androidx.core.view.WindowCompat

import androidx.core.view.isVisible

import androidx.core.widget.doAfterTextChanged

import androidx.lifecycle.lifecycleScope

import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView

import com.google.android.material.bottomsheet.BottomSheetDialog

import com.google.android.material.button.MaterialButton

import com.google.android.material.button.MaterialButtonToggleGroup

import com.google.android.material.materialswitch.MaterialSwitch

import com.google.android.material.textfield.TextInputEditText

import com.google.android.material.textfield.TextInputLayout

import io.github.p1neapplexpress.openflux.R

import io.github.p1neapplexpress.openflux.data.AppItem

import io.github.p1neapplexpress.openflux.util.DomainRulesPreferences

import io.github.p1neapplexpress.openflux.util.RussianAppsPreset

import io.github.p1neapplexpress.openflux.util.SplitTunnelPreferences

import io.github.p1neapplexpress.openflux.util.performAppHaptics

import io.github.p1neapplexpress.openflux.util.ThemePreferences

import kotlinx.coroutines.Dispatchers

import kotlinx.coroutines.launch

import kotlinx.coroutines.withContext

class SplitTunnelActivity : AppCompatActivity() {

    companion object {
        private const val SECTION_APPS = 0
        private const val SECTION_SITES = 1
        private const val PAYLOAD_CHECKED = "payload_checked"
    }

    private var activeSection = SECTION_APPS

    // App Preferences & Data

    private lateinit var appPrefs: SplitTunnelPreferences

    private lateinit var appsAdapter: AppsAdapter

    private val allApps: MutableList<AppItem> = mutableListOf()

    // Domain Preferences & Data

    private lateinit var domainPrefs: DomainRulesPreferences

    private lateinit var domainsAdapter: DomainsAdapter

    private val allDomains: MutableList<String> = mutableListOf()

    // Common Views

    private lateinit var btnBack: ImageView

    private lateinit var titleContainer: View

    private lateinit var searchBarContainer: View

    private lateinit var btnSearchToggle: ImageView

    private lateinit var searchInput: EditText

    private lateinit var btnClearSearch: ImageView

    private lateinit var sectionToggleGroup: MaterialButtonToggleGroup

    private lateinit var btnSectionApps: MaterialButton

    private lateinit var btnSectionSites: MaterialButton

    // Apps Section Views

    private lateinit var containerAppsSection: View

    private lateinit var appToggleGroup: MaterialButtonToggleGroup

    private lateinit var btnAppTabBypass: MaterialButton

    private lateinit var btnAppTabProxy: MaterialButton

    private lateinit var appModeDescription: TextView

    private lateinit var appSelectedCountText: TextView

    private lateinit var switchHideSystem: MaterialSwitch

    private lateinit var btnAppSelectAll: MaterialButton

    private lateinit var btnAppResetDefaults: MaterialButton

    private lateinit var appLoadingProgress: ProgressBar

    private lateinit var appEmptyState: View

    private lateinit var appsRecycler: RecyclerView

    // Sites Section Views

    private lateinit var containerSitesSection: View

    private lateinit var sitesToggleGroup: MaterialButtonToggleGroup

    private lateinit var btnSitesTabBypass: MaterialButton

    private lateinit var btnSitesTabProxy: MaterialButton

    private lateinit var sitesModeDescription: TextView

    private lateinit var sitesCountText: TextView

    private lateinit var btnAddSite: MaterialButton

    private lateinit var btnSitePresets: MaterialButton

    private lateinit var btnClearSites: MaterialButton

    private lateinit var sitesEmptyState: View

    private lateinit var sitesRecycler: RecyclerView

    override fun onCreate(savedInstanceState: Bundle?) {

        super.onCreate(savedInstanceState)

        val themePrefs = ThemePreferences(this)

        themePrefs.applyTheme()

        val isNight = when (themePrefs.themeMode) {

            ThemePreferences.THEME_LIGHT -> false

            ThemePreferences.THEME_DARK -> true

            else -> (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES

        }

        val insetsController = WindowCompat.getInsetsController(window, window.decorView)

        insetsController.isAppearanceLightStatusBars = !isNight

        insetsController.isAppearanceLightNavigationBars = !isNight

        setContentView(R.layout.activity_split_tunnel)

        appPrefs = SplitTunnelPreferences(this)

        domainPrefs = DomainRulesPreferences(this)

        initViews()

        setupListeners()

        loadInstalledApps()

        loadDomains()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {

            override fun handleOnBackPressed() {

                if (searchBarContainer.isVisible) {

                    closeSearch()

                } else {

                    isEnabled = false

                    onBackPressedDispatcher.onBackPressed()

                }

            }

        })

    }

    private fun initViews() {

        btnBack = findViewById(R.id.btn_back)

        titleContainer = findViewById(R.id.title_container)

        searchBarContainer = findViewById(R.id.search_bar_container)

        btnSearchToggle = findViewById(R.id.btn_search_toggle)

        searchInput = findViewById(R.id.search_input)

        btnClearSearch = findViewById(R.id.btn_clear_search)

        sectionToggleGroup = findViewById(R.id.section_toggle_group)

        btnSectionApps = findViewById(R.id.btn_section_apps)

        btnSectionSites = findViewById(R.id.btn_section_sites)

        // Apps views

        containerAppsSection = findViewById(R.id.container_apps_section)

        appToggleGroup = findViewById(R.id.mode_toggle_group)

        btnAppTabBypass = findViewById(R.id.btn_tab_bypass)

        btnAppTabProxy = findViewById(R.id.btn_tab_proxy)

        appModeDescription = findViewById(R.id.mode_description)

        appSelectedCountText = findViewById(R.id.selected_count_text)

        switchHideSystem = findViewById(R.id.switch_hide_system)

        btnAppSelectAll = findViewById(R.id.btn_select_all)

        btnAppResetDefaults = findViewById(R.id.btn_reset_defaults)

        appLoadingProgress = findViewById(R.id.loading_progress)

        appEmptyState = findViewById(R.id.empty_state)

        appsRecycler = findViewById(R.id.apps_recycler)

        // Sites views

        containerSitesSection = findViewById(R.id.container_sites_section)

        sitesToggleGroup = findViewById(R.id.sites_mode_toggle_group)

        btnSitesTabBypass = findViewById(R.id.btn_sites_tab_bypass)

        btnSitesTabProxy = findViewById(R.id.btn_sites_tab_proxy)

        sitesModeDescription = findViewById(R.id.sites_mode_description)

        sitesCountText = findViewById(R.id.sites_count_text)

        btnAddSite = findViewById(R.id.btn_add_site)

        btnSitePresets = findViewById(R.id.btn_site_presets)

        btnClearSites = findViewById(R.id.btn_clear_sites)

        sitesEmptyState = findViewById(R.id.sites_empty_state)

        sitesRecycler = findViewById(R.id.sites_recycler)

        appsRecycler.layoutManager = LinearLayoutManager(this)

        appsAdapter = AppsAdapter { app, isSelected ->

            onAppToggled(app, isSelected)

        }

        appsRecycler.adapter = appsAdapter

        sitesRecycler.layoutManager = LinearLayoutManager(this)

        domainsAdapter = DomainsAdapter { domain ->

            deleteDomain(domain)

        }

        sitesRecycler.adapter = domainsAdapter

    }

    private fun setupListeners() {

        btnBack.setOnClickListener {
            it.performAppHaptics(HapticFeedbackConstants.VIRTUAL_KEY)
            if (searchBarContainer.isVisible) {
                closeSearch()
            } else {
                finish()
            }
        }

        // Section Switcher: Apps vs Sites
        sectionToggleGroup.check(R.id.btn_section_apps)
        sectionToggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                sectionToggleGroup.performAppHaptics()
                activeSection = if (checkedId == R.id.btn_section_apps) SECTION_APPS else SECTION_SITES
                containerAppsSection.isVisible = (activeSection == SECTION_APPS)
                containerSitesSection.isVisible = (activeSection == SECTION_SITES)
                applySearchFilter(searchInput.text?.toString().orEmpty())
            }
        }

        // Apps Mode Toggle (Bypass vs Proxy)
        val isAppBypass = appPrefs.mode == SplitTunnelPreferences.MODE_BYPASS
        appToggleGroup.check(if (isAppBypass) R.id.btn_tab_bypass else R.id.btn_tab_proxy)
        updateAppModeDescription(isAppBypass)

        appToggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                appToggleGroup.performAppHaptics()
                val bypass = checkedId == R.id.btn_tab_bypass
                appPrefs.mode = if (bypass) SplitTunnelPreferences.MODE_BYPASS else SplitTunnelPreferences.MODE_PROXY
                updateAppModeDescription(bypass)
                syncAppSelectionsWithPrefs()
            }
        }

        // Hide System Apps Switch
        switchHideSystem.isChecked = appPrefs.hideSystemApps
        switchHideSystem.jumpDrawablesToCurrentState()
        switchHideSystem.setOnClickListener {
            switchHideSystem.performAppHaptics()
        }
        switchHideSystem.setOnCheckedChangeListener { _, isChecked ->
            appPrefs.hideSystemApps = isChecked
            applySearchFilter(searchInput.text?.toString().orEmpty())
        }

        btnAppSelectAll.setOnClickListener {
            it.performAppHaptics(HapticFeedbackConstants.VIRTUAL_KEY)
            toggleSelectAllApps()
        }

        btnAppResetDefaults.setOnClickListener {
            it.performAppHaptics(HapticFeedbackConstants.VIRTUAL_KEY)
            resetAppDefaults()
        }

        // Sites Mode Toggle (Bypass vs Proxy)
        val isSiteBypass = domainPrefs.mode == DomainRulesPreferences.MODE_BYPASS
        sitesToggleGroup.check(if (isSiteBypass) R.id.btn_sites_tab_bypass else R.id.btn_sites_tab_proxy)
        updateSitesModeDescription(isSiteBypass)

        sitesToggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                sitesToggleGroup.performAppHaptics()
                val bypass = checkedId == R.id.btn_sites_tab_bypass
                domainPrefs.mode = if (bypass) DomainRulesPreferences.MODE_BYPASS else DomainRulesPreferences.MODE_PROXY
                domainPrefs.writeRulesFile(this@SplitTunnelActivity)
                updateSitesModeDescription(bypass)
            }
        }

        btnAddSite.setOnClickListener {
            it.performAppHaptics(HapticFeedbackConstants.VIRTUAL_KEY)
            showAddSiteBottomSheet()
        }

        btnSitePresets.setOnClickListener {
            it.performAppHaptics(HapticFeedbackConstants.VIRTUAL_KEY)
            showPresetsBottomSheet()
        }

        btnClearSites.setOnClickListener {
            it.performAppHaptics(HapticFeedbackConstants.VIRTUAL_KEY)
            confirmClearSites()
        }

        // Search Bar listeners
        btnSearchToggle.setOnClickListener {
            it.performAppHaptics(HapticFeedbackConstants.VIRTUAL_KEY)
            openSearch()
        }

        btnClearSearch.setOnClickListener {
            it.performAppHaptics(HapticFeedbackConstants.VIRTUAL_KEY)

            if (searchInput.text.isNullOrEmpty()) {

                closeSearch()

            } else {

                searchInput.setText("")

            }

        }

        searchInput.doAfterTextChanged { text ->

            val q = text?.toString().orEmpty()

            btnClearSearch.isVisible = q.isNotEmpty()

            applySearchFilter(q)

        }

    }

    private fun updateAppModeDescription(isBypass: Boolean) {

        appModeDescription.text = if (isBypass) {

            getString(R.string.tab_bypass_desc)

        } else {

            getString(R.string.tab_proxy_desc)

        }

    }

    private fun updateSitesModeDescription(isBypass: Boolean) {

        sitesModeDescription.text = if (isBypass) {

            getString(R.string.split_sites_mode_bypass)

        } else {

            getString(R.string.split_sites_mode_proxy)

        }

    }

    // --- Apps Split Tunneling Logic ---

    private fun loadInstalledApps() {

        appLoadingProgress.isVisible = true

        appsRecycler.isVisible = false

        appEmptyState.isVisible = false

        lifecycleScope.launch(Dispatchers.IO) {

            val pm = packageManager

            val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {

                addCategory(Intent.CATEGORY_LAUNCHER)

            }

            val launchableList: List<ResolveInfo> = pm.queryIntentActivities(mainIntent, 0)

            val launchablePkgs = launchableList.map { it.activityInfo.packageName }.toSet()

            val installed = pm.getInstalledApplications(PackageManager.GET_META_DATA)

            val myPackage = packageName

            val currentSelected = getSelectedAppPackages()

            val list = mutableListOf<AppItem>()

            for (app in installed) {

                if (app.packageName == myPackage) continue

                val isLaunchable = launchablePkgs.contains(app.packageName)
                val isSystem = ((app.flags and ApplicationInfo.FLAG_SYSTEM) != 0) ||
                        ((app.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0) ||
                        !isLaunchable

                val label = try {

                    pm.getApplicationLabel(app).toString()

                } catch (_: Exception) {

                    app.packageName

                }

                val icon = try {

                    pm.getApplicationIcon(app)

                } catch (_: Exception) {

                    null

                }

                val selected = currentSelected.contains(app.packageName)

                list.add(

                    AppItem(

                        name = label,

                        packageName = app.packageName,

                        icon = icon,

                        isSelected = selected,

                        isSystem = isSystem

                    )

                )

            }

            list.sortWith(
                compareByDescending<AppItem> { it.isSelected }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
            )

            withContext(Dispatchers.Main) {

                allApps.clear()

                allApps.addAll(list)

                appLoadingProgress.isVisible = false

                appsRecycler.isVisible = true

                applySearchFilter(searchInput.text?.toString().orEmpty())

                updateAppCountDisplay()

            }

        }

    }

    private fun getSelectedAppPackages(): Set<String> {

        return if (appPrefs.mode == SplitTunnelPreferences.MODE_BYPASS) {

            appPrefs.bypassApps

        } else {

            appPrefs.proxyApps

        }

    }

    private fun saveSelectedAppPackages(selected: Set<String>) {

        if (appPrefs.mode == SplitTunnelPreferences.MODE_BYPASS) {

            appPrefs.bypassApps = selected

        } else {

            appPrefs.proxyApps = selected

        }

    }

    private fun onAppToggled(item: AppItem, isSelected: Boolean) {

        val idx = allApps.indexOfFirst { it.packageName == item.packageName }

        if (idx != -1) {

            allApps[idx] = allApps[idx].copy(isSelected = isSelected)

        }

        val currentSet = getSelectedAppPackages().toMutableSet()

        if (isSelected) {

            currentSet.add(item.packageName)

        } else {

            currentSet.remove(item.packageName)

        }

        saveSelectedAppPackages(currentSet)

        updateAppCountDisplay()

    }

    private fun toggleSelectAllApps() {

        val currentlyShowing = appsAdapter.currentList

        val allShowingSelected = currentlyShowing.isNotEmpty() && currentlyShowing.all { it.isSelected }

        val targetState = !allShowingSelected

        val activeSet = getSelectedAppPackages().toMutableSet()

        for (item in currentlyShowing) {

            if (targetState) {

                activeSet.add(item.packageName)

            } else {

                activeSet.remove(item.packageName)

            }

        }

        saveSelectedAppPackages(activeSet)

        syncAppSelectionsWithPrefs()

    }

    private fun resetAppDefaults() {

        val defaultRu = RussianAppsPreset.PACKAGE_NAMES

        if (appPrefs.mode == SplitTunnelPreferences.MODE_BYPASS) {

            appPrefs.bypassApps = defaultRu

        } else {

            appPrefs.proxyApps = emptySet()

        }

        syncAppSelectionsWithPrefs()

        Toast.makeText(this, R.string.reset_defaults, Toast.LENGTH_SHORT).show()

    }

    private fun syncAppSelectionsWithPrefs() {
        val selected = getSelectedAppPackages()
        for (i in allApps.indices) {
            val app = allApps[i]
            allApps[i] = app.copy(isSelected = selected.contains(app.packageName))
        }
        allApps.sortWith(
            compareByDescending<AppItem> { it.isSelected }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
        )
        applySearchFilter(searchInput.text?.toString().orEmpty())
        updateAppCountDisplay()
    }

    private fun updateAppCountDisplay() {

        val selectedCount = allApps.count { it.isSelected }

        val totalCount = if (appPrefs.hideSystemApps) { allApps.count { !it.isSystem || it.isSelected } } else { allApps.size }

        appSelectedCountText.text = getString(R.string.selected_apps_count, selectedCount, totalCount)

        // Update toggle button text and icon dynamically

        val currentlyShowing = appsAdapter.currentList

        val allSelected = currentlyShowing.isNotEmpty() && currentlyShowing.all { it.isSelected }

        if (allSelected) {

            btnAppSelectAll.setText(R.string.split_deselect_all)

            btnAppSelectAll.setIconResource(R.drawable.ic_clear)

        } else {

            btnAppSelectAll.setText(R.string.select_all)

            btnAppSelectAll.setIconResource(R.drawable.ic_check)

        }

    }

    // --- Sites Routing Logic ---

    private fun loadDomains() {

        allDomains.clear()

        allDomains.addAll(domainPrefs.domains.sorted())

        applySitesFilter(searchInput.text?.toString().orEmpty())

        updateSitesCountDisplay()

    }

    private fun updateSitesCountDisplay() {

        val count = domainPrefs.domains.size

        sitesCountText.text = getString(R.string.split_sites_count, count)

    }

    private fun deleteDomain(domain: String) {
        domainPrefs.removeDomain(domain)
        domainPrefs.writeRulesFile(this)
        loadDomains()
        Toast.makeText(this, R.string.domain_deleted, Toast.LENGTH_SHORT).show()
    }

    private fun showAddSiteBottomSheet() {
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.bottom_sheet_add_site, null)
        dialog.setContentView(view)

        val inputLayout = view.findViewById<TextInputLayout>(R.id.layout_domain_input)
        val inputEdit = view.findViewById<TextInputEditText>(R.id.input_domain_value)
        val btnCancel = view.findViewById<View>(R.id.btn_cancel_add_site)
        val btnConfirm = view.findViewById<View>(R.id.btn_confirm_add_site)

        btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        fun doAdd() {
            val raw = inputEdit.text?.toString().orEmpty().trim()
            val clean = raw.lowercase().removePrefix("https://").removePrefix("http://").trimEnd('/')
            if (clean.isBlank() || clean.contains(" ") || !clean.contains(".")) {
                inputLayout.error = getString(R.string.split_sites_invalid_domain)
                return
            }
            inputLayout.error = null

            val added = domainPrefs.addDomain(clean)
            if (added) {
                domainPrefs.writeRulesFile(this@SplitTunnelActivity)
            }
            dialog.dismiss()

            if (added) {
                loadDomains()
                Toast.makeText(this, R.string.domain_added, Toast.LENGTH_SHORT).show()

            }

        }

        btnConfirm.setOnClickListener {
            it.performAppHaptics(HapticFeedbackConstants.VIRTUAL_KEY)
            doAdd()
        }

        inputEdit.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                doAdd()
                true
            } else {
                false
            }
        }

        dialog.show()
        inputEdit.requestFocus()
    }

    private fun showPresetsBottomSheet() {
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.bottom_sheet_domain_presets, null)
        dialog.setContentView(view)

        fun applyPreset(preset: Set<String>, title: String) {
            dialog.dismiss()
            domainPrefs.addPreset(preset)
            domainPrefs.writeRulesFile(this@SplitTunnelActivity)
            loadDomains()
            Toast.makeText(
                this@SplitTunnelActivity,
                getString(R.string.split_sites_preset_applied, title),
                Toast.LENGTH_SHORT
            ).show()
        }

        // 1. Russian Services
        view.findViewById<View>(R.id.card_preset_ru).setOnClickListener {
            it.performAppHaptics(HapticFeedbackConstants.VIRTUAL_KEY)
            applyPreset(DomainRulesPreferences.getPresetRu(this), getString(R.string.split_sites_preset_ru_title))
        }

        // 2. Blocked Resources
        view.findViewById<View>(R.id.card_preset_blocked).setOnClickListener {
            it.performAppHaptics(HapticFeedbackConstants.VIRTUAL_KEY)
            applyPreset(DomainRulesPreferences.getPresetBlocked(this), getString(R.string.split_sites_preset_blocked_title))
        }

        // 3. YouTube
        view.findViewById<View>(R.id.card_preset_youtube).setOnClickListener {
            it.performAppHaptics(HapticFeedbackConstants.VIRTUAL_KEY)
            applyPreset(DomainRulesPreferences.getPresetYoutube(this), getString(R.string.split_sites_preset_youtube_title))
        }

        // 4. Discord
        view.findViewById<View>(R.id.card_preset_discord).setOnClickListener {
            it.performAppHaptics(HapticFeedbackConstants.VIRTUAL_KEY)
            applyPreset(DomainRulesPreferences.getPresetDiscord(this), getString(R.string.split_sites_preset_discord_title))
        }

        // 5. AI
        view.findViewById<View>(R.id.card_preset_ai).setOnClickListener {
            it.performAppHaptics(HapticFeedbackConstants.VIRTUAL_KEY)
            applyPreset(DomainRulesPreferences.getPresetAi(this), getString(R.string.split_sites_preset_ai_title))
        }

        view.findViewById<View>(R.id.btn_cancel_preset).setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun confirmClearSites() {
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.bottom_sheet_confirm_clear, null)
        dialog.setContentView(view)

        view.findViewById<View>(R.id.btn_cancel_clear).setOnClickListener {
            dialog.dismiss()
        }

        view.findViewById<View>(R.id.btn_confirm_clear).setOnClickListener {
            it.performAppHaptics(HapticFeedbackConstants.VIRTUAL_KEY)
            dialog.dismiss()
            domainPrefs.clearAll()
            domainPrefs.writeRulesFile(this)
            loadDomains()
            Toast.makeText(this, R.string.split_sites_clear, Toast.LENGTH_SHORT).show()
        }

        dialog.show()

    }

    // --- Search & Filtering ---

    private fun openSearch() {

        titleContainer.isVisible = false

        searchBarContainer.isVisible = true

        searchInput.requestFocus()

        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager

        imm?.showSoftInput(searchInput, InputMethodManager.SHOW_IMPLICIT)

    }

    private fun closeSearch() {

        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager

        imm?.hideSoftInputFromWindow(searchInput.windowToken, 0)

        searchInput.setText("")

        searchBarContainer.isVisible = false

        titleContainer.isVisible = true

        applySearchFilter("")

    }

    private fun applySearchFilter(query: String) {

        if (activeSection == SECTION_APPS) {

            applyAppsFilter(query)

        } else {

            applySitesFilter(query)

        }

    }

    private fun applyAppsFilter(query: String) {
        val q = query.trim().lowercase()
        val hideSys = appPrefs.hideSystemApps

        val baseList = if (hideSys) {
            allApps.filter { !it.isSystem || it.isSelected }
        } else {
            allApps
        }

        val filtered = (if (q.isEmpty()) {
            baseList
        } else {
            baseList.filter {
                it.name.contains(q, ignoreCase = true) || it.packageName.contains(q, ignoreCase = true)
            }
        }).sortedWith(
            compareByDescending<AppItem> { it.isSelected }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
        )
        appsAdapter.submitList(filtered)
        appEmptyState.isVisible = filtered.isEmpty() && allApps.isNotEmpty()
        updateAppCountDisplay()
    }

    private fun applySitesFilter(query: String) {

        val q = query.trim().lowercase()

        val filtered = if (q.isEmpty()) {

            allDomains.toList()

        } else {

            allDomains.filter { it.contains(q) }

        }

        domainsAdapter.submitList(filtered)

        sitesEmptyState.isVisible = filtered.isEmpty()

    }

    // --- Recycler View Adapters ---

    private class AppDiffCallback : DiffUtil.ItemCallback<AppItem>() {
        override fun areItemsTheSame(oldItem: AppItem, newItem: AppItem): Boolean =
            oldItem.packageName == newItem.packageName

        override fun areContentsTheSame(oldItem: AppItem, newItem: AppItem): Boolean =
            oldItem.name == newItem.name && oldItem.isSelected == newItem.isSelected

        override fun getChangePayload(oldItem: AppItem, newItem: AppItem): Any? {
            return if (oldItem.isSelected != newItem.isSelected && oldItem.name == newItem.name) {
                PAYLOAD_CHECKED
            } else {
                null
            }
        }
    }

    private class AppsAdapter(
        private val onToggle: (AppItem, Boolean) -> Unit
    ) : ListAdapter<AppItem, AppsAdapter.AppViewHolder>(AppDiffCallback()) {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppViewHolder {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_split_app, parent, false)
            return AppViewHolder(v)
        }

        override fun onBindViewHolder(holder: AppViewHolder, position: Int) {
            val item = getItem(position)
            holder.appName.text = item.name
            holder.appPackage.text = item.packageName
            if (item.icon != null) {
                holder.appIcon.setImageDrawable(item.icon)
            } else {
                holder.appIcon.setImageResource(R.mipmap.ic_launcher_round)
            }
            holder.checkbox.setOnCheckedChangeListener(null)
            holder.checkbox.isChecked = item.isSelected

            val toggleAction = {
                val pos = holder.bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION && pos in currentList.indices) {
                    val currentItem = getItem(pos)
                    val newState = !holder.checkbox.isChecked
                    holder.checkbox.isChecked = newState
                    onToggle(currentItem, newState)
                }
            }

            holder.itemView.setOnClickListener {
                it.performAppHaptics(HapticFeedbackConstants.CLOCK_TICK)
                toggleAction()
            }

            holder.checkbox.setOnClickListener {
                it.performAppHaptics(HapticFeedbackConstants.CLOCK_TICK)
                val pos = holder.bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION && pos in currentList.indices) {
                    val currentItem = getItem(pos)
                    onToggle(currentItem, holder.checkbox.isChecked)
                }
            }
        }

        override fun onBindViewHolder(holder: AppViewHolder, position: Int, payloads: MutableList<Any>) {
            if (payloads.contains(PAYLOAD_CHECKED)) {
                val item = getItem(position)
                holder.checkbox.setOnCheckedChangeListener(null)
                holder.checkbox.isChecked = item.isSelected
            } else {
                super.onBindViewHolder(holder, position, payloads)
            }
        }

        class AppViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val appIcon: ImageView = view.findViewById(R.id.app_icon)
            val appName: TextView = view.findViewById(R.id.app_name)
            val appPackage: TextView = view.findViewById(R.id.app_package)
            val checkbox: CheckBox = view.findViewById(R.id.app_checkbox)
        }
    }

    private class DomainDiffCallback : DiffUtil.ItemCallback<String>() {
        override fun areItemsTheSame(oldItem: String, newItem: String): Boolean = oldItem == newItem
        override fun areContentsTheSame(oldItem: String, newItem: String): Boolean = oldItem == newItem
    }

    private class DomainsAdapter(
        private val onDelete: (String) -> Unit
    ) : ListAdapter<String, DomainsAdapter.DomainViewHolder>(DomainDiffCallback()) {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DomainViewHolder {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_split_domain, parent, false)
            return DomainViewHolder(v)
        }

        override fun onBindViewHolder(holder: DomainViewHolder, position: Int) {
            val domain = getItem(position)
            holder.siteDomain.text = domain
            holder.siteType.text = holder.itemView.context.getString(R.string.split_sites_domain_rule)
            holder.btnDelete.setOnClickListener {
                it.performAppHaptics(HapticFeedbackConstants.VIRTUAL_KEY)
                onDelete(domain)
            }
        }

        class DomainViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val siteDomain: TextView = view.findViewById(R.id.site_domain)
            val siteType: TextView = view.findViewById(R.id.site_type)
            val btnDelete: ImageView = view.findViewById(R.id.btn_delete_site)
        }
    }

}
