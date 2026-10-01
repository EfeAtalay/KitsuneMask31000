package com.topjohnwu.magisk.ui.module

import androidx.core.net.toUri
import androidx.databinding.Bindable
import androidx.lifecycle.viewModelScope
import com.topjohnwu.magisk.BR
import com.topjohnwu.magisk.MainDirections
import com.topjohnwu.magisk.R
import com.topjohnwu.magisk.arch.AsyncLoadViewModel
import com.topjohnwu.magisk.core.Config
import com.topjohnwu.magisk.core.Const
import com.topjohnwu.magisk.core.Info
import com.topjohnwu.magisk.core.di.AppContext
import com.topjohnwu.magisk.core.di.ServiceLocator
import com.topjohnwu.magisk.core.model.module.LocalModule
import com.topjohnwu.magisk.core.utils.RootUtils
import com.topjohnwu.magisk.databinding.bindExtra
import com.topjohnwu.magisk.databinding.diffList
import com.topjohnwu.magisk.databinding.set
import com.topjohnwu.magisk.events.DialogBuilder
import com.topjohnwu.magisk.events.SnackbarEvent
import com.topjohnwu.magisk.ui.theme.Theme
import com.topjohnwu.magisk.view.MagiskDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.util.Locale

class ModuleRepoViewModel : AsyncLoadViewModel() {

    private val all = mutableListOf<RepoModuleRvItem>()
    private var started = false
    private var publishGeneration = 0
    private var publishJob: Job? = null

    val items = diffList<RepoModuleRvItem>()
    val extraBindings = bindExtra { it.put(BR.viewModel, this) }

    val amoledFrame: Boolean
        get() = Config.amoled || Theme.selected == Theme.PiplupAmoled

    var query: String = ""
        set(value) {
            if (field == value) return
            field = value
            schedulePublish()
        }

    @get:Bindable
    var loading = true
        private set(value) = set(value, field, { field = it }, BR.loading)

    @get:Bindable
    var detailing = false
        private set(value) = set(value, field, { field = it }, BR.detailing)

    override suspend fun doLoadWork() {
        if (started) return
        started = true
        loading = true
        try {
            val cache = withContext(Dispatchers.IO) { readCache() }
            val installed = withContext(Dispatchers.IO) { installedIds() }
            if (Info.isConnected.value != true) {
                showCached(cache, installed)
                if (all.isEmpty()) SnackbarEvent(R.string.no_connection).publish()
                started = all.isNotEmpty()
                return
            }
            val index = withContext(Dispatchers.IO) { fetchIndex() }
            all.clear()
            all += index.map { row ->
                val cached = cache[row.id]
                val module = RepoModule(
                    id = row.id,
                    lastUpdate = row.lastUpdate,
                    propUrl = row.propUrl,
                    zipUrl = row.zipUrl,
                    notesUrl = row.notesUrl,
                    stars = row.stars,
                    name = cached?.name?.takeIf { it.isNotBlank() } ?: row.id,
                    author = cached?.author.orEmpty(),
                    version = cached?.version.orEmpty(),
                    versionCode = cached?.versionCode ?: -1,
                    description = cached?.description.orEmpty(),
                )
                RepoModuleRvItem(module, row.id in installed)
            }
            publishNow()
            loading = false
            detailing = true
            enrich(index, cache)
            withContext(Dispatchers.IO) { writeCache() }
        } catch (e: Exception) {
            Timber.w(e)
            if (all.isEmpty()) SnackbarEvent(R.string.module_repo_failed).publish()
            started = all.isNotEmpty()
        } finally {
            detailing = false
            loading = false
        }
    }

    fun setOrder(order: Int) {
        Config.repoOrder = order
        schedulePublish()
    }

    fun installPressed(item: RepoModuleRvItem) {
        if (item.installing || all.any { it.installing }) return
        if (!item.module.zipUrl.startsWith("https://")) {
            SnackbarEvent(R.string.module_repo_bad_zip).publish()
            return
        }
        if (Info.isConnected.value != true) {
            SnackbarEvent(R.string.no_connection).publish()
            return
        }
        object : DialogBuilder {
            override fun build(dialog: MagiskDialog) {
                dialog.setTitle(R.string.confirm_install_title)
                dialog.setMessage(
                    dialog.context.getString(R.string.confirm_install, item.title)
                )
                dialog.setButton(MagiskDialog.ButtonType.POSITIVE) {
                    text = R.string.install
                    onClick { download(item) }
                }
                dialog.setButton(MagiskDialog.ButtonType.NEGATIVE) {
                    text = android.R.string.cancel
                }
            }
        }.show()
    }

    private fun download(item: RepoModuleRvItem) {
        item.installing = true
        viewModelScope.launch {
            val ready = try {
                withContext(Dispatchers.IO) { downloadNormalized(item.module) }
            } catch (e: Exception) {
                Timber.w(e)
                null
            }
            item.installing = false
            if (ready == null) {
                SnackbarEvent(R.string.module_repo_bad_zip).publish()
            } else {
                MainDirections.actionFlashFragment(Const.Value.FLASH_ZIP, ready.toUri()).navigate()
            }
        }
    }

    private fun showCached(cache: Map<String, RepoModule>, installed: Set<String>) {
        if (cache.isEmpty()) return
        all.clear()
        all += cache.values.map { cached ->
            RepoModuleRvItem(cached, cached.id in installed)
        }
        publishNow()
    }

    private suspend fun enrich(index: List<IndexRow>, cache: Map<String, RepoModule>) {
        val pending = index.filter { row ->
            val cached = cache[row.id]
            row.propUrl.startsWith("https://") &&
                (cached == null || cached.lastUpdate != row.lastUpdate || cached.name == row.id)
        }
        if (pending.isEmpty()) return
        val gate = Semaphore(6)
        coroutineScope {
            pending.map { row ->
                async(Dispatchers.IO) {
                    gate.withPermit {
                        val text = runCatching {
                            ServiceLocator.networkService.fetchString(row.propUrl)
                        }.getOrNull() ?: return@withPermit
                        val props = parseProp(text)
                        withContext(Dispatchers.Main) {
                            all.find { it.module.id == row.id }?.applyProp(props)
                            schedulePublish()
                        }
                    }
                }
            }.awaitAll()
        }
        schedulePublish()
    }

    private fun schedulePublish() {
        publishJob?.cancel()
        val ticket = ++publishGeneration
        val snapshot = all.toList()
        val q = query
        val order = Config.repoOrder
        publishJob = viewModelScope.launch {
            delay(200)
            val next = withContext(Dispatchers.Default) { arrange(snapshot, q, order) }
            if (ticket == publishGeneration) items.update(next)
        }
    }

    private fun publishNow() {
        publishJob?.cancel()
        val ticket = ++publishGeneration
        val snapshot = all.toList()
        val q = query
        val order = Config.repoOrder
        viewModelScope.launch {
            val next = withContext(Dispatchers.Default) { arrange(snapshot, q, order) }
            if (ticket == publishGeneration) items.update(next)
        }
    }

    private fun arrange(
        source: List<RepoModuleRvItem>,
        query: String,
        order: Int,
    ): List<RepoModuleRvItem> {
        val needle = query.trim().lowercase(Locale.ROOT)
        val filtered = if (needle.isEmpty()) source else source.filter { item ->
            val module = item.module
            module.id.lowercase(Locale.ROOT).contains(needle) ||
                module.name.lowercase(Locale.ROOT).contains(needle) ||
                module.author.lowercase(Locale.ROOT).contains(needle) ||
                module.description.lowercase(Locale.ROOT).contains(needle)
        }
        return when (order) {
            Config.Value.ORDER_NAME ->
                filtered.sortedBy { it.module.name.lowercase(Locale.ROOT) }
            Config.Value.ORDER_STARS ->
                filtered.sortedByDescending { it.module.stars }
            else -> filtered.sortedByDescending { it.module.lastUpdate }
        }
    }

    private fun installedIds(): Set<String> {
        if (!Info.env.isActive || !LocalModule.loaded()) return emptySet()
        return RootUtils.fs.getFile(Const.MAGISK_PATH)
            .listFiles()
            .orEmpty()
            .filter { !it.isFile && !it.isHidden }
            .map { it.name }
            .toSet()
    }

    private suspend fun fetchIndex(): List<IndexRow> {
        val text = ServiceLocator.networkService.fetchString(INDEX_URL)
        val arr = JSONObject(text).optJSONArray("modules") ?: throw IOException("empty index")
        val rows = ArrayList<IndexRow>(arr.length())
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val id = obj.optString("id")
            val zip = obj.optString("zip_url")
            if (id.isBlank() || !zip.startsWith("https://")) continue
            rows += IndexRow(
                id = id,
                lastUpdate = obj.optLong("last_update"),
                propUrl = obj.optString("prop_url"),
                zipUrl = zip,
                notesUrl = obj.optString("notes_url"),
                stars = obj.optInt("stars"),
            )
        }
        if (rows.isEmpty()) throw IOException("empty index")
        return rows
    }

    private fun readCache(): Map<String, RepoModule> {
        val file = cacheFile()
        if (!file.isFile) return emptyMap()
        return runCatching {
            val arr = JSONObject(file.readText()).optJSONArray("modules") ?: return emptyMap()
            buildMap {
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val id = obj.optString("id")
                    if (id.isBlank()) continue
                    put(id, RepoModule(
                        id = id,
                        lastUpdate = obj.optLong("lastUpdate"),
                        name = obj.optString("name", id),
                        author = obj.optString("author"),
                        version = obj.optString("version"),
                        versionCode = obj.optInt("versionCode", -1),
                        description = obj.optString("description"),
                        stars = obj.optInt("stars"),
                    ))
                }
            }
        }.getOrDefault(emptyMap())
    }

    private fun writeCache() {
        val arr = JSONArray()
        for (item in all) {
            val module = item.module
            arr.put(JSONObject()
                .put("id", module.id)
                .put("lastUpdate", module.lastUpdate)
                .put("name", module.name)
                .put("author", module.author)
                .put("version", module.version)
                .put("versionCode", module.versionCode)
                .put("description", module.description)
                .put("stars", module.stars))
        }
        cacheFile().writeText(JSONObject().put("modules", arr).toString())
    }

    private fun cacheFile() = File(AppContext.filesDir, "module_repo_cache.json")

    private suspend fun downloadNormalized(module: RepoModule): File {
        val raw = File(AppContext.cacheDir, "repo-download.zip")
        val dest = File(AppContext.cacheDir, safeZipName(module.id))
        val body = ServiceLocator.networkService.fetchFile(module.zipUrl)
        try {
            body.byteStream().use { input ->
                raw.outputStream().use { output ->
                    val buf = ByteArray(8192)
                    var total = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > MAX_DOWNLOAD) throw IOException("zip too large")
                        output.write(buf, 0, n)
                    }
                }
            }
        } finally {
            body.close()
        }
        normalizeModuleZip(raw, dest)
        raw.delete()
        return dest
    }

    private fun safeZipName(id: String): String {
        val clean = id.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80)
        return "repo-$clean.zip"
    }

    private fun parseProp(text: String): Map<String, String> {
        val props = LinkedHashMap<String, String>()
        for (line in text.lineSequence()) {
            val eq = line.indexOf('=')
            if (eq <= 0) continue
            val key = line.substring(0, eq).trim()
            if (key.isEmpty() || key[0] == '#') continue
            props[key] = line.substring(eq + 1).trim()
        }
        return props
    }

    private data class IndexRow(
        val id: String,
        val lastUpdate: Long,
        val propUrl: String,
        val zipUrl: String,
        val notesUrl: String,
        val stars: Int,
    )

    companion object {
        private const val INDEX_URL =
            "https://raw.githubusercontent.com/Magisk-Modules-Alt-Repo/json/main/modules.json"
        private const val MAX_DOWNLOAD = 80L * 1024L * 1024L
    }
}
