package dev.jota.gitlabcockpit.ui

import dev.jota.gitlabcockpit.CockpitBundle
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime

/**
 * Formats an ISO-8601 timestamp (GitLab's `updated_at`) as a short relative string such as
 * "5m ago" / "2h ago" / "3d ago". Falls back to the ISO date (first 10 chars) for anything older
 * than ~30 days or when parsing fails.
 */
fun formatRelative(iso: String): String = try {
    val instant = runCatching { OffsetDateTime.parse(iso).toInstant() }
        .getOrElse { Instant.parse(iso) }
    val seconds = Duration.between(instant, Instant.now()).seconds
    when {
        seconds < 60 -> CockpitBundle.message("toolwindow.time.justNow")
        seconds < 3_600 -> CockpitBundle.message("toolwindow.time.minutes", seconds / 60)
        seconds < 86_400 -> CockpitBundle.message("toolwindow.time.hours", seconds / 3_600)
        seconds < 2_592_000 -> CockpitBundle.message("toolwindow.time.days", seconds / 86_400)
        else -> iso.take(10)
    }
} catch (e: Exception) {
    iso.take(10)
}
