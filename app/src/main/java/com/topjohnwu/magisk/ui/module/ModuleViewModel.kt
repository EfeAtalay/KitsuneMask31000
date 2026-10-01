package com.topjohnwu.magisk.ui.module

import android.net.Uri
import androidx.databinding.Bindable
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.topjohnwu.magisk.BR
import com.topjohnwu.magisk.BuildConfig
import com.topjohnwu.magisk.R
import com.topjohnwu.magisk.MainDirections
import com.topjohnwu.magisk.arch.AsyncLoadViewModel
import com.topjohnwu.magisk.core.Config
import com.topjohnwu.magisk.core.Const
import com.topjohnwu.magisk.core.Info
import com.topjohnwu.magisk.core.base.ContentResultCallback
import com.topjohnwu.magisk.core.model.module.LocalModule
import com.topjohnwu.magisk.core.model.module.OnlineModule
import com.topjohnwu.magisk.databinding.MergeObservableList
import com.topjohnwu.magisk.databinding.RvItem
import com.topjohnwu.magisk.databinding.bindExtra
import com.topjohnwu.magisk.databinding.diffList
import com.topjohnwu.magisk.databinding.set
import com.topjohnwu.magisk.dialog.LocalModuleInstallDialog
import com.topjohnwu.magisk.dialog.OnlineModuleInstallDialog
import com.topjohnwu.magisk.events.GetContentEvent
import com.topjohnwu.magisk.events.SnackbarEvent
import com.topjohnwu.magisk.ui.theme.Theme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.parcelize.Parcelize
import java.util.Locale

class ModuleViewModel : AsyncLoadViewModel() {

    val bottomBarBarrierIds = intArrayOf(R.id.module_webui, R.id.module_update, R.id.module_remove)

    private val itemsInstalled = diffList<LocalModuleRvItem>()

    val items = MergeObservableList<RvItem>()
    val extraBindings = bindExtra {
        it.put(BR.viewModel, this)
    }

    val data get() = uri

    val amoledFrame: Boolean
        get() = Config.amoled || Theme.selected == Theme.PiplupAmoled

    @get:Bindable
    var loading = true
        private set(value) = set(value, field, { field = it }, BR.loading)

    override suspend fun doLoadWork() {
        loading = true
        val moduleLoaded = Info.env.isActive &&
                withContext(Dispatchers.IO) { LocalModule.loaded() }
        if (moduleLoaded) {
            loadInstalled()
            if (items.isEmpty()) {
                if (!BuildConfig.STARDUST_UI)
                    items.insertItem(InstallModule)
                items.insertList(itemsInstalled)
            }
        }
        loading = false
        loadUpdateInfo()
    }

    override fun onNetworkChanged(network: Boolean) = startLoading()

    private suspend fun loadInstalled() {
        val installed = withContext(Dispatchers.IO) {
            LocalModule.installed().map { LocalModuleRvItem(it) }
        }
        val sorted = withContext(Dispatchers.Default) { installed.sortedForConfig() }
        itemsInstalled.update(sorted)
        loadBanners(sorted)
    }

    private fun loadBanners(installed: List<LocalModuleRvItem>) {
        viewModelScope.launch {
            installed.forEach { it.loadBanner() }
        }
    }

    fun setSort(order: Int) {
        Config.moduleSort = order
        viewModelScope.launch { resort() }
    }

    fun openRepo() {
        MainDirections.actionModuleRepoFragment().navigate()
    }

    private suspend fun loadUpdateInfo() {
        withContext(Dispatchers.IO) {
            itemsInstalled.forEach {
                if (it.item.fetch())
                    it.fetchedUpdateInfo()
            }
        }
        if (Config.moduleSort == Config.Value.MODULE_UPDATE)
            resort()
    }

    private suspend fun resort() {
        val sorted = withContext(Dispatchers.Default) {
            itemsInstalled.toList().sortedForConfig()
        }
        itemsInstalled.update(sorted)
    }

    private fun List<LocalModuleRvItem>.sortedForConfig(): List<LocalModuleRvItem> {
        val byName: (LocalModuleRvItem) -> String = { it.item.name.lowercase(Locale.ROOT) }
        return when (Config.moduleSort) {
            Config.Value.MODULE_NAME_DESC -> sortedByDescending(byName)
            Config.Value.MODULE_AUTHOR -> sortedWith(
                compareBy({ it.item.author.lowercase(Locale.ROOT) }, byName)
            )
            Config.Value.MODULE_UPDATE -> sortedWith(
                compareByDescending<LocalModuleRvItem> { it.updateReady }.thenBy(byName)
            )
            Config.Value.MODULE_ENABLED -> sortedWith(
                compareByDescending<LocalModuleRvItem> { it.isEnabled }.thenBy(byName)
            )
            else -> sortedBy(byName)
        }
    }

    fun downloadPressed(item: OnlineModule?) =
        if (item != null && Info.isConnected.value == true) {
            withExternalRW { OnlineModuleInstallDialog(item).show() }
        } else {
            SnackbarEvent(R.string.no_connection).publish()
        }

    fun installPressed() = withExternalRW {
        GetContentEvent("application/zip", UriCallback()).publish()
    }

    fun requestInstallLocalModule(uri: Uri, displayName: String) {
        LocalModuleInstallDialog(this, uri, displayName).show()
    }

    @Parcelize
    class UriCallback : ContentResultCallback {
        override fun onActivityResult(result: Uri) {
            uri.value = result
        }
    }

    fun runAction(id: String, name: String) {
        MainDirections.actionActionFragment(id, name).navigate()
    }

    companion object {
        private val uri = MutableLiveData<Uri?>()
    }
}
