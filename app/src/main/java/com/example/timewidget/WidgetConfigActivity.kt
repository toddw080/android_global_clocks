package com.example.timewidget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.Toast

/**
 * Configuration screen for a single widget instance. Opened both when the widget is first
 * added (via android:configure) and when an existing widget is tapped. Lets the user pick
 * 1..MAX_ZONES locations, each with its own 12/24/Default format, plus a global default.
 */
class WidgetConfigActivity : Activity() {

    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    private lateinit var zonesContainer: LinearLayout
    private lateinit var globalFormatSpinner: Spinner
    private lateinit var addZoneButton: Button

    /** Cities in picker order, paired with their offset-prefixed display strings. */
    private val pickerEntries = Cities.pickerEntries()
    private val cityDisplays = pickerEntries.map { it.second }

    /** Live handles to each on-screen zone row. */
    private val rows = ArrayList<ZoneRow>()

    private class ZoneRow(val view: View, val citySpinner: Spinner, val formatSpinner: Spinner)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Default result: if the user backs out during initial setup, the widget isn't added.
        appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        setResult(RESULT_CANCELED, resultIntent())

        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        setContentView(R.layout.widget_config)
        zonesContainer = findViewById(R.id.zones_container)
        globalFormatSpinner = findViewById(R.id.global_format_spinner)
        addZoneButton = findViewById(R.id.add_zone_button)

        globalFormatSpinner.adapter = simpleAdapter(
            listOf(getString(R.string.format_12h), getString(R.string.format_24h))
        )

        addZoneButton.setOnClickListener { addZoneRow(null) }
        findViewById<Button>(R.id.save_button).setOnClickListener { save() }

        restoreOrSeed()
    }

    /** Pre-populate from an existing config, or seed sensible defaults for a new widget. */
    private fun restoreOrSeed() {
        val existing = WidgetPrefs.load(this, appWidgetId)
        if (existing != null && existing.zones.isNotEmpty()) {
            globalFormatSpinner.setSelection(if (existing.globalIs24h) 1 else 0)
            existing.zones.forEach { addZoneRow(it) }
        } else {
            globalFormatSpinner.setSelection(0) // 12-hour
            addZoneRow(ZoneEntry("New York", "America/New_York", HourFormat.DEFAULT))
            addZoneRow(ZoneEntry("London", "Europe/London", HourFormat.DEFAULT))
        }
    }

    private fun addZoneRow(preset: ZoneEntry?) {
        if (rows.size >= WidgetPrefs.MAX_ZONES) return

        val rowView = layoutInflater.inflate(R.layout.config_zone_row, zonesContainer, false)
        val citySpinner = rowView.findViewById<Spinner>(R.id.city_spinner)
        val formatSpinner = rowView.findViewById<Spinner>(R.id.format_spinner)
        val removeButton = rowView.findViewById<Button>(R.id.remove_button)

        citySpinner.adapter = simpleAdapter(cityDisplays)
        formatSpinner.adapter = simpleAdapter(
            listOf(
                getString(R.string.format_default),
                getString(R.string.format_12h),
                getString(R.string.format_24h)
            )
        )

        if (preset != null) {
            val cityIndex = pickerEntries.indexOfFirst {
                it.first.label == preset.label && it.first.zoneId == preset.zoneId
            }
            if (cityIndex >= 0) citySpinner.setSelection(cityIndex)
            formatSpinner.setSelection(
                when (preset.format) {
                    HourFormat.DEFAULT -> 0
                    HourFormat.H12 -> 1
                    HourFormat.H24 -> 2
                }
            )
        }

        val row = ZoneRow(rowView, citySpinner, formatSpinner)
        removeButton.setOnClickListener { removeZoneRow(row) }

        rows.add(row)
        zonesContainer.addView(rowView)
        refreshControls()
    }

    private fun removeZoneRow(row: ZoneRow) {
        if (rows.size <= 1) return // keep at least one clock
        rows.remove(row)
        zonesContainer.removeView(row.view)
        refreshControls()
    }

    /** Enable/disable Add and per-row Remove based on the current count. */
    private fun refreshControls() {
        addZoneButton.isEnabled = rows.size < WidgetPrefs.MAX_ZONES
        val canRemove = rows.size > 1
        rows.forEach { it.view.findViewById<Button>(R.id.remove_button).isEnabled = canRemove }
    }

    private fun save() {
        val zones = rows.map { row ->
            val city = pickerEntries[row.citySpinner.selectedItemPosition].first
            val format = when (row.formatSpinner.selectedItemPosition) {
                1 -> HourFormat.H12
                2 -> HourFormat.H24
                else -> HourFormat.DEFAULT
            }
            ZoneEntry(city.label, city.zoneId, format)
        }
        if (zones.isEmpty()) {
            Toast.makeText(this, R.string.config_need_one, Toast.LENGTH_SHORT).show()
            return
        }

        val config = WidgetConfig(zones, globalIs24h = globalFormatSpinner.selectedItemPosition == 1)
        WidgetPrefs.save(this, appWidgetId, config)

        TimeWidgetProvider.renderWidget(this, AppWidgetManager.getInstance(this), appWidgetId)

        setResult(RESULT_OK, resultIntent())
        finish()
    }

    private fun resultIntent() =
        intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)

    private fun simpleAdapter(items: List<String>): ArrayAdapter<String> =
        ArrayAdapter(this, android.R.layout.simple_spinner_item, items).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
}
