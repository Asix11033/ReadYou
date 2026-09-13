package me.ash.reader.infrastructure.preference

import me.ash.reader.ui.page.settings.accounts.AccountViewModel

typealias SyncBlockList = List<String>

object SyncBlockListPreference {

    val default: SyncBlockList = emptyList()

    fun put(accountId: Int, viewModel: AccountViewModel, syncBlockList: SyncBlockList) {
        viewModel.update(accountId) { copy(syncBlockList = syncBlockList) }
    }

    /**
     * Parses the text held by the block-list dialog (or read back from the database) into a
     * keyword list.
     *
     * - `trim` + drop blanks: the trailing newline produced by [toString] must never become a
     *   keyword of its own;
     * - `removePrefix(", ")`: legacy cleanup. The previous implementation used
     *   `joinToString { "$it\n" }`, whose *default* separator `", "` leaked into every entry
     *   after the first one (a two-line list was stored as `"a\n, b\n"`, so the second keyword
     *   silently became `", b"` and could never match a title). Stripping the artifact here
     *   repairs lists that were already saved with the old serializer.
     */
    fun of(syncBlockList: String): SyncBlockList =
        syncBlockList
            .split("\n")
            .map { it.trim().removePrefix(", ").trim() }
            .filter { it.isNotBlank() }

    /**
     * Serializes [syncBlockList] to the single-text form stored in the `account.syncBlockList`
     * column and shown in the dialog.
     *
     * The separator **must** be given explicitly: `joinToString` defaults to `", "`, and combined
     * with a transform that already appends a newline it would write `"a\n, b\n"` instead of
     * `"a\nb"`, corrupting every keyword after the first one.
     */
    fun toString(syncBlockList: SyncBlockList): String = syncBlockList
        .filter { it.isNotBlank() }
        .map { it.trim() }
        .joinToString(separator = "\n")
}
