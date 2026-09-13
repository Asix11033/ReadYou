package me.ash.reader.ui.page.home.reading

import android.webkit.WebView
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import java.util.Date
import me.ash.reader.infrastructure.preference.LocalReadingRenderer
import me.ash.reader.infrastructure.preference.LocalReadingSubheadUpperCase
import me.ash.reader.infrastructure.preference.ReadingRendererPreference
import me.ash.reader.ui.component.reader.LocalTextContentWidth
import me.ash.reader.ui.component.reader.Reader
import me.ash.reader.ui.component.scrollbar.drawVerticalScrollIndicator
import me.ash.reader.ui.component.webview.RYWebView
import me.ash.reader.ui.ext.extractDomain
import me.ash.reader.ui.ext.roundClick

/**
 * 原生渲染器下，`LazyColumn` 的第 0 项是 header item
 * （`Spacer(64dp) + top padding + headline`），`Reader(...)` 的 items 从 1 开始。
 */
private const val HEADER_ITEM_OFFSET = 1

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun Content(
    modifier: Modifier = Modifier,
    content: String,
    feedName: String,
    title: String,
    author: String? = null,
    link: String? = null,
    publishedDate: Date,
    scrollState: ScrollState,
    listState: LazyListState,
    isLoading: Boolean,
    contentPadding: PaddingValues = PaddingValues(),
    onImageClick: ((imgUrl: String, altText: String) -> Unit)? = null,
    /** 预处理结果（补过 id 的 HTML + 目录项）。为 null 时退化为原始 [content]，行为与改动前一致。 */
    prepared: PreparedContent? = null,
    /** 锚点桥：由本组件在组合期间填充 WebView 引用、headline 实测高度与锚点索引表。 */
    bridge: ReaderAnchorBridge? = null,
) {
    val context = LocalContext.current
    val subheadUpperCase = LocalReadingSubheadUpperCase.current
    val renderer = LocalReadingRenderer.current

    val textContentWidth = LocalTextContentWidth.current
    val maxWidthModifier = Modifier.widthIn(max = textContentWidth)
    val uriHandler = LocalUriHandler.current

    // 只有目录可用（≥ 2 项）时才启用锚点：否则渲染路径与改动前逐字节一致。
    val tocAvailable = (prepared?.items?.size ?: 0) >= TocExtractor.MIN_TOC_ITEMS
    val html = prepared?.html?.takeIf { tocAvailable } ?: content

    // 回调必须用 remember 固定实例：每次重组都换 lambda 会让 AndroidView 反复 reload 正文
    val onWebViewReady: (WebView) -> Unit =
        remember(bridge) { { webView: WebView -> bridge?.webView = webView } }
    val onHeadlineSizeChanged: (IntSize) -> Unit =
        remember(bridge) { { size: IntSize -> bridge?.headlineHeightPx = size.height } }
    val onViewportSizeChanged: (IntSize) -> Unit =
        remember(bridge) { { size: IntSize -> bridge?.viewportHeightPx = size.height } }
    // 锚点回调：类型显式声明 + remember，避免 lambda 每帧换实例
    val anchorSink: ((id: String, itemIndex: Int) -> Unit)? =
        remember(tocAvailable, bridge) {
            if (tocAvailable) {
                { id: String, itemIndex: Int ->
                    bridge?.anchorIndices?.set(id, itemIndex + HEADER_ITEM_OFFSET)
                }
            } else {
                null
            }
        }

    val headline =
        @Composable {
            Column(
                modifier =
                    Modifier.then(maxWidthModifier)
                        .padding(horizontal = 12.dp)
                        // headline 高度取决于标题行数与元信息换行，必须实测，不能硬编码
                        .onSizeChanged(onHeadlineSizeChanged)
            ) {
                DisableSelection {
                    Metadata(
                        feedName = feedName,
                        title = title,
                        author = author,
                        publishedDate = publishedDate,
                        modifier = Modifier.roundClick { link?.let { uriHandler.openUri(it) } },
                    )
                }
            }
        }

    if (isLoading) {
        Column { LoadingIndicator(modifier = Modifier.size(56.dp)) }
    } else {

        when (renderer) {
            ReadingRendererPreference.WebView -> {
                Column(
                    modifier =
                        modifier
                            .padding(top = contentPadding.calculateTopPadding())
                            .fillMaxSize()
                            .drawVerticalScrollIndicator(scrollState)
                ) {
                    Column(
                        modifier =
                            Modifier.fillMaxSize()
                                .onSizeChanged(onViewportSizeChanged)
                                .verticalScroll(scrollState),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Column(modifier = Modifier.then(maxWidthModifier)) {
                            // Top bar height
                            Spacer(modifier = Modifier.height(64.dp))
                            // padding
                            headline()

                            RYWebView(
                                modifier = Modifier.fillMaxSize(),
                                content = html,
                                refererDomain = link.extractDomain(),
                                onImageClick = onImageClick,
                                onWebViewReady = onWebViewReady,
                            )
                            Spacer(modifier = Modifier.height(128.dp))
                            Spacer(
                                modifier = Modifier.height(contentPadding.calculateBottomPadding())
                            )
                        }
                    }
                }
            }

            ReadingRendererPreference.NativeComponent -> {
                SelectionContainer {
                    LazyColumn(
                        modifier = modifier.fillMaxSize().drawVerticalScrollIndicator(listState),
                        state = listState,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        // 重建锚点索引表（每次构建 item 列表都会重新跑一遍，保证与 item 一一对应）
                        if (tocAvailable) bridge?.anchorIndices?.clear()

                        item {
                            // Top bar height
                            Spacer(modifier = Modifier.height(64.dp))
                            // padding
                            Spacer(modifier = Modifier.height(contentPadding.calculateTopPadding()))
                            headline()
                        }

                        Reader(
                            context = context,
                            subheadUpperCase = subheadUpperCase.value,
                            link = link ?: "",
                            content = html,
                            onImageClick = onImageClick,
                            onLinkClick = { uriHandler.openUri(it) },
                            anchorSink = anchorSink,
                        )

                        item {
                            Spacer(modifier = Modifier.height(128.dp))
                            Spacer(
                                modifier = Modifier.height(contentPadding.calculateBottomPadding())
                            )
                        }
                    }
                }
            }
        }
    }
}
