package com.topjohnwu.magisk.ui.superuser

import android.graphics.drawable.Drawable
import androidx.databinding.Bindable
import com.topjohnwu.magisk.BR
import com.topjohnwu.magisk.R
import com.topjohnwu.magisk.core.di.AppContext
import com.topjohnwu.magisk.core.model.su.SuPolicy
import com.topjohnwu.magisk.databinding.DiffItem
import com.topjohnwu.magisk.databinding.ItemWrapper
import com.topjohnwu.magisk.databinding.ObservableRvItem
import com.topjohnwu.magisk.databinding.set

class PolicyRvItem(
    private val viewModel: SuperuserViewModel,
    override val item: SuPolicy,
    val packageName: String,
    private val isSharedUid: Boolean,
    val icon: Drawable,
    val appName: String,
    val onHideList: Boolean,
    val activeSuList: Boolean
) : ObservableRvItem(), DiffItem<PolicyRvItem>, ItemWrapper<SuPolicy> {

    override val layoutRes = R.layout.item_policy_md2

    val title get() = if (isSharedUid) "[SharedUID] $appName" else appName

    val showListNote get() = activeSuList || onHideList

    val hideTarget get() = if (activeSuList) !onHideList else onHideList

    val listWarn get() = activeSuList && !onHideList

    val listTint get() = if (listWarn) 0xFFE53935.toInt() else 0xFF9AA0A6.toInt()

    val listNote: String
        get() {
            val res = when {
                activeSuList && onHideList -> R.string.policy_on_sulist
                activeSuList -> R.string.policy_off_sulist
                onHideList -> R.string.policy_on_denylist
                else -> return ""
            }
            return AppContext.getString(res)
        }

    private inline fun <reified T> setImpl(new: T, old: T, setter: (T) -> Unit) {
        if (old != new) {
            setter(new)
        }
    }

    @get:Bindable
    var isExpanded = false
        set(value) = set(value, field, { field = it }, BR.expanded)

    @get:Bindable
    var isEnabled
        get() = item.policy == SuPolicy.ALLOW
        set(value) = setImpl(value, isEnabled) {
            notifyPropertyChanged(BR.enabled)
            viewModel.togglePolicy(this, value)
        }

    @get:Bindable
    var shouldNotify
        get() = item.notification
        private set(value) = setImpl(value, shouldNotify) {
            item.notification = it
            viewModel.updateNotify(this)
        }

    @get:Bindable
    var shouldLog
        get() = item.logging
        private set(value) = setImpl(value, shouldLog) {
            item.logging = it
            viewModel.updateLogging(this)
        }

    fun toggleExpand() {
        isExpanded = !isExpanded
    }

    fun toggleNotify() {
        shouldNotify = !shouldNotify
    }

    fun toggleLog() {
        shouldLog = !shouldLog
    }

    fun revoke() {
        viewModel.deletePressed(this)
    }

    fun testHide() {
        viewModel.probeHide(this)
    }

    fun hideNow() {
        viewModel.hideNowPressed(this)
    }

    @get:Bindable
    var hideResult = ""
        set(value) = set(value, field, { field = it }, BR.hideResult)

    override fun itemSameAs(other: PolicyRvItem) = packageName == other.packageName

    override fun contentSameAs(other: PolicyRvItem) =
        item.policy == other.item.policy &&
            onHideList == other.onHideList &&
            activeSuList == other.activeSuList

}
