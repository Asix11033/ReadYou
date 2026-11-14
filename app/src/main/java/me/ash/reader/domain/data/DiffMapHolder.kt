package me.ash.reader.domain.data

import android.content.Context
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshotFlow
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.ash.reader.domain.model.account.Account
import me.ash.reader.domain.model.account.AccountType
import me.ash.reader.domain.model.article.ArticleWithFeed
import me.ash.reader.domain.service.AccountService
import me.ash.reader.domain.service.RssService
import me.ash.reader.infrastructure.di.ApplicationScope
import me.ash.reader.infrastructure.di.IODispatcher
import java.io.File
import javax.inject.Inject
import timber.log.Timber

@OptIn(FlowPreview::class)
class DiffMapHolder @Inject constructor(
    @ApplicationContext private val context: Context,
    @ApplicationScope private val applicationScope: CoroutineScope,
    @IODispatcher private val ioDispatcher: CoroutineDispatcher,
    private val accountService: AccountService,
    private val rssService: RssService,
) {
    val diffMap = mutableStateMapOf<String, Diff>()

    private val pendingSyncDiffs = mutableStateMapOf<String, Diff>()
    private val syncedDiffs = mutableMapOf<String, Diff>()

    val diffMapSnapshotFlow = snapshotFlow { diffMap.toMap() }.stateIn(
        applicationScope, SharingStarted.Eagerly, emptyMap()
    )

    private val pendingSyncDiffsSnapshotFlow = snapshotFlow { pendingSyncDiffs.toMap() }.stateIn(
        applicationScope, SharingStarted.Eagerly, emptyMap()
    )

    val shouldSyncWithRemote get() = currentAccount?.type?.id != AccountType.Local.id

    private val gson = Gson()

    private val cacheDir = context.cacheDir.resolve("diff")
    private var userCacheDir: File? = null

    private var currentAccount: Account? = null

    private val cacheFile: File? get() = userCacheDir?.resolve("diff_map.json")

    var dbJob: Job? = null
    var remoteJob: Job? = null

    /** Serializes cache writes, DB commits, cache recovery and account changes. */
    private val storageMutex = Mutex()

    init {
        applicationScope.launch {
            accountService.currentAccountFlow.mapNotNull { it }.collect { account ->
                val previousAccount = currentAccount
                if (previousAccount == null) {
                    switchAccount(null, account)
                } else if (previousAccount.id != account.id) {
                    switchAccount(previousAccount, account)
                } else {
                    // Sync updates fields such as updateAt; this is not an account switch.
                    currentAccount = account
                }
            }
        }
    }

    private suspend fun switchAccount(previousAccount: Account?, account: Account) =
        storageMutex.withLock {
            if (previousAccount != null) {
                writeDiffsToCache(
                    cacheDir.resolve(previousAccount.id.toString()).resolve("diff_map.json")
                )
                dbJob?.cancel()
                remoteJob?.cancel()
                diffMap.clear()
                pendingSyncDiffs.clear()
                syncedDiffs.clear()
            }
            currentAccount = account
            userCacheDir = cacheDir.resolve(account.id.toString())
            val file = userCacheDir!!.resolve("diff_map.json")
            restoreDiffsFromCache(file)
            // A failed DB write must retain the cache and overlay for the next commit.
            runCatching { commitDiffsToDbInternal(file) }
                .onFailure { Timber.e(it, "Failed to commit recovered read status") }
            commitOnChange()
            if (account.type.id != AccountType.Local.id) {
                syncOnChange()
            }
        }

    private fun commitOnChange() {
        dbJob = applicationScope.launch(ioDispatcher) {
            diffMapSnapshotFlow.debounce(2_000).collect {
                storageMutex.withLock {
                    // Never overwrite a recovered/committed state with a stale snapshot.
                    cacheFile?.let { file ->
                        if (diffMap.isEmpty()) file.delete() else writeDiffsToCache(file)
                    }
                }
            }
        }
    }

    private fun syncOnChange() {
        remoteJob = applicationScope.launch(ioDispatcher) {
            pendingSyncDiffsSnapshotFlow.debounce(2_000).collect {
                withContext(ioDispatcher) {
                    syncDiffsWithRemote(it)
                }
            }
        }
    }

    fun checkIfUnread(articleWithFeed: ArticleWithFeed): Boolean {
        return diffMap[articleWithFeed.article.id]?.isUnread ?: articleWithFeed.article.isUnread
    }

    /**
     * Updates the diff map with changes to an article's read/unread status.
     *
     * This function manages a map (`diffMap`) that tracks pending changes (diffs) to the
     * read/unread status of articles. These changes are not immediately applied to the
     * underlying data store but are held in `diffMap` until a later commit operation.
     *
     * The function supports three modes of updating:
     *
     * 1. **Toggle:** If `isUnread` is `null`, the function toggles the current read/unread
     *    status of the article.  If the article is currently unread, it will be marked as read,
     *    and vice-versa.
     * 2. **Mark as Unread:** If `isUnread` is `true`, the article will be marked as unread,
     *    regardless of its current status.
     * 3. **Mark as Read:** If `isUnread` is `false`, the article will be marked as read,
     *    regardless of its current status.
     *
     * The function determines if a change needs to be tracked based on the current status and desired status:
     *  - If the requested change matches the article's current status, the diff is removed from the map, if it exists. (No change is needed.)
     *  - Otherwise, the diff is added to or updated in the map.
     *
     * @param articleWithFeed The article and its associated feed data. This is used to identify the article
     *                        and access its current read/unread state.
     * @param isUnread An optional boolean indicating the desired read/unread status of the article.
     *                 - `null`: Toggles the current read/unread status.
     *                 - `true`: Marks the article as unread.
     *                 - `false`: Marks the article as read.
     *
     * @return A [Diff] object representing the changes made to the article.
     *
     * @see Diff
     */
    private fun updateDiffInternal(
        articleWithFeed: ArticleWithFeed, isUnread: Boolean? = null
    ): Diff? {
        val articleId = articleWithFeed.article.id

        val diff = diffMap[articleId]

        if (diff == null) {
            val isUnread = isUnread ?: !articleWithFeed.article.isUnread
            val diff = Diff(
                isUnread = isUnread, articleWithFeed = articleWithFeed
            )
            diffMap[articleId] = diff
            return diff
        } else {
            if (isUnread == null || diff.isUnread != isUnread) {
                val diff = diffMap.remove(articleId)
                return diff?.copy(isUnread = !diff.isUnread)
            }
        }
        return null
    }

    fun updateDiff(
        vararg articleWithFeed: ArticleWithFeed, isUnread: Boolean? = null
    ) {
        val appliedDiffs = articleWithFeed.mapNotNull {
            updateDiffInternal(it, isUnread)
        }
        if (shouldSyncWithRemote) {
            appliedDiffs.forEach {
                appendDiffToSync(it)
            }
        }
    }

    private fun appendDiffToSync(diff: Diff) {
        val syncedDiff = syncedDiffs[diff.articleId]
        if (syncedDiff == null || syncedDiff.isUnread != diff.isUnread) {
            pendingSyncDiffs[diff.articleId] = diff
        }
    }

    fun commitDiffsToDb(): Job = applicationScope.launch(ioDispatcher) {
        runCatching { withContext(NonCancellable) { commitDiffs() } }
            .onFailure { Timber.e(it, "Failed to commit pending read status") }
    }

    /** Returns only after the pending state has been written to the DB. */
    suspend fun commitDiffs() = storageMutex.withLock {
        cacheFile?.let { commitDiffsToDbInternal(it) }
    }

    /**
     * Writes the DB first and removes the overlay only after both directions succeeded.
     * Callers hold [storageMutex].
     */
    private suspend fun commitDiffsToDbInternal(file: File) {
        val toCommit = diffMap.toMap()
        if (toCommit.isEmpty()) {
            file.delete()
            return
        }

        val markAsReadArticles = toCommit.filter { !it.value.isUnread }.map { it.key }.toSet()
        val markAsUnreadArticles = toCommit.filter { it.value.isUnread }.map { it.key }.toSet()
        try {
            rssService.get().batchMarkAsRead(articleIds = markAsReadArticles, isUnread = false)
            rssService.get().batchMarkAsRead(articleIds = markAsUnreadArticles, isUnread = true)
        } catch (e: Exception) {
            // Keep both the UI overlay and cache so the change can be retried.
            writeDiffsToCache(file)
            throw e
        }

        // Remove only entries committed above; changes made during the DB write are kept.
        toCommit.forEach { (id, diff) ->
            if (diffMap[id] == diff) diffMap.remove(id)
        }
        file.delete()
        if (diffMap.isNotEmpty()) writeDiffsToCache(file)
    }

    private fun writeDiffsToCache(file: File, snapshot: Map<String, Diff> = diffMap.toMap()) {
        try {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(gson.toJson(snapshot))
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
        } catch (e: Exception) {
            // The in-memory overlay remains authoritative until a successful commit.
            Timber.e(e, "Failed to write diff cache")
        }
    }

    private suspend fun syncDiffsWithRemote(diffs: Map<String, Diff>) {
        if (!shouldSyncWithRemote) return
        if (diffs.isEmpty()) return
        val toBeSync = diffs
        val markAsReadArticles =
            toBeSync.filter { !it.value.isUnread }.map { it.key }.toSet()
        val markAsUnreadArticles =
            toBeSync.filter { it.value.isUnread }.map { it.key }.toSet()

        val rssService = rssService.get()

        val synced = supervisorScope {
            val read = async {
                rssService.syncReadStatus(
                    articleIds = markAsReadArticles,
                    isUnread = false
                )
            }
            val unread = async {
                rssService.syncReadStatus(
                    articleIds = markAsUnreadArticles,
                    isUnread = true
                )
            }
            runCatching { read.await() }.getOrElse { emptySet() } +
                    runCatching { unread.await() }.getOrElse { emptySet() }
        }

        val currentIds = toBeSync.filterKeys { pendingSyncDiffs.containsKey(it) }.keys
        pendingSyncDiffs -= synced.intersect(currentIds)
        syncedDiffs += toBeSync.filter { synced.contains(it.key) }
    }

    private suspend fun restoreDiffsFromCache(file: File) = withContext(ioDispatcher) {
        if (file.exists() && file.canRead()) {
            val cached = runCatching {
                gson.fromJson<Map<String, Diff>>(
                    file.readText(), object : TypeToken<Map<String, Diff>>() {}.type
                )
            }.onFailure { Timber.e(it, "Failed to read diff cache") }.getOrNull()
            if (cached != null) diffMap.putAll(cached)
        }
    }
}

data class Diff(
    val isUnread: Boolean, val articleId: String, val feedId: String
) {
    constructor(isUnread: Boolean, articleWithFeed: ArticleWithFeed) : this(
        isUnread = isUnread,
        articleId = articleWithFeed.article.id,
        feedId = articleWithFeed.feed.id,
    )
}
