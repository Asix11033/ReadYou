package me.ash.reader.ui.component.webview

import android.webkit.WebView
import kotlin.coroutines.resume
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray

/**
 * WebView 路径的锚点查询桥。
 *
 * 背景：正文 WebView 以 UNSPECIFIED 高度整篇展开在 Compose 的 `verticalScroll` 里，
 * **WebView 自身不滚动**，所以 `element.scrollIntoView()` 没有任何视觉效果。可行路径是
 * `getElementById(id)` 取到元素相对文档顶部的像素值，再由 Kotlin 侧换算成 `scrollState` 的
 * 目标偏移（换算与补偿在 `WebViewAnchorResolver` 里）。
 *
 * ## 坐标系（**必读**：这里曾经是「目录跳转整体偏早」的根因）
 *
 * `getBoundingClientRect()` / `offsetTop` 返回的是 **CSS px**，而 Compose 的
 * `ScrollState.value` / `ScrollState.maxValue` 是 **物理 px**。ReadYou 的正文 WebView 以
 * `initial-scale=1, width=device-width` 布局，因此 **1 CSS px = `density` 个物理 px**。
 * 直接把 CSS px 当物理 px 用，误差与「离文档顶部的距离」成正比 ——
 * 第一项（`overview`，偏移≈0）看起来完全正确，其余项全部**落点偏早**（约差一个 density 倍率）。
 *
 * 本文件对外的所有偏移量**统一换算成物理 px**，调用方不需要知道 CSS px 的存在。
 *
 * 精度：`offsetTop` 在图片异步加载完成前偏小（图片撑高会把锚点往下推），因此查询前先等
 * 「元素出现 + 图片加载完成」，并设总超时兜底——超时也不会失败，只是给出当前的最优值。
 */

/** 等待元素出现与图片加载的总超时。 */
private const val ANCHOR_QUERY_TIMEOUT_MS = 2000L

/** 轮询间隔。 */
private const val POLL_INTERVAL_MS = 50L

/** id 只允许出现在单引号字符串里；非法字符直接拒绝，避免注入。 */
private val safeAnchorId = Regex("[A-Za-z0-9_-]+")

/** 取元素相对文档顶部的偏移（CSS px）；元素不存在返回 `null`。 */
private suspend fun WebView.elementOffsetCss(id: String): Int? {
    val value =
        evaluateJavascriptSync(
            "(function(){var e=document.getElementById('$id');if(!e)return null;" +
                "var r=e.getBoundingClientRect();return Math.round(r.top+window.scrollY);})()"
        )
    return value?.trim('"')?.toIntOrNull()
}

/** 文档总高度（CSS px）。 */
private suspend fun WebView.scrollHeightCss(): Int? =
    evaluateJavascriptSync("Math.round(document.documentElement.scrollHeight)")
        ?.trim('"')
        ?.toIntOrNull()

/**
 * CSS px → 物理 px 的换算系数。
 *
 * 优先用「WebView 实测高度 ÷ 文档 CSS 高度」反算（自校准，对任何非 1 的页面缩放同样成立）；
 * 反算不可用（View 尚未布局 / 文档为空）或明显失真时退回 `density`。
 *
 * 该值只依赖渲染结果，与滚动位置无关，因此每次查询重新取一次即可，无需缓存。
 */
suspend fun WebView.documentScalePx(): Float {
    val density = resources.displayMetrics.density
    val docHeight = scrollHeightCss() ?: return density
    val viewHeight = height
    if (docHeight <= 0 || viewHeight <= 0) return density
    val derived = viewHeight.toFloat() / docHeight
    // 只接受落在 density 附近的推导值：偏差过大说明布局还没完成，用 density 更稳
    return if (derived.isFinite() && derived > density * 0.5f && derived < density * 1.5f) {
        derived
    } else {
        density
    }
}

/**
 * 取元素相对文档顶部的像素偏移（**物理 px**，可直接与 `ScrollState.value` 相加）。
 *
 * @return 偏移值；元素不存在或超时返回 `null`。
 */
suspend fun WebView.anchorOffsetTop(id: String, timeoutMillis: Long = ANCHOR_QUERY_TIMEOUT_MS): Int? {
    val sanitized = id.takeIf { safeAnchorId.matches(it) } ?: return null

    withTimeoutOrNull(timeoutMillis) {
        while (!hasElement(sanitized)) delay(POLL_INTERVAL_MS)
    }
    awaitImagesLoaded(timeoutMillis)

    val scale = documentScalePx()
    val css = elementOffsetCss(sanitized) ?: return null
    return (css * scale).roundToInt()
}

/** 立即取元素偏移（**物理 px**），不做等待与图片补偿。用于恢复时的漂移校正。 */
suspend fun WebView.elementOffsetTop(id: String): Int? {
    val sanitized = id.takeIf { safeAnchorId.matches(it) } ?: return null
    val scale = documentScalePx()
    val css = elementOffsetCss(sanitized) ?: return null
    return (css * scale).roundToInt()
}

/**
 * 找出文档中「起始位置不晚于 [documentY] 的最后一个带 id 元素」。
 *
 * @param documentY 视口顶部在**文档内的物理 px 偏移**（与 `ScrollState.value` 同一坐标系，
 *   内部会换算成 CSS px 后再交给 JS 比较）。
 * @return `id to 文档内物理 px 偏移`；找不到返回 `null`。
 */
suspend fun WebView.anchorAt(documentY: Int): Pair<String, Int>? {
    val scale = documentScalePx()
    val y = (documentY / scale).roundToInt()
    val value =
        evaluateJavascriptSync(
            "(function(){var y=$y,best=null,bestTop=-1;" +
                "var all=document.querySelectorAll('[id]');" +
                "for(var i=0;i<all.length;i++){" +
                "var t=all[i].getBoundingClientRect().top+window.scrollY;" +
                "if(t<=y&&t>=bestTop){bestTop=t;best=all[i];}}" +
                "return best?[best.id,Math.round(bestTop)]:null;})()"
        ) ?: return null
    if (value.isBlank() || value == "null") return null
    return runCatching {
            val array = JSONArray(value)
            array.getString(0) to (array.getInt(1) * scale).roundToInt()
        }
        .getOrNull()
}

/** 文档中是否存在该 id 的元素。 */
suspend fun WebView.hasElement(id: String): Boolean {
    val sanitized = id.takeIf { safeAnchorId.matches(it) } ?: return false
    val value = evaluateJavascriptSync("!!document.getElementById('$sanitized')")
    return value == "true"
}

/** 全部图片是否已加载完成（`img.complete`）。 */
suspend fun WebView.allImagesLoaded(): Boolean {
    val value =
        evaluateJavascriptSync(
            "(function(){var i=document.getElementsByTagName('img');" +
                "for(var k=0;k<i.length;k++){if(!i[k].complete)return false;}return true;})()"
        )
    return value == "true"
}

/** 等待图片加载完成，最多等 [timeoutMillis]，超时即返回（不抛异常）。 */
suspend fun WebView.awaitImagesLoaded(timeoutMillis: Long = ANCHOR_QUERY_TIMEOUT_MS) {
    withTimeoutOrNull(timeoutMillis) {
        while (!allImagesLoaded()) delay(POLL_INTERVAL_MS)
    }
}

/**
 * 把 `evaluateJavascript` 包成协程。
 *
 * 注意：`evaluateJavascript` 必须在主线程调用（调用方位于 Compose 的事件/协程上下文，满足）。
 * 回调值是被 JSON 序列化过的字符串：数字 → `"123"`，字符串 → `"\"abc\""`，null → `"null"`。
 */
private suspend fun WebView.evaluateJavascriptSync(script: String): String? =
    suspendCancellableCoroutine { continuation ->
        val callback: (String?) -> Unit = { value ->
            if (continuation.isActive) continuation.resume(value)
        }
        runCatching { evaluateJavascript(script) { value -> callback(value) } }
            .onFailure { if (continuation.isActive) continuation.resume(null) }
    }
