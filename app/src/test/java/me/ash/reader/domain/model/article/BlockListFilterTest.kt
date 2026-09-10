package me.ash.reader.domain.model.article

import me.ash.reader.infrastructure.preference.SyncBlockList
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BlockListFilterTest {

    @Test
    fun emptyList_neverBlocks() {
        assertFalse(emptyList<String>().shouldBlock("Anything"))
        assertFalse(listOf("", "   ").shouldBlock("Anything"))
    }

    @Test
    fun blocksOnSubstringHit() {
        val list: SyncBlockList = listOf("广告", "Sponsored")
        assertTrue(list.shouldBlock("这是一条广告"))
        assertTrue(list.shouldBlock("Weekly Sponsored Digest"))
        assertFalse(list.shouldBlock("Weekly Digest"))
    }

    @Test
    fun matchingIsCaseInsensitive() {
        val list: SyncBlockList = listOf("sponsored")
        assertTrue(list.shouldBlock("Weekly Sponsored Digest"))
        assertTrue(list.shouldBlock("weekly SPONSORED digest"))
    }

    @Test
    fun trailingBlankEntryIsIgnored() {
        // Serializing the list always leaves a trailing newline, which parses back to a
        // blank entry. It must not end up blocking every article.
        val list: SyncBlockList = listOf("广告", "")
        assertTrue(list.shouldBlock("广告时间"))
        assertFalse(list.shouldBlock("一篇正常文章"))
    }

    @Test
    fun specialCharactersAreTreatedLiterally() {
        val list: SyncBlockList = listOf("100%", "a_b")
        assertTrue(list.shouldBlock("Discount 100% off"))
        assertFalse(list.shouldBlock("1000 dollars"))
        assertTrue(list.shouldBlock("x a_b y"))
        assertFalse(list.shouldBlock("x ab y"))
    }
}
