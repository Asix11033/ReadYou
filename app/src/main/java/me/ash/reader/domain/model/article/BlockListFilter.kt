package me.ash.reader.domain.model.article

import me.ash.reader.infrastructure.preference.SyncBlockList

/**
 * Returns `true` when the [title] hits any keyword in this [SyncBlockList],
 * which means the article should be blocked (filtered out) before it is inserted
 * into the database.
 *
 * Matching is a plain case-insensitive substring check:
 * - blank lines (produced by the trailing newline when the list is serialized)
 *   are ignored, so they never end up blocking every article;
 * - no regex is involved, so user input coming from the UI is always safe.
 */
fun SyncBlockList.shouldBlock(title: String): Boolean = any { keyword ->
    keyword.isNotBlank() && title.contains(keyword, ignoreCase = true)
}
