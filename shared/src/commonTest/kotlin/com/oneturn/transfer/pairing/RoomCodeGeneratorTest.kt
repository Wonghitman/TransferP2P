package com.oneturn.transfer.pairing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RoomCodeGeneratorTest {

    @Test
    fun generatedCodeIsValid() {
        repeat(100) {
            val code = RoomCodeGenerator.generate()
            assertTrue(RoomCodeGenerator.isValid(code), "generated code should pass checksum: $code")
        }
    }

    @Test
    fun detectsTamperedContentWord() {
        val code = RoomCodeGenerator.generate()
        val words = code.split("-")
        val tampered = words.toMutableList()
        // flip one character in a content word (never the checksum word)
        val contentIndex = 0
        val replacement = if (tampered[contentIndex] == "apple") "amber" else "apple"
        tampered[contentIndex] = replacement
        assertFalse(
            RoomCodeGenerator.isValid(tampered.joinToString("-")),
            "tampered code should fail checksum: ${tampered.joinToString("-")}",
        )
    }

    @Test
    fun wrongWordCountFails() {
        assertFalse(RoomCodeGenerator.isValid("apple-cloud-delta"))
        assertFalse(RoomCodeGenerator.isValid("apple-cloud-delta-cedar-extra"))
    }

    @Test
    fun normalizeIsLowercaseTrimmed() {
        assertEquals("apple-cloud-delta-cedar", RoomCodeGenerator.normalize("  Apple-Cloud-Delta-Cedar  "))
    }

    @Test
    fun parseFromScanHandlesJoinUrl() {
        assertEquals(
            "apple-cloud-delta-cedar",
            RoomCodeGenerator.parseFromScan("https://sendmaster.1turn.cn/join/apple-cloud-delta-cedar?x=1"),
        )
        assertEquals(
            "apple-cloud-delta-cedar",
            RoomCodeGenerator.parseFromScan("apple-cloud-delta-cedar"),
        )
    }
}
