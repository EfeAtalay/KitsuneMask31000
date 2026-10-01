package com.topjohnwu.magisk.ui.superuser

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.PackageManager.MATCH_UNINSTALLED_PACKAGES
import android.os.Process
import androidx.databinding.Bindable
import androidx.databinding.ObservableArrayList
import androidx.lifecycle.viewModelScope
import com.topjohnwu.magisk.BR
import com.topjohnwu.magisk.R
import com.topjohnwu.magisk.arch.AsyncLoadViewModel
import com.topjohnwu.magisk.core.Config
import com.topjohnwu.magisk.core.Info
import com.topjohnwu.magisk.core.data.magiskdb.PolicyDao
import com.topjohnwu.magisk.arch.ContextExecutor
import com.topjohnwu.magisk.arch.ViewEvent
import com.topjohnwu.magisk.core.di.AppContext
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
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.topjohnwu.superuser.Shell
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
    var suListMode = Config.sulist
        private set(value) = set(value, field, { field = it }, BR.suListMode)

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
                            info.applicationInfo.getLabel(pm)
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
        suListMode = Config.sulist
        loading = false
    }

    fun denyListPressed() {
        if (suListMode) confirmSwitch(false) else openListConfig()
    }

    fun suListPressed() {
        if (!suListMode) confirmSwitch(true) else openListConfig()
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
                    onClick { setSuList(enableSuList) }
                }
            }
        }.show()
    }

    fun grantPressed() {
        object : ViewEvent(), ContextExecutor {
            override fun invoke(context: Context) {
                viewModelScope.launch {
                    val apps = withContext(Dispatchers.IO) { grantCandidates() }
                    if (apps.isEmpty()) {
                        SnackbarEvent(R.string.superuser_policy_none).publish()
                        return@launch
                    }
                    MaterialAlertDialogBuilder(context)
                        .setTitle(R.string.grant)
                        .setItems(apps.map { it.label }.toTypedArray()) { _, index ->
                            grant(apps[index].uid)
                        }
                        .show()
                }
            }
        }.publish()
    }

    private fun openListConfig() {
        SuperuserFragmentDirections.actionSuperuserFragmentToDenyFragment().navigate()
    }

    private fun setSuList(enabled: Boolean) {
        if (enabled && !Config.denyList) {
            SnackbarEvent(R.string.settings_sulist_error_magiskhide).publish()
            return
        }
        val cmd = if (enabled) "1" else "0"
        Shell.cmd(
            "magisk --sqlite \"REPLACE INTO settings (key,value) VALUES('sulist',$cmd);\""
        ).submit { result ->
            if (!result.isSuccess) return@submit
            Config.sulist = enabled
            viewModelScope.launch {
                suListMode = enabled
            }
        }
    }

    private fun grantCandidates(): List<GrantApp> {
        val pm = AppContext.packageManager
        val taken = itemsPolicies.map { it.item.uid }.toSet()
        return pm.getInstalledApplications(0)
            .asSequence()
            .filter { it.uid != Process.SYSTEM_UID && it.uid != Process.myUid() }
            .filter { it.uid !in taken }
            .filter { pm.getLaunchIntentForPackage(it.packageName) != null }
            .map { GrantApp(it.uid, it.loadLabel(pm).toString()) }
            .distinctBy { it.uid }
            .sortedBy { it.label.lowercase(currentLocale) }
            .toList()
    }

    private fun grant(uid: Int) {
        viewModelScope.launch {
            val policy = SuPolicy(uid).apply {
                this.policy = SuPolicy.ALLOW
                until = 0
            }
            db.update(policy)
            startLoading()
        }
    }

    private data class GrantApp(val uid: Int, val label: String)

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
