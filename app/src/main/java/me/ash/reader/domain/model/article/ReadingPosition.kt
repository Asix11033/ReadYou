package me.ash.reader.domain.model.article

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 「阅读位置记忆」的持久化记录（每篇文章一条）。
 *
 * ## 为什么独立成表，而不是在 `article` 表加列
 *
 * 各服务的同步流程用 `@Insert(onConflict = REPLACE)` 重写文章行（REPLACE 的语义是
 * 先 DELETE 再 INSERT），挂在 `article` 上的位置列每次同步都会被清零。
 * 同理这里**刻意不加外键**：一旦带上 `ON DELETE CASCADE`，文章被重写时位置记录会被一起带走 ——
 * 正是要规避的失效模式。孤儿记录由 [me.ash.reader.domain.repository.ReadingPositionDao.deleteBefore] 按时间清理。
 *
 * ## 字段语义
 *
 * - [mode] 渲染器类型（[MODE_WEBVIEW] / [MODE_NATIVE]）。用户切换过渲染器时无法精确换算，退化为 [ratio]。
 * - [index] / [offset]：[MODE_NATIVE] 时是 `firstVisibleItemIndex` / `firstVisibleItemScrollOffset`；
 *   [MODE_WEBVIEW] 时 [index] 无意义（-1），[offset] 是 `ScrollState.value`（**物理 px**）。
 * - [anchorId] / [anchorOffset]：段落级锚点（源侧 `<h* id>` 或客户端补的 `ry-toc-N`）。
 *   恢复时用「锚点当前位置 − 捕获时位置」补偿图片异步加载造成的漂移；
 *   空串表示当次捕获没有可用锚点（此时只能按像素/比例落位）。
 * - [contentLength] / [contentHash]：内容指纹，避免把「摘要」的位置用到「全文」上。
 */
@Entity(tableName = "reading_position")
data class ReadingPosition(
    @PrimaryKey val articleId: String,
    /** 便于删账户时联动清理；取不到时为 -1。 */
    val accountId: Int,
    val mode: Int,
    val index: Int,
    val offset: Int,
    val anchorId: String,
    val anchorOffset: Int,
    val ratio: Float,
    val contentLength: Int,
    val contentHash: Int,
    val updatedAt: Long,
) {

    companion object {
        /** 默认渲染器（WebView）：位置是 `ScrollState.value` 像素偏移。 */
        const val MODE_WEBVIEW = 0

        /** 原生组件渲染器：位置是 `(itemIndex, itemScrollOffset)`。 */
        const val MODE_NATIVE = 1

        /** 比例大于该值时视为「已读完」：清空记录，下次从头开始。 */
        const val READ_COMPLETED_RATIO = 0.98f

        /** 位置记录的最长保留时间（天）。 */
        const val RETENTION_DAYS = 60L
    }
}
