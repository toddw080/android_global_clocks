package com.example.timewidget

import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs

/** A selectable city and the IANA timezone its clock follows. */
data class City(val label: String, val zoneId: String)

/**
 * Curated catalog of cities spanning the major UTC offsets. The displayed time is
 * always driven by the IANA [City.zoneId] (so it stays DST-correct); the [City.label]
 * is purely the location name shown on the widget. Several cities can share one zone
 * (e.g. Denver / Salt Lake City / Albuquerque / El Paso all use America/Denver).
 */
object Cities {

    val ALL: List<City> = listOf(
        // North America
        City("Honolulu", "Pacific/Honolulu"),
        City("Anchorage", "America/Anchorage"),
        City("Los Angeles", "America/Los_Angeles"),
        City("San Francisco", "America/Los_Angeles"),
        City("Seattle", "America/Los_Angeles"),
        City("Las Vegas", "America/Los_Angeles"),
        City("Phoenix", "America/Phoenix"),
        City("Denver", "America/Denver"),
        City("Salt Lake City", "America/Denver"),
        City("Albuquerque", "America/Denver"),
        City("El Paso", "America/Denver"),
        City("Boise", "America/Boise"),
        City("Chicago", "America/Chicago"),
        City("Dallas", "America/Chicago"),
        City("Houston", "America/Chicago"),
        City("Mexico City", "America/Mexico_City"),
        City("New York", "America/New_York"),
        City("Washington DC", "America/New_York"),
        City("Atlanta", "America/New_York"),
        City("Miami", "America/New_York"),
        City("Toronto", "America/Toronto"),
        City("Halifax", "America/Halifax"),
        City("São Paulo", "America/Sao_Paulo"),
        City("Buenos Aires", "America/Argentina/Buenos_Aires"),
        // Europe / Africa
        City("London", "Europe/London"),
        City("Dublin", "Europe/Dublin"),
        City("Lisbon", "Europe/Lisbon"),
        City("Paris", "Europe/Paris"),
        City("Madrid", "Europe/Madrid"),
        City("Berlin", "Europe/Berlin"),
        City("Rome", "Europe/Rome"),
        City("Amsterdam", "Europe/Amsterdam"),
        City("Stockholm", "Europe/Stockholm"),
        City("Lagos", "Africa/Lagos"),
        City("Cairo", "Africa/Cairo"),
        City("Johannesburg", "Africa/Johannesburg"),
        City("Athens", "Europe/Athens"),
        City("Istanbul", "Europe/Istanbul"),
        City("Moscow", "Europe/Moscow"),
        // Middle East / Asia
        City("Dubai", "Asia/Dubai"),
        City("Tehran", "Asia/Tehran"),
        City("Karachi", "Asia/Karachi"),
        City("Mumbai", "Asia/Kolkata"),
        City("New Delhi", "Asia/Kolkata"),
        City("Dhaka", "Asia/Dhaka"),
        City("Bangkok", "Asia/Bangkok"),
        City("Jakarta", "Asia/Jakarta"),
        City("Singapore", "Asia/Singapore"),
        City("Hong Kong", "Asia/Hong_Kong"),
        City("Beijing", "Asia/Shanghai"),
        City("Shanghai", "Asia/Shanghai"),
        City("Taipei", "Asia/Taipei"),
        City("Seoul", "Asia/Seoul"),
        City("Tokyo", "Asia/Tokyo"),
        // Oceania
        City("Perth", "Australia/Perth"),
        City("Adelaide", "Australia/Adelaide"),
        City("Brisbane", "Australia/Brisbane"),
        City("Sydney", "Australia/Sydney"),
        City("Melbourne", "Australia/Melbourne"),
        City("Auckland", "Pacific/Auckland"),
    )

    /** Current UTC offset in seconds for a zone (DST-aware), or 0 if the id is unknown. */
    private fun offsetSeconds(zoneId: String): Int =
        try {
            ZoneId.of(zoneId).rules.getOffset(Instant.now()).totalSeconds
        } catch (e: Exception) {
            0
        }

    /** Human-readable current offset, e.g. "UTC-07:00" / "UTC+05:30" / "UTC+00:00". */
    fun offsetLabel(zoneId: String): String {
        val secs = offsetSeconds(zoneId)
        val sign = if (secs < 0) "-" else "+"
        val absSecs = abs(secs)
        return "UTC%s%02d:%02d".format(sign, absSecs / 3600, (absSecs % 3600) / 60)
    }

    /**
     * Cities sorted by current UTC offset then name, paired with a display string that
     * carries the offset prefix — suitable for a single Spinner, e.g.
     * "(UTC-07:00)  Denver".
     */
    fun pickerEntries(): List<Pair<City, String>> =
        ALL.sortedWith(compareBy({ offsetSeconds(it.zoneId) }, { it.label }))
            .map { city -> city to "(${offsetLabel(city.zoneId)})  ${city.label}" }
}
