package com.pocketledger.ui.format

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * One money formatter for the whole app: `Ksh 1,234`, with decimals only when
 * the cents are not zero (`Ksh 1,234.50`). Never a float.
 */
fun ksh(cents: Long): String {
    val sign = if (cents < 0) "-" else ""
    val absolute = abs(cents)
    val whole = absolute / 100
    val fraction = absolute % 100
    return if (fraction == 0L) {
        String.format(Locale.US, "%sKsh %,d", sign, whole)
    } else {
        String.format(Locale.US, "%sKsh %,d.%02d", sign, whole, fraction)
    }
}

/** The amount without the currency, for places that print `Ksh` separately. */
fun amount(cents: Long): String {
    val absolute = abs(cents)
    val whole = absolute / 100
    val fraction = absolute % 100
    return if (fraction == 0L) {
        String.format(Locale.US, "%,d", whole)
    } else {
        String.format(Locale.US, "%,d.%02d", whole, fraction)
    }
}

/** "Food" from `food`; unknown tags read as "Other". */
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

/** Up to two initials, e.g. `JH` for "Java House"; no icons, no emoji. */
fun initials(counterparty: String): String =
    counterparty.trim()
        .split(Regex("\\s+"))
        .filter { it.isNotBlank() }
        .take(2)
        .mapNotNull { it.firstOrNull()?.uppercaseChar() }
        .joinToString("")
        .ifEmpty { "?" }

private val clock = DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault())
private val dayAndClock = DateTimeFormatter.ofPattern("d MMM, h:mm a", Locale.getDefault())
private val dayHeading = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.getDefault())

fun timeLabel(unixSeconds: Long): String {
    val zone = ZoneId.systemDefault()
    val moment = Instant.ofEpochSecond(unixSeconds).atZone(zone)
    return if (moment.toLocalDate() == LocalDate.now(zone)) {
        clock.format(moment)
    } else {
        dayAndClock.format(moment)
    }
}

/** `TODAY`, `YESTERDAY`, or a date heading for the Activity list. */
fun dayHeading(unixSeconds: Long): String {
    val zone = ZoneId.systemDefault()
    val day = Instant.ofEpochSecond(unixSeconds).atZone(zone).toLocalDate()
    val today = LocalDate.now(zone)
    return when (day) {
        today -> "TODAY"
        today.minusDays(1) -> "YESTERDAY"
        else -> dayHeading.format(Instant.ofEpochSecond(unixSeconds).atZone(zone)).uppercase(Locale.getDefault())
    }
}

/** The date under the Home title, e.g. "Monday 5 October". */
fun todayLabel(): String {
    val today = LocalDate.now(ZoneId.systemDefault())
    return dayHeading.format(today)
}

/** Share of the daily limit, clamped to 0..100 for the progress bar. */
fun limitFraction(spentCents: Long, limitCents: Long): Float {
    if (limitCents <= 0L) return 0f
    return (spentCents.toFloat() / limitCents.toFloat()).coerceIn(0f, 1f)
}

/** "61" for the caption under the progress bar. */
fun limitPercent(spentCents: Long, limitCents: Long): Int {
    if (limitCents <= 0L) return 0
    return ((spentCents.toDouble() / limitCents) * 100).roundToInt()
}

/**
 * The worker stores M-Pesa's uppercase names (`JAVA HOUSE`). Notifications read
 * better in sentence case, but utility and brand acronyms must survive.
 */
private val ACRONYMS = setOf("KPLC", "DSTV", "KFC", "KCB", "NCBA", "KRA", "NHIF", "SGR", "Uber")

fun displayName(raw: String): String = raw.trim()
    .split(Regex("\\s+"))
    .joinToString(" ") { word ->
        val upper = word.uppercase(Locale.US)
        when {
            word.isEmpty() -> word
            upper in ACRONYMS -> word
            word.all { it.isDigit() } -> word
            else -> word.lowercase(Locale.getDefault())
                .replaceFirstChar { it.titlecase(Locale.getDefault()) }
        }
    }
