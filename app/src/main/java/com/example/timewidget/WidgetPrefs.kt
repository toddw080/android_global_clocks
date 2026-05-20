package com.example.timewidget

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** How a single zone renders its time. DEFAULT defers to the widget's global setting. */
enum class HourFormat { DEFAULT, H12, H24 }

/**
 * One configured clock row. [label] is what the widget shows (user-editable). [cityName] is the
 * city that was picked (drives the time via [zoneId]); it defaults to [label] and is kept so the
 * config screen can still show which city a row points at even after the label is customized.
 */
data class ZoneEntry(
    val label: String,
    val zoneId: String,
    val format: HourFormat,
    val cityName: String = label
)

/** A widget instance's full configuration: its ordered rows plus the global 12/24 default. */
data class WidgetConfig(val zones: List<ZoneEntry>, val globalIs24h: Boolean)

/**
 * Per-widget configuration persisted in SharedPreferences as JSON, keyed by appWidgetId.
 * Each placed widget instance keeps its own independent setup.
 */
object WidgetPrefs {

    const val MAX_ZONES = 4

    private const val PREFS = "time_widget_prefs"
    private fun key(appWidgetId: Int) = "widget_$appWidgetId"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun save(context: Context, appWidgetId: Int, config: WidgetConfig) {
        val zones = JSONArray()
        for (z in config.zones) {
            zones.put(
                JSONObject()
                    .put("label", z.label)
                    .put("cityName", z.cityName)
                    .put("zoneId", z.zoneId)
                    .put("format", z.format.name)
            )
        }
        val obj = JSONObject()
            .put("zones", zones)
            .put("globalIs24h", config.globalIs24h)
        prefs(context).edit().putString(key(appWidgetId), obj.toString()).apply()
    }

    /** Returns the saved config, or null if this widget hasn't been configured yet. */
    fun load(context: Context, appWidgetId: Int): WidgetConfig? {
        val raw = prefs(context).getString(key(appWidgetId), null) ?: return null
        return try {
            val obj = JSONObject(raw)
            val arr = obj.getJSONArray("zones")
            val zones = ArrayList<ZoneEntry>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                zones.add(
                    ZoneEntry(
                        label = o.getString("label"),
                        zoneId = o.getString("zoneId"),
                        format = runCatching { HourFormat.valueOf(o.optString("format", "DEFAULT")) }
                            .getOrDefault(HourFormat.DEFAULT),
                        // Back-compat: older saves have no cityName -> fall back to the label.
                        cityName = o.optString("cityName", o.getString("label"))
                    )
                )
            }
            WidgetConfig(zones, obj.optBoolean("globalIs24h", false))
        } catch (e: Exception) {
            null
        }
    }

    fun delete(context: Context, appWidgetId: Int) {
        prefs(context).edit().remove(key(appWidgetId)).apply()
    }
}
