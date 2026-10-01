package com.topjohnwu.magisk.ui.module

import androidx.databinding.Bindable
import com.topjohnwu.magisk.BR
import com.topjohnwu.magisk.R
import com.topjohnwu.magisk.databinding.DiffItem
import com.topjohnwu.magisk.databinding.ObservableRvItem
import com.topjohnwu.magisk.databinding.set

class RepoModule(
    val id: String,
    var lastUpdate: Long = 0,
    var propUrl: String = "",
    var zipUrl: String = "",
    var notesUrl: String = "",
    var stars: Int = 0,
    var name: String = id,
    var author: String = "",
    var version: String = "",
    var versionCode: Int = -1,
    var description: String = "",
    var source: String = "",
)

class RepoModuleRvItem(
    val module: RepoModule,
    installed: Boolean,
) : ObservableRvItem(), DiffItem<RepoModuleRvItem> {

    override val layoutRes = R.layout.item_repo_module

    @get:Bindable
    var installed = installed
        set(value) = set(value, field, { field = it }, BR.installed, BR.installing)

    @get:Bindable
    var installing = false
        set(value) = set(value, field, { field = it }, BR.installing, BR.installed)

    @get:Bindable
    val title get() = module.name.ifBlank { module.id }

    @get:Bindable
    val subtitle: String
        get() {
            val parts = listOf(module.version, module.author, module.source)
                .filter { it.isNotBlank() }
            return parts.joinToString(" · ").ifBlank { module.id }
        }

    @get:Bindable
    val description get() = module.description

    @get:Bindable
    val hasDescription get() = module.description.isNotBlank()

    @get:Bindable
    val starsLabel get() = "★ ${module.stars}"

    fun applyProp(props: Map<String, String>) {
        module.name = props["name"]?.trim()?.take(120)?.ifBlank { null } ?: module.name
        module.author = props["author"].orEmpty().trim().take(120)
        module.version = props["version"].orEmpty().trim().take(48)
        module.versionCode = props["versionCode"]?.trim()?.toIntOrNull() ?: module.versionCode
        module.description = props["description"].orEmpty()
            .replace('\n', ' ')
            .trim()
            .take(400)
        notifyPropertyChanged(BR.title)
        notifyPropertyChanged(BR.subtitle)
        notifyPropertyChanged(BR.description)
        notifyPropertyChanged(BR.hasDescription)
    }

    fun applyListing(lastUpdate: Long, propUrl: String, zipUrl: String, notesUrl: String, stars: Int) {
        module.lastUpdate = lastUpdate
        module.propUrl = propUrl
        module.zipUrl = zipUrl
        module.notesUrl = notesUrl
        module.stars = stars
        notifyPropertyChanged(BR.starsLabel)
    }

    override fun itemSameAs(other: RepoModuleRvItem) = module.id == other.module.id
}
