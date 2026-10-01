package com.topjohnwu.magisk.ui.module

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.databinding.Bindable
import com.topjohnwu.magisk.BR
import com.topjohnwu.magisk.R
import com.topjohnwu.magisk.core.Info
import com.topjohnwu.magisk.core.model.module.LocalModule
import com.topjohnwu.magisk.databinding.DiffItem
import com.topjohnwu.magisk.databinding.ItemWrapper
import com.topjohnwu.magisk.databinding.ObservableRvItem
import com.topjohnwu.magisk.databinding.RvItem
import com.topjohnwu.magisk.databinding.set
import com.topjohnwu.magisk.utils.TextHolder
import com.topjohnwu.magisk.utils.asText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object InstallModule : RvItem(), DiffItem<InstallModule> {
    override val layoutRes = R.layout.item_module_download
}

class LocalModuleRvItem(
    override val item: LocalModule
) : ObservableRvItem(), DiffItem<LocalModuleRvItem>, ItemWrapper<LocalModule> {

    override val layoutRes = R.layout.item_module_md2

    val showNotice: Boolean
    val showAction: Boolean
    val noticeText: TextHolder

    init {
        val isZygisk = item.isZygisk
        val isRiru = item.isRiru
        val zygiskUnloaded = isZygisk && item.zygiskUnloaded

        showNotice = zygiskUnloaded ||
            (Info.isZygiskEnabled && isRiru) ||
            (!Info.isZygiskEnabled && isZygisk)
        showAction = item.hasAction && !showNotice
        noticeText =
            when {
                zygiskUnloaded -> R.string.zygisk_module_unloaded.asText()
                isRiru -> R.string.suspend_text_riru.asText(R.string.zygisk.asText())
                else -> R.string.suspend_text_zygisk.asText(R.string.zygisk.asText())
            }
    }

    @get:Bindable
    var isEnabled = item.enable
        set(value) = set(value, field, { field = it }, BR.enabled, BR.updateReady) {
            item.enable = value
        }

    @get:Bindable
    var isRemoved = item.remove
        set(value) = set(value, field, { field = it }, BR.removed, BR.updateReady) {
            item.remove = value
        }

    @get:Bindable
    val showUpdate get() = item.updateInfo != null

    @get:Bindable
    val updateReady get() = item.outdated && !isRemoved && isEnabled

    val isUpdated = item.updated

    fun fetchedUpdateInfo() {
        notifyPropertyChanged(BR.showUpdate)
        notifyPropertyChanged(BR.updateReady)
    }

    val hasWebUi get() = item.hasWebUi

    @get:Bindable
    var banner: Bitmap? = null
        private set(value) = set(value, field, { field = it }, BR.banner, BR.hasBanner)

    @get:Bindable
    val hasBanner get() = banner != null

    suspend fun loadBanner() {
        if (banner != null) return
        val decoded = withContext(Dispatchers.IO) {
            runCatching { decodeBanner(item.readBanner()) }.getOrNull()
        } ?: return
        banner = decoded
    }

    fun openWebUi(view: android.view.View) {
        view.context.startActivity(
            android.content.Intent(view.context, WebUIActivity::class.java)
                .putExtra(WebUIActivity.EXTRA_PATH, item.webRootPath)
                .putExtra(WebUIActivity.EXTRA_NAME, item.name)
        )
    }

    fun delete() {
        isRemoved = !isRemoved
    }

    override fun itemSameAs(other: LocalModuleRvItem): Boolean = item.id == other.item.id

    private fun decodeBanner(bytes: ByteArray?): Bitmap? {
        if (bytes == null || bytes.isEmpty()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val opts = BitmapFactory.Options().apply {
            inSampleSize = bannerSampleSize(bounds.outWidth, bounds.outHeight)
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }

    private fun bannerSampleSize(width: Int, height: Int): Int {
        var sample = 1
        while (width / sample > 1600 || height / sample > 800) sample *= 2
        return sample
    }
}
