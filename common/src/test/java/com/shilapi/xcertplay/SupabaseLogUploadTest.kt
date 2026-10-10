package com.shilapi.xcertplay

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SupabaseLogUploadTest {
    @Test fun shortTextStaysWhole() {
        val text = "hello\n"
        assertEquals(text, SupabaseLogUpload.tailUtf8(text, 100).toString(Charsets.UTF_8))
    }

    @Test fun keepsTheEndingInsideTheCap() {
        val text = (1..5000).joinToString("\n") { "line $it" } + "\n"
        val tail = SupabaseLogUpload.tailUtf8(text, 1024)
        val decoded = tail.toString(Charsets.UTF_8)
        assertTrue(tail.size <= 1024)
        assertTrue(decoded.trimEnd().endsWith("line 5000"))
        val firstNumber = decoded.substringAfter("line ").substringBefore("\n").toInt()
        assertTrue(firstNumber > 1)
    }

    @Test fun doesNotSplitAMultibyteCharacter() {
        val tail = SupabaseLogUpload.tailUtf8("中".repeat(4000), 100)
        val decoded = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(tail))
        assertTrue(decoded.isNotEmpty())
        assertTrue(tail.size <= 100)
    }

    @Test fun blankOrNonHttpsConfigStaysOff() {
        assertFalse(SupabaseLogUpload.enabled("", "key"))
        assertFalse(SupabaseLogUpload.enabled("https://example.supabase.co", ""))
        assertFalse(SupabaseLogUpload.enabled("http://example.supabase.co", "key"))
        assertTrue(SupabaseLogUpload.enabled("https://example.supabase.co", "sb_publishable_x"))
    }
}
