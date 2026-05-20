package com.example.timewidget

import android.app.Activity
import android.app.AlertDialog
import android.appwidget.AppWidgetManager
import android.os.Bundle
import android.os.Handler
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast

/**
 * Configuration screen for a single widget instance. Opened both when the widget is first
 * added (via android:configure) and when an existing widget is tapped. Lets the user pick
 * 1..MAX_ZONES locations, each with its own 12/24/Default format, plus a global default.
 *
 * Each city is chosen via a search dialog backed by [CityRepository] (the full ~69k-city
 * dataset), with a short curated "popular" list shown before the user types.
 */
class WidgetConfigActivity : Activity() {

    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    private lateinit var zonesContainer: LinearLayout
    private lateinit var globalFormatSpinner: Spinner
    private lateinit var addZoneButton: Button

    // Appearance controls.
    private lateinit var bgColorSpinner: Spinner
    private lateinit var opacitySeek: SeekBar
    private lateinit var opacityValue: TextView
    private lateinit var textColorSpinner: Spinner
    private lateinit var textSizeSpinner: Spinner

    private class BgChoice(val name: String, val color: Int?)

    /** Background swatch palette; index 0 = System (follow theme). */
    private val bgChoices = listOf(
        BgChoice("System", null),
        BgChoice("Black", 0xFF000000.toInt()),
        BgChoice("White", 0xFFFFFFFF.toInt()),
        BgChoice("Blue", 0xFF1565C0.toInt()),
        BgChoice("Teal", 0xFF00796B.toInt()),
        BgChoice("Green", 0xFF2E7D32.toInt()),
        BgChoice("Red", 0xFFC62828.toInt()),
        BgChoice("Purple", 0xFF6A1B9A.toInt()),
        BgChoice("Orange", 0xFFEF6C00.toInt()),
        BgChoice("Slate", 0xFF37474F.toInt())
    )

    /** A choosable entry in the city dialog: what to store plus how to display it. */
    private class PickItem(val label: String, val zoneId: String, val display: String) {
        override fun toString() = display
    }

    /** Curated, well-known cities shown before the user types anything. */
    private val popularItems: List<PickItem> by lazy {
        Cities.pickerEntries().map { (city, display) -> PickItem(city.label, city.zoneId, display) }
    }

    /** Live handles to each on-screen zone row. */
    private val rows = ArrayList<ZoneRow>()

    private class ZoneRow(
        val view: View,
        val labelInput: EditText,
        val zoneField: TextView,
        val formatSpinner: Spinner
    ) {
        /** The city chosen for this row (name + zone) — drives the time. */
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

        // Warm the city dataset on a background thread so search is ready when the user types.
        Thread { CityRepository.ensureLoaded(applicationContext) }.start()

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

        setupAppearanceControls()
        restoreOrSeed()
    }

    private fun setupAppearanceControls() {
        bgColorSpinner = findViewById(R.id.bg_color_spinner)
        opacitySeek = findViewById(R.id.opacity_seek)
        opacityValue = findViewById(R.id.opacity_value)
        textColorSpinner = findViewById(R.id.text_color_spinner)
        textSizeSpinner = findViewById(R.id.text_size_spinner)

        bgColorSpinner.adapter = simpleAdapter(bgChoices.map { it.name })
        textColorSpinner.adapter = simpleAdapter(listOf("Auto", "Light", "Dark"))
        textSizeSpinner.adapter = simpleAdapter(listOf("Small", "Medium", "Large"))

        opacitySeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                opacityValue.text = getString(R.string.config_opacity_value, progress)
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
    }

    private fun applyAppearanceToUi(a: Appearance) {
        bgColorSpinner.setSelection(bgChoices.indexOfFirst { it.color == a.backgroundColor }.coerceAtLeast(0))
        opacitySeek.progress = a.opacityPercent
        opacityValue.text = getString(R.string.config_opacity_value, a.opacityPercent)
        textColorSpinner.setSelection(a.textColorMode.ordinal)
        textSizeSpinner.setSelection(a.textSize.ordinal)
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
        applyAppearanceToUi(existing?.appearance ?: Appearance.DEFAULT)
    }

    private fun addZoneRow(preset: ZoneEntry?) {
        if (rows.size >= WidgetPrefs.MAX_ZONES) return

        val rowView = layoutInflater.inflate(R.layout.config_zone_row, zonesContainer, false)
        val labelInput = rowView.findViewById<EditText>(R.id.label_input)
        val zoneField = rowView.findViewById<TextView>(R.id.zone_field)
        val formatSpinner = rowView.findViewById<Spinner>(R.id.format_spinner)
        val removeButton = rowView.findViewById<Button>(R.id.remove_button)

        val row = ZoneRow(rowView, labelInput, zoneField, formatSpinner)

        zoneField.setOnClickListener { openCityDialog(row) }

        formatSpinner.adapter = simpleAdapter(
            listOf(
                getString(R.string.format_default),
                getString(R.string.format_12h),
                getString(R.string.format_24h)
            )
        )

        if (preset != null) {
            setZone(row, preset.cityName, preset.zoneId)
            row.labelInput.setText(preset.label)
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

    /** Set the row's zone (city name + offset shown in the zone field) without touching the label. */
    private fun setZone(row: ZoneRow, cityName: String, zoneId: String) {
        row.selectedCity = City(cityName, zoneId)
        row.zoneField.text = getString(R.string.config_zone_display, Cities.offsetLabel(zoneId), cityName)
    }

    /**
     * From the picker: set the zone and default the label to the city name — but keep whatever
     * the user already typed if it's a custom label (not just the previous city's name).
     */
    private fun applyPick(row: ZoneRow, cityName: String, zoneId: String) {
        val previousCity = row.selectedCity?.label
        val currentLabel = row.labelInput.text?.toString().orEmpty()
        setZone(row, cityName, zoneId)
        if (currentLabel.isBlank() || currentLabel == previousCity) {
            row.labelInput.setText(cityName)
        }
    }

    /** "Derby, Connecticut, United States  —  UTC-05:00" (admin/country omitted when blank). */
    private fun CityRecord.toPickItem(): PickItem {
        val place = buildString {
            append(name)
            if (admin.isNotBlank()) append(", ").append(admin)
            val country = CityRepository.countryName(countryCode)
            if (country.isNotBlank()) append(", ").append(country)
        }
        return PickItem(name, zoneId, "$place  —  ${Cities.offsetLabel(zoneId)}")
    }

    /**
     * Standard search dialog: a search box on top (keyboard docks/auto-shows like any other
     * app) and a scrollable list below. Empty query shows the curated "popular" list; typing
     * searches the full dataset (debounced, capped). Tapping a city selects it and closes.
     */
    private fun openCityDialog(row: ZoneRow) {
        val view = layoutInflater.inflate(R.layout.dialog_city_search, null)
        val searchBox = view.findViewById<EditText>(R.id.search_box)
        val cityList = view.findViewById<ListView>(R.id.city_list)
        cityList.emptyView = view.findViewById(R.id.city_empty)

        val adapter = ArrayAdapter<PickItem>(
            this, android.R.layout.simple_list_item_1, ArrayList(popularItems)
        )
        cityList.adapter = adapter

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.config_choose_city)
            .setView(view)
            .setNegativeButton(R.string.config_cancel, null)
            .create()

        val handler = Handler(mainLooper)
        searchBox.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun afterTextChanged(s: Editable?) {}
            override fun onTextChanged(s: CharSequence?, st: Int, before: Int, count: Int) {
                val query = s?.toString().orEmpty()
                handler.removeCallbacksAndMessages(null)
                handler.postDelayed({
                    val items = if (query.isBlank()) {
                        popularItems
                    } else {
                        CityRepository.search(query).map { it.toPickItem() }
                    }
                    adapter.clear()
                    adapter.addAll(items)
                    adapter.notifyDataSetChanged()
                }, SEARCH_DEBOUNCE_MS)
            }
        })

        cityList.setOnItemClickListener { _, _, position, _ ->
            val item = adapter.getItem(position) ?: return@setOnItemClickListener
            applyPick(row, item.label, item.zoneId)
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
            val cityName = city.label
            val displayLabel = row.labelInput.text?.toString()?.trim().orEmpty().ifEmpty { cityName }
            zones.add(ZoneEntry(label = displayLabel, zoneId = city.zoneId, format = format, cityName = cityName))
        }

        val appearance = Appearance(
            backgroundColor = bgChoices[bgColorSpinner.selectedItemPosition].color,
            opacityPercent = opacitySeek.progress,
            textColorMode = TextColorMode.values()[textColorSpinner.selectedItemPosition],
            textSize = TextSize.values()[textSizeSpinner.selectedItemPosition]
        )
        val config = WidgetConfig(
            zones,
            globalIs24h = globalFormatSpinner.selectedItemPosition == 1,
            appearance = appearance
        )
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

    private companion object {
        const val SEARCH_DEBOUNCE_MS = 140L
    }
}
