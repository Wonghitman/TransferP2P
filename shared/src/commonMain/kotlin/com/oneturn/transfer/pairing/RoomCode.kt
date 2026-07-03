package com.oneturn.transfer.pairing

import kotlin.random.Random

object RoomCodeGenerator {
    private val words = listOf(
        "apple", "blue", "cloud", "delta", "echo", "flame", "green", "haze",
        "iris", "jade", "kite", "lime", "mint", "nova", "opal", "pine",
    )

    fun generateNumeric(length: Int = 6): String =
        buildString(length) {
            repeat(length) { append(Random.nextInt(10)) }
        }

    fun generateMnemonic(wordCount: Int = 4): String =
        (1..wordCount)
            .map { words.random() }
            .joinToString("-")

    fun normalize(input: String): String = input.trim().lowercase()

    fun parseFromScan(content: String): String {
        val trimmed = content.trim()
        Regex("""/join/([^/?#\s]+)""", RegexOption.IGNORE_CASE)
            .find(trimmed)
            ?.groupValues
            ?.get(1)
            ?.let { return normalize(it) }
        return normalize(trimmed)
    }
}

data class RoomInfo(
    val code: String,
    val joinUrl: String,
    val wsUrl: String,
    val expiresAt: Long,
)
