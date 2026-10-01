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
                    name = row.name.ifBlank { cached?.name?.takeIf { it.isNotBlank() } ?: row.id },
                    author = row.author.ifBlank { cached?.author.orEmpty() },
                    version = row.version.ifBlank { cached?.version.orEmpty() },
                    versionCode = if (row.versionCode >= 0) row.versionCode else cached?.versionCode ?: -1,
                    description = row.description.ifBlank { cached?.description.orEmpty() },
                    source = row.source,
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
                            val item = all.find { it.module.id == row.id } ?: return@withContext
                            val described = props["name"].orEmpty() + " " + props["description"].orEmpty()
                            if (!textAllowsMagisk(item.module.id, described)) {
                                all.remove(item)
                            } else {
                                item.applyProp(props)
                            }
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

    private suspend fun fetchIndex(): List<IndexRow> = coroutineScope {
        val jobs = REPOS.map { repo ->
            async(Dispatchers.IO) {
                runCatching { fetchRepo(repo) }
                    .onFailure { Timber.w(it, "repo %s", repo.name) }
                    .getOrDefault(emptyList())
            }
        }
        val featured = async(Dispatchers.IO) { fetchFeatured() }
        val merged = mergeRows(jobs.awaitAll().flatten() + featured.await())
        if (merged.isEmpty()) throw IOException("empty index")
        merged
    }

    private suspend fun fetchRepo(repo: RepoSource): List<IndexRow> {
        val text = ServiceLocator.networkService.fetchString(repo.url)
        return when (repo.kind) {
            RepoKind.ALT -> parseAlt(text, repo.name)
            RepoKind.MMRL -> parseMmrl(text, repo.name)
        }
    }

    private fun parseAlt(text: String, source: String): List<IndexRow> {
        val arr = JSONObject(text).optJSONArray("modules") ?: return emptyList()
        val rows = ArrayList<IndexRow>(arr.length())
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val id = obj.optString("id")
            val zip = obj.optString("zip_url")
            if (id.isBlank() || !zip.startsWith("https://")) continue
            if (!textAllowsMagisk(id, "")) continue
            rows += IndexRow(
                id = id,
                lastUpdate = obj.optLong("last_update"),
                propUrl = obj.optString("prop_url"),
                zipUrl = zip,
                notesUrl = obj.optString("notes_url"),
                stars = obj.optInt("stars"),
                source = source,
            )
        }
        return rows
    }

    private fun parseMmrl(text: String, source: String): List<IndexRow> {
        val arr = JSONObject(text).optJSONArray("modules") ?: return emptyList()
        val rows = ArrayList<IndexRow>(arr.length())
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            if (!isMagiskCompatible(obj)) continue
            val id = obj.optString("id")
            val latest = latestVersion(obj.optJSONArray("versions")) ?: continue
            val zip = latest.optString("zipUrl")
            if (id.isBlank() || !zip.startsWith("https://")) continue
            rows += IndexRow(
                id = id,
                lastUpdate = unixMillis(obj.optDouble("timestamp")),
                zipUrl = zip,
                notesUrl = latest.optString("changelog"),
                stars = obj.optInt("stars"),
                name = obj.optString("name"),
                author = obj.optString("author"),
                version = latest.optString("version").ifBlank { obj.optString("version") },
                versionCode = latest.optInt("versionCode", obj.optInt("versionCode", -1)),
                description = obj.optString("description"),
                source = source,
            )
        }
        return rows
    }

    private suspend fun fetchFeatured(): List<IndexRow> = coroutineScope {
        FEATURED.map { module ->
            async(Dispatchers.IO) {
                runCatching {
                    val json = JSONObject(ServiceLocator.networkService.fetchString(module.updateUrl))
                    val zip = json.optString("zipUrl")
                    if (!zip.startsWith("https://")) return@runCatching null
                    IndexRow(
                        id = module.id,
                        lastUpdate = System.currentTimeMillis(),
                        zipUrl = zip,
                        notesUrl = json.optString("changelog"),
                        stars = 0,
                        name = module.name,
                        author = module.author,
                        version = json.optString("version"),
                        versionCode = json.optInt("versionCode", -1),
                        description = module.description,
                        source = SOURCE_OFFICIAL,
                        official = true,
                    )
                }.onFailure { Timber.w(it, "featured %s", module.id) }.getOrNull()
            }
        }.awaitAll().filterNotNull()
    }

    private fun mergeRows(rows: List<IndexRow>): List<IndexRow> {
        val merged = LinkedHashMap<String, IndexRow>()
        for (row in rows) {
            val previous = merged[row.id]
            merged[row.id] = if (previous == null) row else prefer(previous, row)
        }
        return merged.values.toList()
    }

    private fun prefer(current: IndexRow, candidate: IndexRow): IndexRow {
        val winner = when {
            candidate.versionCode > current.versionCode -> candidate
            current.versionCode > candidate.versionCode && current.versionCode > 0 -> current
            candidate.official && !current.official -> candidate
            else -> current
        }
        val other = if (winner === candidate) current else candidate
        return winner.copy(
            stars = maxOf(current.stars, candidate.stars),
            name = winner.name.ifBlank { other.name },
            author = winner.author.ifBlank { other.author },
            description = winner.description.ifBlank { other.description },
            propUrl = winner.propUrl.ifBlank { other.propUrl },
            notesUrl = winner.notesUrl.ifBlank { other.notesUrl },
        )
    }

    private fun latestVersion(versions: JSONArray?): JSONObject? {
        if (versions == null || versions.length() == 0) return null
        var best: JSONObject? = null
        for (i in 0 until versions.length()) {
            val version = versions.optJSONObject(i) ?: continue
            if (!version.optString("zipUrl").startsWith("https://")) continue
            if (best == null ||
                version.optInt("versionCode") > best.optInt("versionCode") ||
                (version.optInt("versionCode") == best.optInt("versionCode") &&
                    version.optDouble("timestamp") > best.optDouble("timestamp"))
            ) best = version
        }
        return best
    }

    private fun isMagiskCompatible(obj: JSONObject): Boolean {
        val note = obj.optJSONObject("note")
        val noteText = note?.optString("title").orEmpty() + " " + note?.optString("message").orEmpty()
        if (!textAllowsMagisk(obj.optString("id") + " " + obj.optString("name"),
                obj.optString("description") + " " + noteText))
            return false
        val manager = obj.optJSONObject("manager") ?: obj.optJSONObject("root")
        val magisk = manager?.optJSONObject("magisk")
        // MMRL uses min -1 when that root solution is explicitly unsupported.
        val min = if (magisk != null && magisk.has("min")) magisk.optInt("min", 0) else null
        if (min != null && min < 0) return false
        val meta = obj.optString("metamodule")
        if ((meta == "1" || meta.equals("true", true)) && (min == null || min < 0))
            return false
        val installed = Info.env.versionCode
        if (min != null && min > 0 && installed > 0 && min > installed) return false
        return true
    }

    private fun textAllowsMagisk(name: String, description: String): Boolean {
        val text = "$name $description".lowercase(Locale.ROOT)
        if (text.contains("not for magisk") || text.contains("not compatible with magisk") ||
            text.contains("kernelsu only") || text.contains("apatch only") || text.contains("ksu only")
        ) return false
        val mentionsMagisk = text.contains("magisk")
        val mentionsOther = text.contains("kernelsu") || text.contains("kernel su") ||
            text.contains("apatch") || KSU_WORD.containsMatchIn(text)
        return !mentionsOther || mentionsMagisk
    }

    private fun unixMillis(raw: Double): Long {
        if (raw <= 0.0) return 0L
        return if (raw < 10_000_000_000.0) (raw * 1000.0).toLong() else raw.toLong()
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
        val propUrl: String = "",
        val zipUrl: String,
        val notesUrl: String = "",
        val stars: Int = 0,
        val name: String = "",
        val author: String = "",
        val version: String = "",
        val versionCode: Int = -1,
        val description: String = "",
        val source: String = "",
        val official: Boolean = false,
    )

    private enum class RepoKind { ALT, MMRL }

    private data class RepoSource(val name: String, val url: String, val kind: RepoKind)

    private data class FeaturedModule(
        val id: String,
        val name: String,
        val author: String,
        val description: String,
        val updateUrl: String,
    )

    companion object {
        private const val SOURCE_OFFICIAL = "Official"
        private val KSU_WORD = Regex("""(^|[^a-z])ksu([^a-z]|$)""")
        private const val MAX_DOWNLOAD = 80L * 1024L * 1024L
        private val REPOS = listOf(
            RepoSource(
                "Googlers",
                "https://gr.dergoogler.com/gmr/json/modules.json",
                RepoKind.MMRL,
            ),
            RepoSource(
                "Alt Repo",
                "https://raw.githubusercontent.com/Magisk-Modules-Alt-Repo/json/main/modules.json",
                RepoKind.ALT,
            ),
        )
        private val FEATURED = listOf(
            FeaturedModule(
                "zygisksu",
                "Zygisk Next",
                "5ec1cff, Nullptr, aviraxp",
                "Standalone Zygisk for Magisk. Turn the built-in Zygisk switch off before use.",
                "https://api.nullptr.icu/android/zygisk-next/static/update.json",
            ),
            FeaturedModule(
                "rezygisk",
                "ReZygisk",
                "PerformanC",
                "Standalone Zygisk for Magisk. Turn the built-in Zygisk switch off before use.",
                "https://raw.githubusercontent.com/ThePedroo/RemoteFiles/refs/heads/main/ReZygisk/update.json",
            ),
            FeaturedModule(
                "zygisk_vector",
                "Vector",
                "JingMatrix",
                "Xposed-compatible framework for Magisk Zygisk.",
                "https://raw.githubusercontent.com/JingMatrix/Vector/master/zygisk/update.json",
            ),
            FeaturedModule(
                "playintegrityfix",
                "Play Integrity Fork",
                "osm0sis",
                "Magisk module for Play Integrity. Replaces the unmaintained Play Integrity Fix.",
                "https://raw.githubusercontent.com/osm0sis/PlayIntegrityFork/main/update.json",
            ),
        )
    }
}
