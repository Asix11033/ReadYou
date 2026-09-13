package me.ash.reader.ui.page.home.reading

import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.ash.reader.R
import me.ash.reader.domain.model.article.ReadingPosition
import me.ash.reader.infrastructure.android.TextToSpeechManager
import me.ash.reader.infrastructure.preference.LocalPullToSwitchArticle
import me.ash.reader.infrastructure.preference.LocalReadingAutoHideToolbar
import me.ash.reader.infrastructure.preference.LocalReadingBoldCharacters
import me.ash.reader.infrastructure.preference.LocalReadingRememberPosition
import me.ash.reader.infrastructure.preference.LocalReadingRenderer
import me.ash.reader.infrastructure.preference.LocalReadingTextLineHeight
import me.ash.reader.infrastructure.preference.ReadingRendererPreference
import me.ash.reader.infrastructure.preference.not
import me.ash.reader.ui.ext.collectAsStateValue
import me.ash.reader.ui.ext.showToast
import me.ash.reader.ui.page.adaptive.ArticleListReaderViewModel
import me.ash.reader.ui.page.adaptive.NavigationAction
import me.ash.reader.ui.page.adaptive.ReaderState
import me.ash.reader.ui.page.home.reading.tts.TtsButton

private const val UPWARD = 1
private const val DOWNWARD = -1

/** 阅读位置捕获的去抖时长：滚动停止约 0.8s 后才落盘，避免高频写库。 */
private const val POSITION_CAPTURE_DEBOUNCE_MS = 800L

/** 像素偏移小于该值时视为「仍在顶部」，不写位置记录。 */
private const val POSITION_AT_TOP_PX = 8

/** 当前渲染器对应的位置记录模式。 */
private fun ReadingRendererPreference.positionMode(): Int =
    when (this) {
        ReadingRendererPreference.WebView -> ReadingPosition.MODE_WEBVIEW
        ReadingRendererPreference.NativeComponent -> ReadingPosition.MODE_NATIVE
    }

/** 把锚点落成一条可持久化的位置记录（`accountId` / `updatedAt` 由 ViewModel 统一填充）。 */
private fun Anchor.toReadingPosition(articleId: String, mode: Int, content: String) =
    ReadingPosition(
        articleId = articleId,
        accountId = -1,
        mode = mode,
        index = itemIndex,
        offset = offset,
        anchorId = id,
        anchorOffset = anchorOffset,
        ratio = ratio,
        contentLength = content.length,
        contentHash = content.hashCode(),
        updatedAt = 0L,
    )

/**
 * 把位置记录还原成可跳转的锚点。
 *
 * 三种情况：
 * 1. 渲染器一致 + 内容指纹一致 → 精确恢复（像素/项序号 + 锚点漂移补偿）；
 * 2. 用户换过渲染器 → 像素与项序号互不可用，退化为比例落位；
 * 3. 渲染器一致但内容变了（摘要 ↔ 全文）→ **不猜**，从头开始。
 */
private fun ReadingPosition.toAnchorOrNull(currentMode: Int, content: String): Anchor? {
    val sameContent = contentLength == content.length && contentHash == content.hashCode()
    return when {
        mode == currentMode && sameContent ->
            Anchor(
                id = anchorId,
                itemIndex = index,
                offset = offset,
                ratio = ratio,
                anchorOffset = anchorOffset,
            )

        mode != currentMode && ratio in 0.02f..0.97f ->
            Anchor(id = anchorId, itemIndex = -1, offset = 0, ratio = ratio, anchorOffset = -1)

        else -> null
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterialApi::class)
@Composable
fun ReadingPage(
    //    navController: NavHostController,
    viewModel: ArticleListReaderViewModel,
    navigationAction: NavigationAction,
    onLoadArticle: (String, Int) -> Unit,
    onNavAction: (NavigationAction) -> Unit,
    onNavigateToStylePage: () -> Unit,
) {
    val context = LocalContext.current
    val hapticFeedback = LocalHapticFeedback.current
    val isPullToSwitchArticleEnabled = LocalPullToSwitchArticle.current.value
    val readingUiState = viewModel.readingUiState.collectAsStateValue()
    val readerState = viewModel.readerStateStateFlow.collectAsStateValue()
    val boldCharacters = LocalReadingBoldCharacters.current
    val coroutineScope = rememberCoroutineScope()
    val renderer = LocalReadingRenderer.current
    val rememberPositionEnabled = LocalReadingRememberPosition.current.value

    var isReaderScrollingDown by remember { mutableStateOf(false) }
    var showFullScreenImageViewer by remember { mutableStateOf(false) }

    var currentImageData by remember { mutableStateOf(ImageData()) }

    // ------------------------------------------------------------------
    // 锚点层消费方：目录（P2）、返回阅读处（P3）、阅读位置记忆（P4）
    // ------------------------------------------------------------------
    val anchorBridge = remember { ReaderAnchorBridge() }
    val contentText = readerState.content.text.orEmpty()

    // 单点预处理：一次 Jsoup 解析同时产出「补过 id 的 HTML」与目录项。
    // 放到默认调度器上做，避免 40KB 级 HTML 在主线程解析；未就绪前退化为原始内容（与改动前一致）。
    val preparedState = remember(contentText) { mutableStateOf<PreparedContent?>(null) }
    LaunchedEffect(contentText) {
        preparedState.value =
            if (contentText.isBlank()) null
            else withContext(Dispatchers.Default) { TocExtractor.prepare(contentText) }
    }
    val prepared = preparedState.value
    val tocItems = prepared?.items.orEmpty()
    val isTocAvailable = tocItems.size >= TocExtractor.MIN_TOC_ITEMS

    var showToc by remember { mutableStateOf(false) }

    /** 返回点：内存里的临时状态，只有一层，随 `articleId` 变化清除。 */
    var returnPoint by remember { mutableStateOf<Anchor?>(null) }

    /** 滚动意图：程序化滚动窗口内不写入阅读位置记忆（P4 的唯一消费者）。 */
    var scrollIntent by remember { mutableStateOf(ScrollIntent.Idle) }

    /**
     * 「本篇已做过位置恢复」标记。
     *
     * 用 `remember(articleId)` 而不是在 effect 里清零：key 变化时状态在**组合期**就地重建，
     * 不依赖两个 LaunchedEffect 的执行先后顺序（后者在 AnimatedContent 的子组合里，
     * 与外层 effect 的先后是不保证的）。
     */
    var positionRestored by remember(readerState.articleId) { mutableStateOf(false) }

    // ReadingPage 切文章时不销毁 → 所有临时的 remember 状态必须显式绑定 articleId 清除
    LaunchedEffect(readerState.articleId) {
        returnPoint = null
        showToc = false
    }

    SideEffect {
        anchorBridge.renderer = renderer
        anchorBridge.items = tocItems
    }

    /** 把 `scrollIntent` 的复位统一挂到协程完成回调上（**含取消路径**）。 */
    fun launchProgrammaticScroll(block: suspend () -> Unit): Job {
        scrollIntent = ScrollIntent.Programmatic
        val job = coroutineScope.launch {
            try {
                block()
            } finally {
                scrollIntent = ScrollIntent.Idle
            }
        }
        // 取消路径也要复位，否则标志位会永久为 true（极难排查的静默失效）
        job.invokeOnCompletion { scrollIntent = ScrollIntent.Idle }
        return job
    }

    /** 目录跳转：先记返回点，再跳。 */
    fun jumpToTocItem(item: TocItem) {
        val resolver = anchorBridge.resolver() ?: return
        launchProgrammaticScroll {
            resolver.captureCurrent()?.let { returnPoint = it }
            resolver.scrollTo(item.id)
        }
    }

    /** 返回跳转前的位置。 */
    fun returnToReading() {
        val anchor = returnPoint ?: return
        val resolver = anchorBridge.resolver()
        returnPoint = null
        if (resolver == null) return
        launchProgrammaticScroll { resolver.restore(anchor) }
        context.showToast(context.getString(R.string.reading_returned))
    }

    // 系统返回键：有返回点时优先回阅读处，再按一次才退出文章
    BackHandler(enabled = returnPoint != null) { returnToReading() }

    val isShowToolBar =
        if (LocalReadingAutoHideToolbar.current.value) {
            readerState.articleId != null && !isReaderScrollingDown
        } else {
            true
        }

    var showTopDivider by remember { mutableStateOf(false) }

    //    LaunchedEffect(readerState.listIndex) {
    //        readerState.listIndex?.let {
    //            navController.previousBackStackEntry?.savedStateHandle?.set("articleIndex", it)
    //        }
    //    }

    var bringToTop by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        content = { paddings ->
            Box(modifier = Modifier.fillMaxSize()) {
                if (readerState.articleId != null) {
                    TopBar(
                        isShow = isShowToolBar,
                        isScrolled = showTopDivider,
                        title = readerState.title,
                        link = readerState.link,
                        onClick = { bringToTop = true },
                        navigationAction = navigationAction,
                        onNavButtonClick = onNavAction,
                        onNavigateToStylePage = onNavigateToStylePage,
                    )
                }

                val isNextArticleAvailable = readerState.nextArticle != null
                val isPreviousArticleAvailable = readerState.previousArticle != null

                if (readerState.articleId != null) {
                    // Content
                    AnimatedContent(
                        targetState = readerState,
                        transitionSpec = {
                            val direction =
                                when {
                                    initialState.nextArticle?.articleId == targetState.articleId ->
                                        UPWARD
                                    initialState.previousArticle?.articleId ==
                                        targetState.articleId -> DOWNWARD
                                    initialState.articleId == targetState.articleId -> {
                                        when (targetState.content) {
                                            is ReaderState.Description -> DOWNWARD
                                            else -> UPWARD
                                        }
                                    }

                                    else -> UPWARD
                                }
                            val exit = 100
                            val enter = exit * 2
                            (slideInVertically(
                                initialOffsetY = { (it * 0.2f * direction).toInt() },
                                animationSpec =
                                    spring(
                                        dampingRatio = .9f,
                                        stiffness = Spring.StiffnessLow,
                                        visibilityThreshold = IntOffset.VisibilityThreshold,
                                    ),
                            ) +
                                fadeIn(
                                    tween(
                                        delayMillis = exit,
                                        durationMillis = enter,
                                        easing = LinearOutSlowInEasing,
                                    )
                                )) togetherWith
                                (slideOutVertically(
                                    targetOffsetY = { (it * -0.2f * direction).toInt() },
                                    animationSpec =
                                        spring(
                                            dampingRatio = Spring.DampingRatioNoBouncy,
                                            stiffness = Spring.StiffnessLow,
                                            visibilityThreshold = IntOffset.VisibilityThreshold,
                                        ),
                                ) +
                                    fadeOut(
                                        tween(durationMillis = exit, easing = FastOutLinearInEasing)
                                    ))
                        },
                        label = "",
                    ) {
                        remember { it }
                            .run {
                                val state =
                                    rememberPullToLoadState(
                                        key = content,
                                        onLoadNext =
                                            if (isNextArticleAvailable) {
                                                {
                                                    val (id, index) = readerState.nextArticle
                                                    onLoadArticle(id, index)
                                                }
                                            } else null,
                                        onLoadPrevious =
                                            if (isPreviousArticleAvailable) {
                                                {
                                                    val (id, index) = readerState.previousArticle
                                                    onLoadArticle(id, index)
                                                }
                                            } else null,
                                    )

                                val listState =
                                    rememberSaveable(
                                        inputs = arrayOf(content),
                                        saver = LazyListState.Saver,
                                    ) {
                                        LazyListState()
                                    }

                                val scrollState = rememberScrollState()

                                val scope = rememberCoroutineScope()

                                // 两条渲染路径的滚动状态在这里挂到锚点桥上（组合完成后即可用）
                                LaunchedEffect(scrollState, listState) {
                                    anchorBridge.attach(scrollState, listState)
                                }

                                // ---------- P4 · 阅读位置记忆：恢复 ----------
                                // 时序要求：内容已落地（不再是 Loading）+ 滚动容器与 WebView 都已上桥。
                                // 每篇文章只做一次；恢复属于程序化滚动，因此不会反过来被记进记忆。
                                LaunchedEffect(articleId, content) {
                                    if (!rememberPositionEnabled) return@LaunchedEffect
                                    if (content is ReaderState.Loading) return@LaunchedEffect
                                    val id = articleId ?: return@LaunchedEffect
                                    if (positionRestored) return@LaunchedEffect
                                    val text = content.text.orEmpty()
                                    if (text.isBlank()) return@LaunchedEffect
                                    val anchor =
                                        viewModel
                                            .loadReadingPosition(id)
                                            ?.toAnchorOrNull(renderer.positionMode(), text)
                                            ?: return@LaunchedEffect
                                    positionRestored = true
                                    launchProgrammaticScroll {
                                        anchorBridge.awaitResolver()?.restore(anchor)
                                    }
                                }

                                // ---------- P4 · 阅读位置记忆：捕获 ----------
                                // 去抖落盘：滚动停止约 0.8s 后写一次；程序化滚动期间一律跳过。
                                LaunchedEffect(articleId, content, renderer, rememberPositionEnabled) {
                                    if (!rememberPositionEnabled) return@LaunchedEffect
                                    if (content is ReaderState.Loading) return@LaunchedEffect
                                    val id = articleId ?: return@LaunchedEffect
                                    val text = content.text.orEmpty()
                                    if (text.isBlank()) return@LaunchedEffect
                                    val mode = renderer.positionMode()
                                    snapshotFlow {
                                            if (renderer == ReadingRendererPreference.WebView) {
                                                scrollState.value to 0
                                            } else {
                                                listState.firstVisibleItemIndex to
                                                    listState.firstVisibleItemScrollOffset
                                            }
                                        }
                                        .debounce(POSITION_CAPTURE_DEBOUNCE_MS)
                                        .collect {
                                            // 程序化滚动窗口内不写记忆：否则「目录跳到生词表 →
                                            // 退出 → 重开」会直接落在生词表
                                            if (scrollIntent != ScrollIntent.Idle) return@collect
                                            val anchor =
                                                anchorBridge.resolver()?.captureCurrent()
                                                    ?: return@collect
                                            val position = anchor.toReadingPosition(id, mode, text)
                                            when {
                                                // 已读到末尾：清空记录，下次从头开始
                                                position.ratio >
                                                    ReadingPosition.READ_COMPLETED_RATIO ->
                                                    viewModel.clearReadingPosition(id)
                                                // 仍在顶部：不写（也**不删**，避免与恢复竞争）
                                                anchor.offset < POSITION_AT_TOP_PX &&
                                                    anchor.itemIndex <= 0 -> Unit
                                                else -> viewModel.saveReadingPosition(position)
                                            }
                                        }
                                }

                                LaunchedEffect(bringToTop) {
                                    if (bringToTop) {
                                        // 点顶栏回顶：视为放弃原上下文 → 清空返回点与阅读位置记忆；
                                        // 长距离同样走阈值化跳转（瞬时），不再用十几屏的高速动画
                                        returnPoint = null
                                        articleId?.let { viewModel.clearReadingPosition(it) }
                                        launchProgrammaticScroll {
                                            val resolver = anchorBridge.resolver()
                                            when {
                                                resolver != null -> resolver.scrollToTop()
                                                scrollState.value != 0 -> scrollState.scrollTo(0)
                                                listState.firstVisibleItemIndex != 0 ->
                                                    listState.scrollToItem(0)
                                            }
                                        }
                                            .invokeOnCompletion { bringToTop = false }
                                    }
                                }

                                showTopDivider =
                                    snapshotFlow {
                                            scrollState.value >= 120 ||
                                                listState.firstVisibleItemIndex != 0
                                        }
                                        .collectAsStateValue(initial = false)

                                CompositionLocalProvider(
                                    LocalTextStyle provides
                                        LocalTextStyle.current.run {
                                            merge(
                                                lineHeight =
                                                    if (lineHeight.isSpecified)
                                                        (lineHeight.value *
                                                                LocalReadingTextLineHeight.current)
                                                            .sp
                                                    else TextUnit.Unspecified
                                            )
                                        }
                                ) {
                                    Box(
                                        modifier = Modifier.fillMaxSize(),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Content(
                                            modifier =
                                                Modifier.pullToLoad(
                                                    state = state,
                                                    onScroll = { f ->
                                                        if (abs(f) > 2f)
                                                            isReaderScrollingDown = f < 0f
                                                    },
                                                    enabled = isPullToSwitchArticleEnabled,
                                                ),
                                            contentPadding = paddings,
                                            content = content.text ?: "",
                                            feedName = feedName,
                                            title = title.toString(),
                                            author = author,
                                            link = link,
                                            publishedDate = publishedDate,
                                            isLoading = content is ReaderState.Loading,
                                            scrollState = scrollState,
                                            listState = listState,
                                            onImageClick = { imgUrl, altText ->
                                                currentImageData = ImageData(imgUrl, altText)
                                                showFullScreenImageViewer = true
                                            },
                                            prepared = prepared,
                                            bridge = anchorBridge,
                                        )
                                        PullToLoadIndicator(
                                            state = state,
                                            canLoadPrevious = isPreviousArticleAvailable,
                                            canLoadNext = isNextArticleAvailable,
                                        )
                                    }
                                }
                            }
                    }
                }
                // Bottom Bar
                if (readerState.articleId != null) {
                    val chipPoint = returnPoint
                    val returnToReadingChip: (@Composable () -> Unit)? =
                        if (chipPoint == null) {
                            null
                        } else {
                            {
                                ReturnToReadingChip(
                                    label = chipPoint.label,
                                    onClick = { returnToReading() },
                                )
                            }
                        }

                    BottomBar(
                        isShow = isShowToolBar,
                        isUnread = readingUiState.isUnread,
                        isStarred = readingUiState.isStarred,
                        isNextArticleAvailable = isNextArticleAvailable,
                        isFullContent =
                            readerState.content is ReaderState.FullContent ||
                                readerState.content is ReaderState.Error,
                        isBoldCharacters = boldCharacters.value,
                        isTocAvailable = isTocAvailable,
                        leadingContent = returnToReadingChip,
                        onUnread = { viewModel.updateReadStatus(it) },
                        onStarred = { viewModel.updateStarredStatus(it) },
                        onNextArticle = {
                            readerState.nextArticle?.let {
                                val (id, index) = it
                                onLoadArticle(id, index)
                            }
                        },
                        onFullContent = {
                            if (it) viewModel.renderFullContent()
                            else viewModel.renderDescriptionContent()
                        },
                        onBoldCharacters = { (!boldCharacters).put(context, coroutineScope) },
                        onToc = { showToc = true },
                        onReadAloud = {
                            viewModel.textToSpeechManager.readHtml(
                                readerState.content.text ?: return@BottomBar
                            )
                        },
                        ttsButton = {
                            TtsButton(
                                onClick = {
                                    when (it) {
                                        TextToSpeechManager.State.Error -> {
                                            context.showToast("TextToSpeech initialization failed")
                                        }

                                        TextToSpeechManager.State.Idle -> {
                                            viewModel.textToSpeechManager.readHtml(
                                                readerState.content.text ?: ""
                                            )
                                        }

                                        is TextToSpeechManager.State.Reading -> {
                                            viewModel.textToSpeechManager.stop()
                                        }

                                        TextToSpeechManager.State.Preparing -> {
                                            /* no-op */
                                        }
                                    }
                                },
                                state =
                                    viewModel.textToSpeechManager.stateFlow.collectAsStateValue(),
                            )
                        },
                    )
                }
            }
        },
    )
    if (showToc && isTocAvailable) {
        TocSheet(
            items = tocItems,
            onSelect = { item ->
                showToc = false
                jumpToTocItem(item)
            },
            onDismissRequest = { showToc = false },
        )
    }
    if (showFullScreenImageViewer) {

        ReaderImageViewer(
            imageData = currentImageData,
            onDownloadImage = {
                viewModel.downloadImage(
                    it,
                    onSuccess = { context.showToast(context.getString(R.string.image_saved)) },
                    onFailure = {
                        // FIXME: crash the app for error report
                        th ->
                        throw th
                    },
                )
            },
            onDismissRequest = { showFullScreenImageViewer = false },
        )
    }
}
