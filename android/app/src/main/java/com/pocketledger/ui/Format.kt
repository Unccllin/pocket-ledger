package com.pocketledger.ui

import androidx.compose.ui.graphics.Color
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

/** `1030.5` cents never happens: amounts are integer cents, formatted as `1,030.00`. */
fun money(cents: Long): String {
    val sign = if (cents < 0) "-" else ""
    val absolute = abs(cents)
    return String.format(Locale.US, "%s%,d.%02d", sign, absolute / 100, absolute % 100)
}

/** Spending reads as a signed amount; money received is marked with a plus. */
fun signedAmount(cents: Long, received: Boolean): String =
    if (received) "+Ksh${money(cents)}" else "\u2212Ksh${money(cents)}"

fun categoryLabel(tag: String): String = when (tag.lowercase(Locale.US)) {
    "food" -> "Food"
    "utilities" -> "Utilities"
    "airtime" -> "Airtime"
    "transport" -> "Transport"
    "shopping" -> "Shopping"
    "cash" -> "Cash"
    "transfer" -> "Transfer"
    else -> "Other"
}

fun categoryEmoji(tag: String): String = when (tag.lowercase(Locale.US)) {
    "food" -> "\uD83C\uDF7D\uFE0F"
    "utilities" -> "\uD83D\uDCA1"
    "airtime" -> "\uD83D\uDCF1"
    "transport" -> "\uD83D\uDE8C"
    "shopping" -> "\uD83D\uDED2"
    "cash" -> "\uD83D\uDCB5"
    "transfer" -> "\u21C4"
    else -> "\u2022"
}

/** Soft category tints; the emoji carries the meaning, the tint groups the list. */
fun categoryTint(tag: String, dark: Boolean): Color {
    val light = when (tag.lowercase(Locale.US)) {
        "food" -> 0xFFF6DFCB
        "utilities" -> 0xFFD9E2F7
        "airtime" -> 0xFFF7E7C6
        "transport" -> 0xFFD3EBDB
        "shopping" -> 0xFFEADCF3
        "cash" -> 0xFFDBEBD2
        "transfer" -> 0xFFE1E0F2
        else -> 0xFFEAE7DF
    }
    val night = when (tag.lowercase(Locale.US)) {
        "food" -> 0xFF3A2A1E
        "utilities" -> 0xFF1F2A3B
        "airtime" -> 0xFF3A2F1B
        "transport" -> 0xFF1D3227
        "shopping" -> 0xFF2F2438
        "cash" -> 0xFF24331F
        "transfer" -> 0xFF252438
        else -> 0xFF2A2A26
    }
    return Color(if (dark) night else light)
}

private val clockFormat = DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault())
private val olderFormat = DateTimeFormatter.ofPattern("d MMM, HH:mm", Locale.getDefault())

/** `Today 14:15` for today's rows, `31 Oct, 14:15` otherwise. */
fun timeLabel(unixSeconds: Long): String {
    val zone = ZoneId.systemDefault()
    val moment = Instant.ofEpochSecond(unixSeconds).atZone(zone)
    return if (moment.toLocalDate() == LocalDate.now(zone)) {
        "Today ${clockFormat.format(moment)}"
    } else {
        olderFormat.format(moment)
    }
}
