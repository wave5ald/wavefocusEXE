package com.example.util

import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.chrono.HijrahChronology
import java.time.chrono.HijrahDate

/** Result of an online Hijri verification round. */
data class HijriVerification(
    val offsetDays: Int = 0,
    val method: String = "",
    val checkedAt: Long = System.currentTimeMillis(),
    val error: String? = null,
)

/**
 * Verifies the on-device Umm al-Qura Hijri calendar against the official Saudi
 * calendar (Aladhan API, method HJCoSA).
 *
 * Only the day offset is exchanged: it is applied to every conversion by
 * [HijriCalendarHelper], so month lengths follow the device's real Umm al-Qura
 * tables while the day number matches the announced Saudi date.
 */
object HijriSync {

    private const val ENDPOINT = "https://api.aladhan.com/v1/gToH"
    private val HIJRI_DATE = Regex("\"date\"\\s*:\\s*\"(\\d{1,2})-(\\d{1,2})-(\\d{3,4})\"")
    private val METHOD = Regex("\"method\"\\s*:\\s*\"([^\"]+)\"")

    fun verify(today: LocalDate = LocalDate.now()): HijriVerification {
        return try {
            val day = "%02d".format(today.dayOfMonth)
            val month = "%02d".format(today.monthValue)
            val url = URL("$ENDPOINT?date=$day-$month-${today.year}")
            val connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 8000
                requestMethod = "GET"
                setRequestProperty("Accept", "application/json")
            }
            val body = try {
                connection.inputStream.bufferedReader().use { it.readText() }
            } finally {
                connection.disconnect()
            }

            val match = HIJRI_DATE.find(body) ?: return HijriVerification(error = "bad-payload")
            val (hijriDay, hijriMonth, hijriYear) = match.destructured
            val official = HijrahChronology.INSTANCE.date(
                hijriYear.toInt(), hijriMonth.toInt(), hijriDay.toInt()
            )
            val onDevice = HijrahDate.from(today)
            val offset = (official.toEpochDay() - onDevice.toEpochDay()).toInt().coerceIn(-3, 3)
            HijriVerification(
                offsetDays = offset,
                method = METHOD.find(body)?.groupValues?.get(1) ?: "HJCoSA",
            )
        } catch (e: Exception) {
            HijriVerification(error = e.message ?: "unreachable")
        }
    }
}
