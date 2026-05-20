package com.example.timewidget

import android.content.Context
import java.text.Normalizer
import java.util.Locale

/** One city from the bundled GeoNames dataset. [folded] is the diacritic-stripped lowercase name. */
data class CityRecord(
    val name: String,
    val admin: String,
    val countryCode: String,
    val zoneId: String,
    val population: Long,
    val folded: String
)

/**
 * Loads the bundled `cities.tsv` (GeoNames cities5000 — ~69k cities with population >= 5,000)
 * once and provides fast, diacritic-insensitive, population-ranked search. The dataset lets the
 * picker find essentially any town (e.g. Queenstown, NZ), not just a hand-curated list.
 */
object CityRepository {

    @Volatile
    private var cities: List<CityRecord>? = null

    private val DIACRITICS = Regex("\\p{Mn}+")

    val isLoaded: Boolean get() = cities != null

    /** Parse the asset once. Call off the main thread — it reads ~2 MB and folds ~69k names. */
    fun ensureLoaded(context: Context) {
        if (cities != null) return
        synchronized(this) {
            if (cities != null) return
            val list = ArrayList<CityRecord>(70_000)
            context.assets.open("cities.tsv").bufferedReader().useLines { lines ->
                for (line in lines) {
                    val p = line.split('\t')
                    // name, admin, country, zone, population
                    if (p.size < 5 || p[0].isEmpty() || p[3].isEmpty()) continue
                    list.add(
                        CityRecord(
                            name = p[0],
                            admin = p[1],
                            countryCode = p[2],
                            zoneId = p[3],
                            population = p[4].toLongOrNull() ?: 0L,
                            folded = fold(p[0])
                        )
                    )
                }
            }
            list.sortByDescending { it.population }
            cities = list
        }
    }

    /**
     * Returns up to [limit] matches: prefix matches first, then substring matches, each bucket
     * already ordered by population (so "London" surfaces London, GB before smaller Londons).
     */
    fun search(query: String, limit: Int = 50): List<CityRecord> {
        val all = cities ?: return emptyList()
        val q = fold(query.trim())
        if (q.isEmpty()) return emptyList()

        val prefix = ArrayList<CityRecord>(limit)
        val contains = ArrayList<CityRecord>(limit)
        for (c in all) {
            when {
                c.folded.startsWith(q) -> if (prefix.size < limit) prefix.add(c)
                c.folded.contains(q) -> if (contains.size < limit) contains.add(c)
            }
            if (prefix.size >= limit) break
        }
        if (prefix.size >= limit) return prefix

        val out = ArrayList<CityRecord>(limit)
        out.addAll(prefix)
        for (c in contains) {
            if (out.size >= limit) break
            out.add(c)
        }
        return out
    }

    /** Full country name from its ISO code via the platform (e.g. "NZ" -> "New Zealand"). */
    fun countryName(code: String): String {
        if (code.isBlank()) return ""
        val name = Locale("", code).displayCountry
        return if (name.isBlank()) code else name
    }

    private fun fold(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD)
            .replace(DIACRITICS, "")
            .lowercase(Locale.ROOT)
}
