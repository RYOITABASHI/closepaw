package ai.closepaw.ui.session

import ai.closepaw.R
import android.content.res.Resources
import android.text.format.DateFormat
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Utility functions for time formatting in the session list UI.
 * All user-visible text comes from string resources so it follows the
 * device locale.
 */
object TimeUtils {

    /**
     * Format timestamp as relative time.
     *
     * - < 1 minute: "Just now"
     * - < 60 minutes: "X minutes ago"
     * - < 24 hours: "X hours ago"
     * - < 7 days: "Yesterday" / "X days ago"
     * - Same year: localized month + day (e.g., "Jan 21")
     * - Different year: localized month + day + year (e.g., "Jan 21, 2023")
     *
     * @param resources Resources used to resolve the localized strings
     * @param timestamp The epoch timestamp in milliseconds
     * @return A human-readable relative time string
     */
    fun formatRelativeTime(resources: Resources, timestamp: Long): String {
        val now = System.currentTimeMillis()
        val diff = now - timestamp

        return when {
            diff < TimeUnit.MINUTES.toMillis(1) -> resources.getString(R.string.time_utils_just_now)
            diff < TimeUnit.HOURS.toMillis(1) -> {
                val minutes = TimeUnit.MILLISECONDS.toMinutes(diff).toInt()
                resources.getQuantityString(R.plurals.time_utils_minutes_ago, minutes, minutes)
            }
            diff < TimeUnit.DAYS.toMillis(1) -> {
                val hours = TimeUnit.MILLISECONDS.toHours(diff).toInt()
                resources.getQuantityString(R.plurals.time_utils_hours_ago, hours, hours)
            }
            diff < TimeUnit.DAYS.toMillis(7) -> {
                val days = TimeUnit.MILLISECONDS.toDays(diff).toInt()
                if (days == 1) {
                    resources.getString(R.string.time_utils_yesterday)
                } else {
                    resources.getQuantityString(R.plurals.time_utils_days_ago, days, days)
                }
            }
            else -> {
                val dateTime = LocalDateTime.ofInstant(
                    Instant.ofEpochMilli(timestamp),
                    ZoneId.systemDefault()
                )
                val currentYear = LocalDateTime.now().year
                val locale = currentLocale(resources)
                val skeleton = if (dateTime.year == currentYear) "MMMd" else "yMMMd"
                val pattern = DateFormat.getBestDateTimePattern(locale, skeleton)
                dateTime.format(DateTimeFormatter.ofPattern(pattern, locale))
            }
        }
    }

    /**
     * Format message count for display.
     *
     * @param resources Resources used to resolve the localized plural
     * @param count The number of messages
     * @return A formatted string like "3 messages" or "1 message"
     */
    fun formatMessageCount(resources: Resources, count: Int): String =
        resources.getQuantityString(R.plurals.time_utils_message_count, count, count)

    private fun currentLocale(resources: Resources): Locale {
        val locales = resources.configuration.locales
        return if (locales.isEmpty) Locale.getDefault() else locales[0]
    }
}
