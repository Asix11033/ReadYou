package me.ash.reader.ui.page.home.reading

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `TocExtractor` 的单元测试。
 *
 * 这一层是纯 Kotlin（Jsoup）+ 无 Android 依赖，是整个方案里唯一可以脱离设备验证的部分。
 */
class TocExtractorTest {

    /** 源侧升级到 v2 格式之后：真标题 + 现成 id，客户端只需原样识别。 */
    private val v2Article =
        """
        <p style="color:#6b7280">The Atlantic · 科技 · 进阶 · 原文链接</p>
        <div style="border-left:3px solid #9ca3af;padding-left:12px;margin:0 0 16px">
          <h2 id="overview" style="margin:0 0 10px;font-size:15px;font-weight:600;color:#6b7280">背景导读</h2>
          <p>本文讨论了一场每隔几年就会重现的争论，并给出证据层面的判断。</p>
        </div>
        <h2 id="sec-1">Why the debate keeps resurfacing</h2>
        <p style="color:#6b7280">为什么这场争论反复出现</p>
        <p>The argument has returned every few years for a decade.</p>
        <h2 id="sec-2">What the evidence actually shows</h2>
        <p style="color:#6b7280">证据实际说明了什么</p>
        <p>Across twelve studies, the effect size is modest.</p>
        <hr id="notes-sep">
        <h2 id="notes" style="margin:0 0 16px;color:#6b7280;font-size:12px;letter-spacing:.08em">精读笔记 · STUDY NOTES</h2>
        <h3 id="notes-vocab" style="margin:0 0 10px;font-weight:600">重点词汇（2）</h3>
        <ol><li><strong>resurface</strong> · v. · 再次出现</li></ol>
        <h3 id="notes-phrases" style="margin:0 0 10px;font-weight:600">值得积累的表达（1）</h3>
        <ol><li>every few years —— 每隔几年</li></ol>
        <h3 id="notes-sentences" style="margin:0 0 10px;font-weight:600">长难句解析（1）</h3>
        <ol><li>Across twelve studies, the effect size is modest.</li></ol>
        """
            .trimIndent()

    /** 现状（v1）格式：小节标题被压平成 PLAIN `<p>`，骨架靠样式约定识别。 */
    private val v1Article =
        """
        <p style="color:#6b7280">The Atlantic · 科技 · 进阶 · 原文链接</p>
        <div style="border-left:3px solid #9ca3af;padding-left:12px;margin:0 0 16px">
          <p style="color:#6b7280">背景导读</p>
          <p>本文讨论了一场每隔几年就会重现的争论，并给出证据层面的判断。</p>
        </div>
        <p>Raw LLM predictions are biased, not just noisy</p>
        <p>We empirically evaluated the promise of LLM-based A/B testing across a range of tasks and datasets.</p>
        <p style="color:#6b7280">原始的 LLM 预测是有偏的，而不只是有噪声。</p>
        <p>Two conditions make LLM outputs valid surrogates</p>
        <p>Across twelve studies drawn from published benchmark results, the effect size is modest at best.</p>
        <p style="color:#6b7280">在十二项来自已发表基准结果的研究中，效应量最好也不过是温和的。</p>
        <hr>
        <p style="margin:0 0 16px;color:#6b7280;font-size:12px;letter-spacing:.08em">精读笔记 · STUDY NOTES</p>
        <p style="margin:0 0 10px;font-weight:600">重点词汇（7）</p>
        <ol><li><strong>surrogate</strong> · n. · 替代物</li></ol>
        <p style="margin:0 0 10px;font-weight:600">值得积累的表达（6）</p>
        <ol><li>a range of —— 一系列</li></ol>
        <p style="margin:0 0 10px;font-weight:600">长难句解析（3）</p>
        <ol><li>Across twelve studies, the effect size is modest.</li></ol>
        """
            .trimIndent()

    @Test
    fun `v2 formatted article keeps source ids`() {
        val prepared = TocExtractor.prepare(v2Article)

        assertEquals(
            listOf(
                "overview" to 2,
                "sec-1" to 2,
                "sec-2" to 2,
                "notes" to 2,
                "notes-vocab" to 3,
                "notes-phrases" to 3,
                "notes-sentences" to 3,
            ),
            prepared.items.map { it.id to it.level },
        )
        assertEquals("背景导读", prepared.items.first().text)
        // 源侧已经给了真标题 + 真 id，客户端不做任何改写 → HTML 逐字节原样返回
        assertFalse(prepared.html.contains("ry-toc-"))
        assertEquals(v2Article, prepared.html)
    }

    @Test
    fun `v1 formatted article recovers skeleton and flattened section titles`() {
        val prepared = TocExtractor.prepare(v1Article)

        assertEquals(
            listOf(
                "overview" to 2,
                "ry-toc-1" to 2,
                "ry-toc-2" to 2,
                "notes" to 2,
                "notes-vocab" to 3,
                "notes-phrases" to 3,
                "notes-sentences" to 3,
            ),
            prepared.items.map { it.id to it.level },
        )
        assertEquals(
            listOf(
                "背景导读",
                "Raw LLM predictions are biased, not just noisy",
                "Two conditions make LLM outputs valid surrogates",
                "精读笔记 · STUDY NOTES",
                "重点词汇（7）",
                "值得积累的表达（6）",
                "长难句解析（3）",
            ),
            prepared.items.map { it.text },
        )
        // 被压平的标题要升级成真 h2，并把中文对照段留在原地
        assertTrue(
            prepared.html.contains(
                "<h2 id=\"ry-toc-1\">Raw LLM predictions are biased, not just noisy</h2>"
            )
        )
    }

    @Test
    fun `semantic ids are written back into the html`() {
        // 目录项用的是语义 id（overview / notes / notes-vocab …），DOM 上必须真的带上这些 id，
        // 否则 WebView 侧 getElementById 查不到，目录跳转会静默失败。
        val prepared = TocExtractor.prepare(v1Article)

        // 注意：Jsoup 的属性保持插入顺序，元素原有的 style 在先、补的 id 在后，
        // 所以断言不能写成 "<h2 id=\"notes\""，只能用「标签 + 任意属性 + id」匹配。
        listOf("overview", "notes", "notes-vocab", "notes-phrases", "notes-sentences")
            .forEach { id ->
                assertTrue("missing id=\"$id\" in html", prepared.html.contains("id=\"$id\""))
            }
        assertTrue(
            Regex("""<h2\b[^>]*\bid="notes"""").containsMatchIn(prepared.html)
        )
        assertTrue(
            Regex("""<h3\b[^>]*\bid="notes-vocab"""").containsMatchIn(prepared.html)
        )
    }

    @Test
    fun `plain paragraphs of foreign feeds are never rewritten`() {
        // 没有骨架信号（背景导读容器 / 精读笔记标签）的第三方源：只读取真标题，
        // 绝不把正文 <p> 升级成 <h2> —— 否则跨源会出现视觉回归。
        val raw =
            """
            <p>A short line without any skeleton signal at all</p>
            <p>Another short line that would match the heuristic</p>
            <p>This is a long enough paragraph that it is clearly body text, not a heading.</p>
            """
                .trimIndent()

        val prepared = TocExtractor.prepare(raw)

        assertTrue(prepared.items.isEmpty())
        assertEquals(raw, prepared.html)
    }

    @Test
    fun `v2 article without body sections still yields the five skeleton items`() {
        // 生成器侧实测：多数文章原文就没有小节标题，骨架 = overview + notes + 3×h3 = 5 个标题。
        // 客户端不能按「≥7 个标题」做断言，也不能因为缺 sec-N 就判成「无目录」。
        val raw =
            """
            <div style="border-left:3px solid #9ca3af"><h2 id="overview">背景导读</h2><p>只有一段中文导读。</p></div>
            <p>The first English paragraph of a flowing essay, comfortably longer than any heuristic title limit.</p>
            <p style="color:#6b7280">这是一段中文对照。</p>
            <hr id="notes-sep">
            <h2 id="notes" style="color:#6b7280;font-size:12px;font-weight:400">精读笔记 · STUDY NOTES</h2>
            <h3 id="notes-vocab" style="font-weight:600">重点词汇（7）</h3>
            <ol><li>a</li></ol>
            <h3 id="notes-phrases" style="font-weight:600">值得积累的表达（6）</h3>
            <ol><li>b</li></ol>
            <h3 id="notes-sentences" style="font-weight:600">长难句解析（3）</h3>
            <ol><li>c</li></ol>
            """
                .trimIndent()

        val prepared = TocExtractor.prepare(raw)

        assertEquals(
            listOf("overview", "notes", "notes-vocab", "notes-phrases", "notes-sentences"),
            prepared.items.map { it.id },
        )
        assertEquals(raw, prepared.html)
    }

    @Test
    fun `article without any structure is returned byte for byte`() {
        val raw =
            """
            <p>This is an ordinary article whose first paragraph is long enough that it cannot possibly be mistaken for a heading.</p>
            <p><img src="https://example.com/a.png" alt="a" /></p>
            <p>Another ordinary paragraph, also comfortably longer than the sixty character heuristic limit.</p>
            """
                .trimIndent()

        val prepared = TocExtractor.prepare(raw)

        assertTrue(prepared.items.isEmpty())
        assertEquals(raw, prepared.html)
    }

    @Test
    fun `fewer than two items leaves the html untouched`() {
        val raw =
            """
            <div style="border-left:3px solid #9ca3af"><p style="color:#6b7280">背景导读</p><p>只有一行导读，没有任何小节标题的一篇短文。</p></div>
            <p>这是一个足够长的正文段落，不会命中启发式规则，因此整篇只有 1 个目录项。</p>
            """
                .trimIndent()

        val prepared = TocExtractor.prepare(raw)

        assertTrue(prepared.items.isEmpty())
        assertEquals(raw, prepared.html)
    }

    @Test
    fun `sentence ending with a period is not treated as a title`() {
        val prepared =
            TocExtractor.prepare(
                """
                <div style="border-left:3px solid #9ca3af"><p style="color:#6b7280">背景导读</p></div>
                <p>Identification then holds only by assumption.</p>
                <p>Key Takeaways</p>
                """
                    .trimIndent()
            )

        assertEquals(listOf("背景导读", "Key Takeaways"), prepared.items.map { it.text })
        assertFalse(prepared.items.any { it.text.endsWith(".") })
    }

    @Test
    fun `ids are deterministic across runs`() {
        val first = TocExtractor.prepare(v1Article)
        val second = TocExtractor.prepare(v1Article)

        assertEquals(first.items, second.items)
        assertEquals(first.html, second.html)
    }

    @Test
    fun `ids never collide`() {
        val prepared =
            TocExtractor.prepare(
                """
                <h2 id="notes">Not the notes label (source used the semantic id itself)</h2>
                <h2 id="sec-1">Section</h2>
                <p style="margin:0;color:#6b7280;font-size:12px">精读笔记 · STUDY NOTES</p>
                """
                    .trimIndent()
            )

        assertEquals(3, prepared.items.size)
        assertEquals(prepared.items.size, prepared.items.map { it.id }.distinct().size)
    }

    @Test
    fun `blank or plain text input is handled gracefully`() {
        assertEquals(PreparedContent("", emptyList()), TocExtractor.prepare(""))
        val textOnly = "no html at all"
        assertEquals(PreparedContent(textOnly, emptyList()), TocExtractor.prepare(textOnly))
    }
}
