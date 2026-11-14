package me.ash.reader.domain.data

import android.content.Context
import androidx.work.ListenableWorker
import java.io.File
import java.util.Date
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import me.ash.reader.domain.model.account.Account
import me.ash.reader.domain.model.account.AccountType
import me.ash.reader.domain.model.article.Article
import me.ash.reader.domain.model.article.ArticleWithFeed
import me.ash.reader.domain.model.feed.Feed
import me.ash.reader.domain.service.AbstractRssRepository
import me.ash.reader.domain.service.AccountService
import me.ash.reader.domain.service.RssService
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.junit.MockitoJUnitRunner
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock

@RunWith(MockitoJUnitRunner.Silent::class)
class DiffMapHolderTest {
    private lateinit var tempDir: File
    private lateinit var scope: CoroutineScope
    private lateinit var accountFlow: MutableStateFlow<Account?>

    private val account = Account(id = 1, name = "Local", type = AccountType(1))

    @Before
    fun setUp() {
        tempDir = createTempDir()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        accountFlow = MutableStateFlow(null)
    }

    @After
    fun tearDown() {
        scope.cancel()
        tempDir.deleteRecursively()
    }

    private fun newHolder(service: AbstractRssRepository): DiffMapHolder {
        val context = mock<Context> { on { cacheDir } doReturn tempDir }
        val accountService = mock<AccountService> { on { currentAccountFlow } doReturn accountFlow }
        return DiffMapHolder(
            context, scope, Dispatchers.Unconfined, accountService,
            mock<RssService> { on { get() } doReturn service },
        )
    }

    private fun article(id: String, isUnread: Boolean = true) = ArticleWithFeed(
        article = Article(
            id = id,
            date = Date(0),
            title = id,
            rawDescription = "",
            shortDescription = "",
            link = "",
            feedId = "feed",
            accountId = 1,
            isUnread = isUnread,
        ),
        feed = Feed(
            id = "feed",
            name = "feed",
            icon = null,
            url = "",
            groupId = "group",
            accountId = 1,
        ),
    )

    @Test
    fun `commit clears overlay only after database write succeeds`() = runBlocking {
        val writes = mutableListOf<Pair<Set<String>, Boolean>>()
        accountFlow.value = account
        val service = object : TestRssService() {
            override suspend fun batchMarkAsRead(articleIds: Set<String>, isUnread: Boolean) {
                writes += articleIds to isUnread
            }
        }
        val holder = newHolder(service)
        holder.updateDiff(article("a"), isUnread = false)
        assertFalse(holder.checkIfUnread(article("a")))

        holder.commitDiffs()

        assertEquals(listOf(setOf("a") to false, emptySet<String>() to true), writes)
        assertFalse(holder.diffMap.containsKey("a"))
        assertFalse(cacheFile().exists())
    }

    @Test
    fun `failed database write keeps overlay and cache`() = runBlocking {
        accountFlow.value = account
        val service = object : TestRssService() {
            override suspend fun batchMarkAsRead(articleIds: Set<String>, isUnread: Boolean) {
                error("db unavailable")
            }
        }
        val holder = newHolder(service)
        holder.updateDiff(article("b"), isUnread = false)

        runCatching { holder.commitDiffs() }

        assertFalse(holder.checkIfUnread(article("b")))
        assertTrue(holder.diffMap.containsKey("b"))
        assertTrue(cacheFile().readText().contains(""""articleId":"b""""))
    }

    @Test
    fun `account field update does not clear pending read status`() = runBlocking {
        val writes = mutableListOf<Pair<Set<String>, Boolean>>()
        accountFlow.value = account
        val service = object : TestRssService() {
            override suspend fun batchMarkAsRead(articleIds: Set<String>, isUnread: Boolean) {
                writes += articleIds to isUnread
            }
        }
        val holder = newHolder(service)
        holder.updateDiff(article("a"), isUnread = false)

        accountFlow.value = account.copy(updateAt = Date())
        assertFalse(holder.checkIfUnread(article("a")))

        holder.commitDiffs()
        assertTrue(writes.any { it.first.contains("a") && !it.second })
        assertFalse(holder.diffMap.containsKey("a"))
    }

    private fun cacheFile() = tempDir.resolve("diff").resolve("1").resolve("diff_map.json")

    private open class TestRssService : AbstractRssRepository(
        mock(), mock(), mock(), mock(), mock(), mock(),
        mock(), mock(), mock(),
    ) {
        override suspend fun sync(
            accountId: Int,
            feedId: String?,
            groupId: String?,
        ): ListenableWorker.Result = ListenableWorker.Result.success()
    }
}
