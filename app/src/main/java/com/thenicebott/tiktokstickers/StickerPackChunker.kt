package com.thenicebott.tiktokstickers

object StickerPackChunker {
    fun <T> chunk(items: List<T>, minimumSize: Int, maximumSize: Int): List<List<T>> {
        require(minimumSize > 0) { "minimumSize must be positive" }
        require(maximumSize >= minimumSize) { "maximumSize must be at least minimumSize" }
        if (items.size < minimumSize) return emptyList()

        val chunks = items.chunked(maximumSize).map { it.toMutableList() }.toMutableList()
        val last = chunks.last()
        if (chunks.size > 1 && last.size < minimumSize) {
            val previous = chunks[chunks.lastIndex - 1]
            val needed = minimumSize - last.size
            val movable = previous.size - minimumSize
            repeat(minOf(needed, movable)) {
                last.add(0, previous.removeAt(previous.lastIndex))
            }
        }

        return chunks.filter { it.size >= minimumSize }
    }
}
