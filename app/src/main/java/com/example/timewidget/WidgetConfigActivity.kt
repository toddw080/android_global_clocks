package com.example.timewidget

import android.app.Activity
import android.app.AlertDialog
import android.appwidget.AppWidgetManager
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast

/**
 * Configuration screen for a single widget instance. Opened both when the widget is first
 * added (via android:configure) and when an existing widget is tapped. Lets the user pick
 * 1..MAX_ZONES locations, each with its own 12/24/Default format, plus a global default.
 *
 * Each city is chosen via a search dialog (search box + scrollable list) so the keyboard
 * behaves natively, rather than an inline auto-complete dropdown.
 */
class WidgetConfigActivity : Activity() {

    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    private lateinit var zonesContainer: LinearLayout
    private lateinit var globalFormatSpinner: Spinner
    private lateinit var addZoneButton: Button

    /** Cities in picker order, paired with their offset-prefixed display strings. */
    private val pickerEntries = Cities.pickerEntries()
    private val cityDisplays = pickerEntries.map { it.second }
    private val displayToCity = pickerEntries.associate { (city, display) -> display to city }
    private val cityToDisplay = pickerEntries.associate { (city, display) -> city to display }

    /** Live handles to each on-screen zone row. */
    private val rows = ArrayList<ZoneRow>()

    private class ZoneRow(
        val view: View,
        val cityField: TextView,
        val formatSpinner: Spinner
    ) {
        /** The city chosen for this row — the source of truth. */
        var selectedCity: City? = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Default result: if the user backs out / cancels during initial setup, the widget
        // isn't added.
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
        findViewById<Button>(R.id.cancel_button).setOnClickListener { cancelAndFinish() }

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
        val cityField = rowView.findViewById<TextView>(R.id.city_field)
        val formatSpinner = rowView.findViewById<Spinner>(R.id.format_spinner)
        val removeButton = rowView.findViewById<Button>(R.id.remove_button)

        val row = ZoneRow(rowView, cityField, formatSpinner)

        // Tapping the field opens the search dialog.
        cityField.setOnClickListener { openCityDialog(row) }

        formatSpinner.adapter = simpleAdapter(
            listOf(
                getString(R.string.format_default),
                getString(R.string.format_12h),
                getString(R.string.format_24h)
            )
        )

        if (preset != null) {
            pickerEntries.firstOrNull {
                it.first.label == preset.label && it.first.zoneId == preset.zoneId
            }?.first?.let { setCityOnRow(row, it) }
            formatSpinner.setSelection(
                when (preset.format) {
                    HourFormat.DEFAULT -> 0
                    HourFormat.H12 -> 1
                    HourFormat.H24 -> 2
                }
            )
        }

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

    private fun setCityOnRow(row: ZoneRow, city: City) {
        row.selectedCity = city
        row.cityField.text = cityToDisplay[city] ?: city.label
    }

    /**
     * Open a standard search dialog: a search box on top (keyboard docks/auto-shows like any
     * other app) and a scrollable list below that filters as you type. Tapping a city selects
     * it and closes the dialog. Cancel / back / tap-outside dismiss without changing the row.
     */
    private fun openCityDialog(row: ZoneRow) {
        val view = layoutInflater.inflate(R.layout.dialog_city_search, null)
        val searchBox = view.findViewById<EditText>(R.id.search_box)
        val cityList = view.findViewById<ListView>(R.id.city_list)

        val listAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, cityDisplays)
        cityList.adapter = listAdapter

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.config_choose_city)
            .setView(view)
            .setNegativeButton(R.string.config_cancel, null)
            .create()

        searchBox.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
                listAdapter.filter.filter(s)
            }

            override fun afterTextChanged(s: Editable?) {}
        })

        cityList.setOnItemClickListener { _, _, position, _ ->
            val display = listAdapter.getItem(position) ?: return@setOnItemClickListener
            displayToCity[display]?.let { setCityOnRow(row, it) }
            dialog.dismiss()
        }

        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()
        searchBox.requestFocus()
    }

    /** Enable/disable Add and per-row Remove based on the current count. */
    private fun refreshControls() {
        addZoneButton.isEnabled = rows.size < WidgetPrefs.MAX_ZONES
        val canRemove = rows.size > 1
        rows.forEach { it.view.findViewById<Button>(R.id.remove_button).isEnabled = canRemove }
    }

    private fun save() {
        if (rows.isEmpty()) {
            Toast.makeText(this, R.string.config_need_one, Toast.LENGTH_SHORT).show()
            return
        }
        val zones = ArrayList<ZoneEntry>(rows.size)
        for (row in rows) {
            val city = row.selectedCity
            if (city == null) {
                Toast.makeText(this, R.string.config_pick_city, Toast.LENGTH_SHORT).show()
                return
            }
            val format = when (row.formatSpinner.selectedItemPosition) {
                1 -> HourFormat.H12
                2 -> HourFormat.H24
                else -> HourFormat.DEFAULT
            }
            zones.add(ZoneEntry(city.label, city.zoneId, format))
        }

        val config = WidgetConfig(zones, globalIs24h = globalFormatSpinner.selectedItemPosition == 1)
        WidgetPrefs.save(this, appWidgetId, config)

        TimeWidgetProvider.renderWidget(this, AppWidgetManager.getInstance(this), appWidgetId)

        setResult(RESULT_OK, resultIntent())
        finish()
    }

    private fun cancelAndFinish() {
        setResult(RESULT_CANCELED, resultIntent())
        finish()
    }

    private fun resultIntent() =
        intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)

    private fun simpleAdapter(items: List<String>): ArrayAdapter<String> =
        ArrayAdapter(this, android.R.layout.simple_spinner_item, items).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
}
