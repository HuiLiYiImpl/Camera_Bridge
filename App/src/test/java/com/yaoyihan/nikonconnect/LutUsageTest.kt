package com.yaoyihan.nikonconnect

import org.junit.Assert.*
import org.junit.Test

class LutUsageTest {
    @Test fun frequencyOutranksRecentBrowsingAndOnlyFourAreFeatured() {
        val frequent = LutEntry("frequent", "常用", useCount = 12, lastUsedAt = 1)
        val recent = LutEntry("recent", "刚浏览", useCount = 0, lastUsedAt = 100)
        val entries = listOf(recent, frequent) + (1..4).map { LutEntry("$it", "$it", useCount = it.toLong()) }
        assertEquals(listOf("frequent", "4", "3", "2"), frequentLuts(entries).map { it.id })
        assertEquals(emptyList<LutEntry>(), frequentLuts(emptyList()))
    }

    @Test fun tiedCountsUseRecencyAndDoNotInventMissingPresets() {
        val older = LutEntry("a", "较早", useCount = 2, lastUsedAt = 10)
        val newer = LutEntry("b", "较近", useCount = 2, lastUsedAt = 20)
        assertEquals(listOf(newer, older), frequentLuts(listOf(older, newer)))
    }

    @Test fun successfulUsePreservesCoverAndOtherMetadataWithoutOverflow() {
        val original = LutEntry("id", "名称", favorite = true, coverKey = "cover", useCount = 3)
        val used = original.recordSuccessfulUse(123)
        assertEquals(4L, used.useCount)
        assertEquals(123L, used.lastUsedAt)
        assertEquals(original.copy(useCount = 4, lastUsedAt = 123), used)
        assertEquals(Long.MAX_VALUE, original.copy(useCount = Long.MAX_VALUE).recordSuccessfulUse(124).useCount)
        assertEquals(1L, original.copy(useCount = -1).recordSuccessfulUse(125).useCount)
    }
}
