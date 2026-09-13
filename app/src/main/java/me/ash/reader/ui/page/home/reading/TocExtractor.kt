package me.ash.reader.ui.page.home.reading

import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/**
 * 目录项。
 *
 * @param id 锚点 id —— 源侧提供的（`sec-1` / `notes-vocab` …），或客户端补的 `ry-toc-N`。
 * @param level 标题层级，2 = `h2`（顶格）、3 = `h3`（缩进）。
 * @param text 目录显示文字。
 */
data class TocItem(
    val id: String,
    val level: Int,
    val text: String,
)

/**
 * 预处理结果。
 *
 * @param html 补全 `id` 之后的 HTML，交由渲染器（WebView / 原生组件）使用。
 * @param items 目录项，按文档顺序。**少于 2 项时为空列表**（此时 `html` 与输入完全一致）。
 */
data class PreparedContent(
    val html: String,
    val items: List<TocItem>,
)

/**
 * 单点内容预处理：一次 Jsoup 解析，同时产出「补过 id 的 HTML」与「目录项列表」。
 *
 * 纯 Kotlin + Jsoup，无 Android 依赖，可单元测试。
 *
 * 识别策略（渐进增强，源侧改格式前后都能工作）：
 * 1. **真标题**（`h1`–`h6`）—— 源侧升级格式后走这条，确定性；
 * 2. **骨架信号**（样式约定，完全可靠）—— `border-left:3px solid #9ca3af` 容器内的首行
 *    → `overview`；`font-size:12px` → `notes`；`font-weight:600` → 笔记三件套；
 * 3. **启发式**（老文章兜底）—— 位于 `<hr>` 之前的 PLAIN `<p>`、长度 ≤ 60、无句末标点
 *    → 升级为 `<h2>` 并补 id。
 *
 * 安全性：**目录项少于 [MIN_TOC_ITEMS] 时原样返回输入 HTML**，因此没有可用目录的文章
 * （例如 IT 之家那类源）渲染结果与改动前逐字节一致。
 */
object TocExtractor {

    /** 启发式标题的最大字符数。 */
    private const val MAX_TITLE_LENGTH = 60

    /** 少于该数量的目录项视为「无目录」，不做任何 DOM 改动。 */
    const val MIN_TOC_ITEMS = 2

    private const val AUTO_ID_PREFIX = "ry-toc-"
    private const val OVERVIEW_ID = "overview"
    private const val NOTES_ID = "notes"
    private const val NOTES_SECTION_PREFIX = "notes-item-"

    /** 归一化（去空格 + 小写）之后的样式片段。 */
    private const val OVERVIEW_CONTAINER_STYLE = "border-left:3pxsolid#9ca3af"
    private const val NOTES_LABEL_STYLE = "font-size:12px"
    private const val NOTES_SECTION_STYLE = "font-weight:600"

    private val headingTags = setOf("h1", "h2", "h3", "h4", "h5", "h6")
    private val mediaTags = setOf("img", "iframe", "video", "figure", "table", "hr")

    /** 句末标点：出现即认为是正文句子，不是标题。 */
    private val sentenceEndings = charArrayOf('.', '!', '?', '。', '！', '？', '…', '．')

    /** 明显属于句子中段的标点：出现即认为是正文片段。 */
    private val clauseEndings = charArrayOf(',', '，', ';', '；', '、', ':')

    /** 安全的锚点 id 字符集（与源侧格式规范的 `[a-z0-9-]` 约定一致）。 */
    private val safeId = Regex("[A-Za-z0-9_-]+")

    /** 笔记三件套的语义 id —— 按标题文字匹配，跨文章稳定。 */
    private val notesSectionIds =
        listOf(
            "notes-vocab" to listOf("重点词汇", "词汇"),
            "notes-phrases" to listOf("值得积累", "表达"),
            "notes-sentences" to listOf("长难句", "句子"),
        )

    fun prepare(rawHtml: String): PreparedContent {
        if (rawHtml.isBlank()) return PreparedContent(rawHtml, emptyList())

        val document =
            runCatching { Jsoup.parseBodyFragment(rawHtml) }.getOrNull()
                ?: return PreparedContent(rawHtml, emptyList())
        // Jsoup 默认 prettyPrint = true，会在块级元素的文本外插入换行与缩进，
        // 改写后的 HTML 会因此多出空白（内联元素之间尤其危险）。必须关掉。
        document.outputSettings().prettyPrint(false)
        val body = document.body()
        val elements = body.getAllElements()
        if (elements.size <= 1) return PreparedContent(rawHtml, emptyList())

        val separatorIndex = elements.indexOfFirst { it.tagName() == "hr" }
        val overviewLabel = findOverviewLabel(body)
        // 骨架信号：只有「背景导读容器 / 精读笔记标签」这类双语源特征出现时，才允许把正文
        // `<p>` 升级成标题。第三方源一律只读不写 —— 真标题照样进目录，但绝不改写 DOM。
        val hasSkeleton =
            overviewLabel != null ||
                body.select("p").any {
                    it.hasStyle(NOTES_LABEL_STYLE) || it.hasStyle(NOTES_SECTION_STYLE)
                }

        val items = mutableListOf<TocItem>()
        val usedIds = mutableSetOf<String>()
        var autoId = 0
        var notesItemCount = 0

        /** 是否改动过 DOM（补 id / 换标签）。源侧已输出真锚点时保持 false。 */
        var modified = false

        fun claimId(element: Element, preferred: String?): String {
            if (preferred != null && safeId.matches(preferred) && usedIds.add(preferred)) {
                // preferred 可能是「语义 id」（overview / notes / notes-vocab …）而不是元素自带的 id。
                // 必须写回 DOM —— 否则 WebView 侧 getElementById 查不到，跳转会静默失败；
                // 源侧已给真 id 时 element.id() == preferred，这里不动 DOM，modified 保持 false。
                if (element.id() != preferred) {
                    element.attr("id", preferred)
                    modified = true
                }
                return preferred
            }
            var candidate: String
            do {
                autoId += 1
                candidate = "$AUTO_ID_PREFIX$autoId"
            } while (!usedIds.add(candidate))
            element.attr("id", candidate)
            modified = true
            return candidate
        }

        elements.forEachIndexed { index, element ->
            val tag = element.tagName()
            when {
                tag in headingTags -> {
                    val preferred =
                        element.id().takeIf { it.isNotBlank() }
                            ?: if (element === overviewLabel) OVERVIEW_ID else null
                    val id = claimId(element, preferred)
                    items += TocItem(id = id, level = tag[1].digitToInt(), text = element.text().trim())
                }

                tag != "p" -> Unit

                element === overviewLabel -> {
                    val id = claimId(element, OVERVIEW_ID)
                    element.tagName("h2")
                    modified = true
                    items += TocItem(id = id, level = 2, text = element.text().trim())
                }

                element.hasStyle(NOTES_LABEL_STYLE) -> {
                    val id = claimId(element, NOTES_ID)
                    element.tagName("h2")
                    modified = true
                    items += TocItem(id = id, level = 2, text = element.text().trim())
                }

                element.hasStyle(NOTES_SECTION_STYLE) -> {
                    val text = element.text().trim()
                    notesItemCount += 1
                    val preferred = notesSectionId(text) ?: "$NOTES_SECTION_PREFIX$notesItemCount"
                    val id = claimId(element, preferred)
                    element.tagName("h3")
                    modified = true
                    items += TocItem(id = id, level = 3, text = text)
                }

                hasSkeleton &&
                    (separatorIndex == -1 || index < separatorIndex) &&
                    element.isPlainParagraph() &&
                    element.isTitleLike() -> {
                    val id = claimId(element, null)
                    element.tagName("h2")
                    modified = true
                    items += TocItem(id = id, level = 2, text = element.text().trim())
                }
            }
        }

        if (items.size < MIN_TOC_ITEMS) return PreparedContent(rawHtml, emptyList())
        // 源侧（格式规范 v2）已经输出真标题与真 id 时不做任何改写 → 直接返回原文，
        // 既避免 Jsoup 重新序列化，也免掉 WebView 的一次多余 reload。
        return PreparedContent(html = if (modified) body.html() else rawHtml, items = items)
    }

    /** 背景导读容器（`border-left:3px solid #9ca3af`）内的首个标题行。 */
    private fun findOverviewLabel(body: Element): Element? =
        body.select("div")
            .firstOrNull { it.hasStyle(OVERVIEW_CONTAINER_STYLE) }
            ?.selectFirst("h1, h2, h3, h4, h5, h6, p")

    /** 归一化样式，抹平 `font-weight:600` 与 `font-weight: 600` 的差异。 */
    private fun Element.normalizedStyle(): String = attr("style").replace(" ", "").lowercase()

    private fun Element.hasStyle(fragment: String): Boolean =
        normalizedStyle().contains(fragment)

    private fun Element.isPlainParagraph(): Boolean =
        attr("style").isBlank() &&
            !hasClass("readability-styled") &&
            children().none { it.tagName() in mediaTags }

    private fun Element.isTitleLike(): Boolean {
        val text = text().trim()
        if (text.length < 2 || text.length > MAX_TITLE_LENGTH) return false
        if (!text.any { it.isLetter() }) return false
        if (text.last() in sentenceEndings) return false
        if (text.last() in clauseEndings) return false
        return true
    }

    private fun notesSectionId(text: String): String? =
        notesSectionIds.firstOrNull { (_, keywords) -> keywords.any { text.contains(it) } }?.first
}
