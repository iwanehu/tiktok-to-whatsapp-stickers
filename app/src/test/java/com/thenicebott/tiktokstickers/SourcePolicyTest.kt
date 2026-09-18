package com.thenicebott.tiktokstickers
import org.junit.Assert.*
import org.junit.Test
class SourcePolicyTest {
    @Test fun acceptsOnlyHttpsMediaOrigins() {
        assertTrue(SourcePolicy.allowed("https://p16.tiktokcdn.com/sticker.webp?token=1"))
        listOf("http://p16.tiktokcdn.com/a", "https://tiktokcdn.com.evil.example/a", "https://tiktokcdn.com@127.0.0.1/a", "https://127.0.0.1/a", "file:///tmp/a", "https://p16.tiktokcdn.com:8080/a").forEach { assertFalse(it, SourcePolicy.allowed(it)) }
    }
}
