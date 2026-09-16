package com.thenicebott.tiktokstickers

import org.junit.Assert.assertEquals
import org.junit.Test

class StickerPackChunkerTest {
    @Test
    fun keepsEveryItemAndAvoidsInvalidRemainders() {
        val expected = mapOf(
            0 to emptyList(),
            2 to emptyList(),
            3 to listOf(3),
            30 to listOf(30),
            31 to listOf(28, 3),
            32 to listOf(29, 3),
            33 to listOf(30, 3),
            60 to listOf(30, 30),
            61 to listOf(30, 28, 3)
        )

        expected.forEach { (itemCount, expectedSizes) ->
            val items = (0 until itemCount).toList()
            val chunks = StickerPackChunker.chunk(items, minimumSize = 3, maximumSize = 30)
            assertEquals("Unexpected sizes for $itemCount items", expectedSizes, chunks.map { it.size })
            if (itemCount >= 3) assertEquals("Items were lost or reordered", items, chunks.flatten())
        }
    }
}
