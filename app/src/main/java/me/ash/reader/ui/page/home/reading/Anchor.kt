package me.ash.reader.ui.page.home.reading

import android.webkit.WebView
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import kotlin.math.abs
import kotlin.math.roundToInt
import me.ash.reader.infrastructure.preference.ReadingRendererPreference
import me.ash.reader.ui.component.webview.anchorAt
import me.ash.reader.ui.component.webview.anchorOffsetTop
import me.ash.reader.ui.component.webview.elementOffsetTop

/**
 * 「锚点」——目录跳转目标、返回点、持久阅读位置三者的共用抽象。
 *
 * @param id 锚点 id（源侧 `<h* id>`，或客户端补的 `ry-toc-N`）；空串表示没有可用锚点。
 * @param itemIndex 原生渲染器：LazyColumn 项序号（WebView 路径为 -1）。
 * @param offset 原生：项内偏移；WebView：`scrollState` 像素偏移。
 * @param ratio 通用兜底比例（`value / maxValue`）。
 * @param label 用于展示「返回到 …」的落点预览文字。
 * @param anchorOffset 捕获时刻该锚点在 WebView 文档内的偏移，用于补偿图片异步加载造成的漂移。
 */
data class Anchor(
    val id: String = "",
    val itemIndex: Int = -1,
    val offset: Int = 0,
    val ratio: Float = 0f,
    val label: String = "",
    val anchorOffset: Int = -1,
)

/**
 * 滚动意图状态机。
 *
 * 上层（目录跳转 / 返回 / 回顶 / 位置恢复）在发起程序化滚动前置为 [Programmatic]，
 * 滚动结束（**含取消路径**）复位为 [Idle]。它的唯一消费者是「阅读位置记忆」——程序化滚动
 * 绝不写入记忆，否则删掉「跳到生词表 → 退出 → 重开」就会直接落在生词表。
 *
 * 注意不要把「用户滚动」做成正向判断：惯性 fling 走 `onPreFling`、不经过 `onPreScroll`，
 * 正向识别会漏判。正确做法是**反向排除**（默认写入，仅在 Programmatic 窗口内跳过）。
 */
enum class ScrollIntent {
    Idle,
    Programmatic,
}

/**
 * 统一锚点解析接口。上层只依赖它，不感知渲染器差异。
 *
 * 两个实现：WebView 路径（JS 取 `offsetTop` + 坐标补偿）、原生组件路径（锚点索引表）。
 */
interface AnchorResolver {

    /** 跳转到锚点；内部按「距离 > 2 × 视口高度」阈值决定动画还是瞬时。 */
    suspend fun scrollTo(id: String)

    /**
     * 捕获当前位置为返回点/阅读位置。
     *
     * 声明为 `suspend` 是因为 WebView 路径需要用 JS 反查「当前视口顶部之上最近的锚点」以
     * 生成落点预览文字。
     */
    suspend fun captureCurrent(): Anchor?

    /** 恢复到锚点。 */
    suspend fun restore(anchor: Anchor)
}

/** 长距离跳转的阈值倍数：超过「2 × 视口高度」时改用瞬时跳转，避免十几屏的高速滚动动画。 */
private const val LONG_JUMP_VIEWPORT_MULTIPLIER = 2

/** 估算列表项平均高度时用的兜底值（视口内没有可见项时）。 */
private const val FALLBACK_ITEM_HEIGHT_PX = 400

/** WebView 之前固定垫高的 `Spacer(64.dp)`。 */
private const val TOP_BAR_SPACER_DP = 64

/**
 * 承载两条渲染路径定位状态的桥。
 *
 * 由 `ReadingPage` 持有（`remember`，普通可变字段，不参与重组），`Content` 在组合期间填充；
 * 用户事件发生在组合完成之后，因此读取时数据一定是最新的。
 */
class ReaderAnchorBridge {

    var renderer: ReadingRendererPreference = ReadingRendererPreference.WebView

    /** WebView 路径：正文 WebView 实例（由 `RYWebView` 的 `onWebViewReady` 注入）。 */
    var webView: WebView? = null

    /** WebView 路径：`headline`（标题+元信息）实测高度，随标题行数变化，不能硬编码。 */
    var headlineHeightPx: Int = 0

    /** WebView 路径：滚动视口高度（由 `Content` 的滚动容器实测），用于长距离跳转阈值。 */
    var viewportHeightPx: Int = 0

    var scrollState: ScrollState? = null

    var listState: LazyListState? = null

    /** 原生路径：锚点 id → 本次 LazyListScope 内的项序号（不含 header item）。 */
    val anchorIndices: MutableMap<String, Int> = mutableMapOf()

    /** 目录项，用于落点预览文字。 */
    var items: List<TocItem> = emptyList()

    fun attach(scrollState: ScrollState, listState: LazyListState) {
        this.scrollState = scrollState
        this.listState = listState
    }

    fun labelOf(id: String): String = items.firstOrNull { it.id == id }?.text.orEmpty()

    /** 按当前渲染器给出解析器；状态尚未就绪时返回 null（调用方静默跳过）。 */
    fun resolver(): AnchorResolver? =
        when (renderer) {
            ReadingRendererPreference.WebView ->
                webView?.let { webView -> scrollState?.let { WebViewAnchorResolver(webView, it, this) } }

            ReadingRendererPreference.NativeComponent ->
                listState?.let { LazyListAnchorResolver(it, this) }
        }
}

/**
 * WebView 路径的实现。
 *
 * 坐标换算：`scrollState` 的坐标系包含 WebView 之前的 `Spacer(64.dp)` 与动态高度的 headline，
 * 所以目标偏移 = 64dp + headline 实测高度 + 元素在文档内的偏移。
 */
private class WebViewAnchorResolver(
    private val webView: WebView,
    private val scrollState: ScrollState,
    private val bridge: ReaderAnchorBridge,
) : AnchorResolver {

    /** dp → px 用 WebView 自身的 density，避免非 Composable 环境取不到 LocalDensity。 */
    private val topBarSpacerPx: Int
        get() = (TOP_BAR_SPACER_DP * webView.resources.displayMetrics.density).roundToInt()

    private val contentTopPx: Int
        get() = topBarSpacerPx + bridge.headlineHeightPx

    override suspend fun scrollTo(id: String) {
        val documentOffset = webView.anchorOffsetTop(id) ?: return
        jumpTo(contentTopPx + documentOffset)
    }

    override suspend fun scrollToTop() {
        jumpTo(0)
    }

    override suspend fun captureCurrent(): Anchor? {
        val current = scrollState.value
        val max = scrollState.maxValue
        // 视口顶部在文档坐标系里的位置
        val documentY = (current - contentTopPx).coerceAtLeast(0)
        val hit = webView.anchorAt(documentY)
        return Anchor(
            id = hit?.first.orEmpty(),
            itemIndex = -1,
            offset = current,
            ratio = if (max > 0) current.toFloat() / max else 0f,
            label = hit?.first?.let { bridge.labelOf(it) }.orEmpty(),
            anchorOffset = hit?.second ?: -1,
        )
    }

    override suspend fun restore(anchor: Anchor) {
        val max = scrollState.maxValue
        var target = anchor.offset
        // 补偿图片加载：锚点现在的位置相对捕获时刻的位移，直接加到原偏移上
        if (anchor.id.isNotBlank() && anchor.anchorOffset >= 0) {
            webView.elementOffsetTop(anchor.id)?.let { now -> target += now - anchor.anchorOffset }
        }
        if (target <= 0 || max <= 0) {
            target = (anchor.ratio * max).roundToInt()
        }
        jumpTo(target.coerceIn(0, max.coerceAtLeast(0)))
    }

    private suspend fun jumpTo(target: Int) {
        // 视口高度优先取滚动容器实测值，实测还没回填时退回 ScrollState 的自报值
        val viewport = bridge.viewportHeightPx.takeIf { it > 0 } ?: scrollState.viewportSize
        val distance = abs(target - scrollState.value)
        if (viewport > 0 && distance > LONG_JUMP_VIEWPORT_MULTIPLIER * viewport) {
            scrollState.scrollTo(target)
        } else {
            scrollState.animateScrollTo(target)
        }
    }
}

/**
 * 原生组件渲染器的实现。
 *
 * 第 0 项是 header item（`Spacer(64dp) + top padding + headline`），`Reader(...)` 的 items 从 1
 * 开始，所以由 `Content` 在写入索引表时统一 +1（见 `HEADER_ITEM_OFFSET`）。
 */
private class LazyListAnchorResolver(
    private val listState: LazyListState,
    private val bridge: ReaderAnchorBridge,
) : AnchorResolver {

    override suspend fun scrollTo(id: String) {
        val index = bridge.anchorIndices[id] ?: return
        jumpToItem(index)
    }

    override suspend fun scrollToTop() {
        jumpToItem(0)
    }

    override suspend fun captureCurrent(): Anchor? {
        val index = listState.firstVisibleItemIndex
        val offset = listState.firstVisibleItemScrollOffset
        val total = listState.layoutInfo.totalItemsCount
        val nearest =
            bridge.anchorIndices.entries.filter { it.value <= index }.maxByOrNull { it.value }
        return Anchor(
            id = nearest?.key.orEmpty(),
            itemIndex = index,
            offset = offset,
            ratio = if (total > 0) index.toFloat() / total else 0f,
            label = nearest?.key?.let { bridge.labelOf(it) }.orEmpty(),
        )
    }

    override suspend fun restore(anchor: Anchor) {
        val index =
            if (anchor.itemIndex >= 0) anchor.itemIndex
            else bridge.anchorIndices[anchor.id] ?: return
        val lastIndex = (listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0)
        listState.scrollToItem(index.coerceIn(0, lastIndex), anchor.offset.coerceAtLeast(0))
    }

    private suspend fun jumpToItem(index: Int) {
        val current = listState.firstVisibleItemIndex
        val distance = abs(index - current) * averageItemHeightPx()
        val layoutInfo = listState.layoutInfo
        val viewport = layoutInfo.viewportEndOffset - layoutInfo.viewportStartOffset
        if (viewport > 0 && distance > LONG_JUMP_VIEWPORT_MULTIPLIER * viewport) {
            listState.scrollToItem(index)
        } else {
            listState.animateScrollToItem(index)
        }
    }

    private fun averageItemHeightPx(): Int {
        val visible = listState.layoutInfo.visibleItemsInfo
        if (visible.isEmpty()) return FALLBACK_ITEM_HEIGHT_PX
        return (visible.sumOf { it.size } / visible.size).coerceAtLeast(1)
    }
}
