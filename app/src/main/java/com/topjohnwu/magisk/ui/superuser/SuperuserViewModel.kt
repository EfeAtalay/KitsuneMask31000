package com.topjohnwu.magisk.ui.superuser

import android.annotation.SuppressLint
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.PackageManager.MATCH_UNINSTALLED_PACKAGES
import android.os.Process
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import androidx.databinding.Bindable
import androidx.databinding.ObservableArrayList
import androidx.lifecycle.viewModelScope
import com.topjohnwu.magisk.BR
import com.topjohnwu.magisk.R
import com.topjohnwu.magisk.arch.AsyncLoadViewModel
import com.topjohnwu.magisk.core.Config
import com.topjohnwu.magisk.core.Info
import com.topjohnwu.magisk.core.data.magiskdb.PolicyDao
import com.topjohnwu.magisk.core.di.AppContext
import com.topjohnwu.magisk.ui.deny.AppProcessInfo
import com.topjohnwu.magisk.core.di.ServiceLocator
import com.topjohnwu.magisk.core.ktx.getLabel
import com.topjohnwu.magisk.core.model.su.SuPolicy
import com.topjohnwu.magisk.core.utils.currentLocale
import com.topjohnwu.magisk.databinding.*
import com.topjohnwu.magisk.databinding.MergeObservableList
import com.topjohnwu.magisk.databinding.RvItem
import com.topjohnwu.magisk.databinding.bindExtra
import com.topjohnwu.magisk.databinding.diffList
import com.topjohnwu.magisk.databinding.set
import com.topjohnwu.magisk.dialog.SuperuserRevokeDialog
import com.topjohnwu.magisk.events.BiometricEvent
import com.topjohnwu.magisk.events.AuthEvent
import com.topjohnwu.magisk.events.SnackbarEvent
import com.google.android.material.materialswitch.MaterialSwitch
import com.topjohnwu.superuser.Shell
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.topjohnwu.magisk.events.DialogBuilder
import com.topjohnwu.magisk.utils.asText
import com.topjohnwu.magisk.view.MagiskDialog
import com.topjohnwu.magisk.view.TextItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SuperuserViewModel(
    private val db: PolicyDao
) : AsyncLoadViewModel() {

    private val itemNoData = TextItem(R.string.superuser_policy_none)

    private val itemsHelpers = ObservableArrayList<TextItem>()
    private val itemsPolicies = diffList<PolicyRvItem>()

    val items = MergeObservableList<RvItem>()
        .insertList(itemsHelpers)
        .insertList(itemsPolicies)
    val extraBindings = bindExtra {
        it.put(BR.listener, this)
    }

    @get:Bindable
    var loading = true
        private set(value) = set(value, field, { field = it }, BR.loading)

    @get:Bindable
    var suListMode = Info.sulist
        private set(value) = set(value, field, { field = it }, BR.suListMode)

    @get:Bindable
    var pendingLabel = ""
        private set(value) = set(value, field, { field = it }, BR.pendingLabel)

    @get:Bindable
    var showSystemApps = Config.showSystemApp
        set(value) = set(value, field, { field = it }, BR.showSystemApps) {
            Config.showSystemApp = it
        }

    @SuppressLint("InlinedApi")
    override suspend fun doLoadWork() {
        if (!Info.showSuperUser) {
            loading = false
            return
        }
        loading = true
        withContext(Dispatchers.IO) {
            db.deleteOutdated()
            db.delete(AppContext.applicationInfo.uid)
            val listed = Shell.cmd("magisk magiskhide ls").exec().out
                .map { it.substringBefore('|') }
                .toSet()
            val active = Info.sulist
            val policies = ArrayList<PolicyRvItem>()
            val pm = AppContext.packageManager
            for (policy in db.fetchAll()) {
                val pkgs =
                    if (policy.uid == Process.SYSTEM_UID) arrayOf("android")
                    else pm.getPackagesForUid(policy.uid)
                if (pkgs == null) {
                    db.delete(policy.uid)
                    continue
                }
                val map = pkgs.mapNotNull { pkg ->
                    try {
                        val info = pm.getPackageInfo(pkg, MATCH_UNINSTALLED_PACKAGES)
                        PolicyRvItem(
                            this@SuperuserViewModel, policy,
                            info.packageName,
                            info.sharedUserId != null,
                            info.applicationInfo.loadIcon(pm),
                            info.applicationInfo.getLabel(pm),
                            info.packageName in listed,
                            active
                        )
                    } catch (e: PackageManager.NameNotFoundException) {
                        null
                    }
                }
                if (map.isEmpty()) {
                    db.delete(policy.uid)
                    continue
                }
                policies.addAll(map)
            }
            policies.sortWith(compareBy(
                { it.appName.lowercase(currentLocale) },
                { it.packageName }
            ))
            itemsPolicies.update(policies)
        }
        if (itemsPolicies.isNotEmpty())
            itemsHelpers.clear()
        else if (itemsHelpers.isEmpty())
            itemsHelpers.add(itemNoData)
        publishMode()
        loading = false
    }

    fun denyListPressed() {
        when {
            suListMode -> confirmSwitch(false)
            modePending() -> SnackbarEvent(R.string.sulist_edit_after_reboot).publish()
            else -> openListConfig()
        }
    }

    fun suListPressed() {
        when {
            !suListMode -> confirmSwitch(true)
            modePending() -> SnackbarEvent(R.string.sulist_edit_after_reboot).publish()
            else -> openListConfig()
        }
    }

    fun undoPending() {
        if (!modePending()) return
        setSuList(Info.sulist)
    }

    private fun confirmSwitch(enableSuList: Boolean) {
        if (enableSuList && !Config.denyList) {
            SnackbarEvent(R.string.settings_sulist_error_magiskhide).publish()
            return
        }
        object : DialogBuilder {
            override fun build(dialog: MagiskDialog) {
                dialog.setIcon(R.drawable.ic_warning_circle)
                dialog.setTitle(R.string.sulist_switch_title)
                dialog.setMessage(
                    if (enableSuList) R.string.sulist_switch_to_sulist
                    else R.string.sulist_switch_to_denylist
                )
                dialog.setButton(MagiskDialog.ButtonType.NEGATIVE) {
                    text = android.R.string.cancel
                }
                dialog.setButton(MagiskDialog.ButtonType.POSITIVE) {
                    text = R.string.confirm
                    filled = true
                    onClick {
                        viewModelScope.launch {
                            val extra = withContext(Dispatchers.IO) {
                                if (enableSuList) carrySql("sulist", onlyAllowed = true)
                                else carrySql("hidelist", onlyAllowed = false)
                            }
                            setSuList(enableSuList, extra)
                        }
                    }
                }
            }
        }.show()
    }

    fun grantPressed() {
        object : DialogBuilder {
            override fun build(dialog: MagiskDialog) {
                dialog.setTitle(R.string.grant)
                val root = LayoutInflater.from(dialog.context)
                    .inflate(R.layout.dialog_grant, null)
                val switch = root.findViewById<MaterialSwitch>(R.id.grant_show_system)
                val list = root.findViewById<RecyclerView>(R.id.grant_list)
                val empty = root.findViewById<TextView>(R.id.grant_empty)
                val rows = ObservableArrayList<MagiskDialog.DialogItem>()
                var apps = emptyList<GrantApp>()
                var ticket = 0
                list.layoutManager = LinearLayoutManager(dialog.context)
                list.setAdapter(rows, bindExtra { extra ->
                    extra.put(BR.listener, MagiskDialog.DialogClickListener { pos ->
                        apps.getOrNull(pos)?.let { grant(it) }
                        dialog.dismiss()
                    })
                })
                fun publish(next: List<GrantApp>) {
                    apps = next
                    rows.clear()
                    rows.addAll(next.mapIndexed { index, app ->
                        MagiskDialog.DialogItem(app.label, index)
                    })
                    val vacant = next.isEmpty()
                    empty.visibility = if (vacant) View.VISIBLE else View.GONE
                    list.visibility = if (vacant) View.GONE else View.VISIBLE
                }
                fun reload() {
                    val mine = ++ticket
                    viewModelScope.launch {
                        val next = withContext(Dispatchers.IO) { grantCandidates() }
                        if (mine == ticket) publish(next)
                    }
                }
                switch.isChecked = showSystemApps
                switch.setOnCheckedChangeListener { _, checked ->
                    if (checked == showSystemApps) return@setOnCheckedChangeListener
                    showSystemApps = checked
                    reload()
                }
                dialog.setView(root)
                dialog.setButton(MagiskDialog.ButtonType.NEGATIVE) {
                    text = android.R.string.cancel
                }
                reload()
            }
        }.show()
    }

    private fun openListConfig() {
        SuperuserFragmentDirections.actionSuperuserFragmentToDenyFragment().navigate()
    }

    private fun modePending() = Config.sulist != Info.sulist

    private fun publishMode() {
        suListMode = Info.sulist
        pendingLabel = when {
            !modePending() -> ""
            Config.sulist -> AppContext.getString(R.string.sulist_pending_sulist)
            else -> AppContext.getString(R.string.sulist_pending_denylist)
        }
    }

    private fun sqlQuote(value: String) = value.replace("'", "''")

    // The running daemon still writes to the old mode's table until reboot,
    // so the carried apps go straight into the target table by SQL.
    private fun carrySql(table: String, onlyAllowed: Boolean): String {
        val pm = AppContext.packageManager
        val sql = StringBuilder()
        val seen = HashSet<String>()
        for (item in itemsPolicies) {
            if (onlyAllowed && item.item.policy != SuPolicy.ALLOW) continue
            if (!seen.add(item.packageName)) continue
            val names = try {
                val info = pm.getApplicationInfo(item.packageName, 0)
                AppProcessInfo(info, pm, emptyList()).processes.map { it.name }
            } catch (e: Exception) {
                emptyList()
            }
            for (proc in (names + item.packageName).distinct()) {
                sql.append("INSERT OR IGNORE INTO ").append(table)
                sql.append(" (package_name,process) VALUES('")
                sql.append(sqlQuote(item.packageName))
                sql.append("','")
                sql.append(sqlQuote(proc))
                sql.append("');")
            }
        }
        return sql.toString()
    }

    private fun setSuList(enabled: Boolean, extraSql: String = "") {
        if (enabled && !Config.denyList) {
            SnackbarEvent(R.string.settings_sulist_error_magiskhide).publish()
            return
        }
        val cmd = if (enabled) "1" else "0"
        Shell.cmd(
            "magisk --sqlite \"REPLACE INTO settings (key,value) VALUES('sulist',$cmd);$extraSql\""
        ).submit { result ->
            if (!result.isSuccess) return@submit
            Config.sulist = enabled
            viewModelScope.launch { publishMode() }
        }
    }

    private fun grantCandidates(): List<GrantApp> {
        val pm = AppContext.packageManager
        val showSystem = showSystemApps
        val taken = itemsPolicies.map { it.item.uid }.toSet()
        return pm.getInstalledApplications(0)
            .asSequence()
            .filter { it.uid != Process.SYSTEM_UID && it.uid != Process.myUid() }
            .filter { it.uid !in taken }
            .filter { app ->
                val system = app.flags and ApplicationInfo.FLAG_SYSTEM != 0
                if (system) showSystem else pm.getLaunchIntentForPackage(app.packageName) != null
            }
            .groupBy { it.uid }
            .map { (uid, apps) ->
                GrantApp(
                    uid,
                    apps.first().loadLabel(pm).toString(),
                    apps.map { it.packageName }.distinct()
                )
            }
            .sortedBy { it.label.lowercase(currentLocale) }
    }

    private fun grant(app: GrantApp) {
        viewModelScope.launch {
            val policy = SuPolicy(app.uid).apply {
                this.policy = SuPolicy.ALLOW
                until = 0
            }
            db.update(policy)
            val cmds = withContext(Dispatchers.IO) { grantListCmds(app.packages) }
            if (cmds.isEmpty()) {
                startLoading()
                return@launch
            }
            Shell.cmd(*cmds.toTypedArray()).submit {
                viewModelScope.launch { startLoading() }
            }
        }
    }

    // magiskhide add follows the running daemon: DenyList writes hidelist,
    // SuList writes sulist. A SuList switch that is still waiting for reboot
    // is also recorded in the sulist table.
    private fun grantListCmds(packages: List<String>): List<String> {
        val pm = AppContext.packageManager
        val pendingSu = !Info.sulist && Config.sulist
        val cmds = ArrayList<String>()
        for (pkg in packages) {
            val names = try {
                val info = pm.getApplicationInfo(pkg, 0)
                AppProcessInfo(info, pm, emptyList()).processes.map { it.name }
            } catch (e: Exception) {
                emptyList()
            }
            for (proc in (names + pkg).distinct()) {
                val qPkg = pkg.replace("'", "'\\''")
                val qProc = proc.replace("'", "'\\''")
                cmds += "magisk magiskhide add '$qPkg' '$qProc'"
                if (pendingSu)
                    cmds += "magisk --sqlite \"INSERT OR IGNORE INTO sulist (package_name,process) VALUES('$qPkg','$qProc');\""
            }
        }
        return cmds
    }

    private data class GrantApp(val uid: Int, val label: String, val packages: List<String>)

    // ---

    fun deletePressed(item: PolicyRvItem) {
        fun updateState() = viewModelScope.launch {
            db.delete(item.item.uid)
            val list = ArrayList(itemsPolicies)
            list.removeAll { it.item.uid == item.item.uid }
            itemsPolicies.update(list)
            if (list.isEmpty() && itemsHelpers.isEmpty()) {
                itemsHelpers.add(itemNoData)
            }
        }

        if (Config.userAuth) {
            AuthEvent { updateState() }.publish()
        } else if (ServiceLocator.biometrics.isEnabled) {
            BiometricEvent {
                onSuccess { updateState() }
            }.publish()
        } else {
            SuperuserRevokeDialog(item.title) { updateState() }.show()
        }
    }

    fun updateNotify(item: PolicyRvItem) {
        viewModelScope.launch {
            db.update(item.item)
            val res = when {
                item.item.notification -> R.string.su_snack_notif_on
                else -> R.string.su_snack_notif_off
            }
            itemsPolicies.forEach {
                if (it.item.uid == item.item.uid) {
                    it.notifyPropertyChanged(BR.shouldNotify)
                }
            }
            SnackbarEvent(res.asText(item.appName)).publish()
        }
    }

    fun updateLogging(item: PolicyRvItem) {
        viewModelScope.launch {
            db.update(item.item)
            val res = when {
                item.item.logging -> R.string.su_snack_log_on
                else -> R.string.su_snack_log_off
            }
            itemsPolicies.forEach {
                if (it.item.uid == item.item.uid) {
                    it.notifyPropertyChanged(BR.shouldLog)
                }
            }
            SnackbarEvent(res.asText(item.appName)).publish()
        }
    }

    fun togglePolicy(item: PolicyRvItem, enable: Boolean) {
        val items = itemsPolicies.filter { it.item.uid == item.item.uid }
        fun updateState() {
            viewModelScope.launch {
                val res = if (enable) R.string.su_snack_grant else R.string.su_snack_deny
                item.item.policy = if (enable) SuPolicy.ALLOW else SuPolicy.DENY
                db.update(item.item)
                items.forEach {
                    it.notifyPropertyChanged(BR.enabled)
                }
                SnackbarEvent(res.asText(item.appName)).publish()
            }
        }

        if (Config.userAuth) {
            AuthEvent { updateState() }.publish()
        } else if (ServiceLocator.biometrics.isEnabled) {
            BiometricEvent {
                onSuccess { updateState() }
            }.publish()
        } else {
            updateState()
        }
    }
}
