package me.ash.reader.domain.repository

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import me.ash.reader.domain.model.article.ReadingPosition

/**
 * 「阅读位置记忆」的存取。
 *
 * 写入走 [upsert]（主键是 `articleId`，REPLACE 即「有则覆盖、无则插入」）；
 * 清理有三条路径：读到末尾（调用方直接 [deleteByArticleId]）、按时间老化（[deleteBefore]）、
 * 删除账户时联动（[deleteByAccountId]）。
 */
@Dao
interface ReadingPositionDao {

    @Query(
        """
        SELECT * FROM reading_position
        WHERE articleId = :articleId
        """
    )
    suspend fun queryByArticleId(articleId: String): ReadingPosition?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(readingPosition: ReadingPosition)

    @Query(
        """
        DELETE FROM reading_position
        WHERE articleId = :articleId
        """
    )
    suspend fun deleteByArticleId(articleId: String)

    @Query(
        """
        DELETE FROM reading_position
        WHERE updatedAt < :before
        """
    )
    suspend fun deleteBefore(before: Long)

    @Query(
        """
        DELETE FROM reading_position
        WHERE accountId = :accountId
        """
    )
    suspend fun deleteByAccountId(accountId: Int)
}
