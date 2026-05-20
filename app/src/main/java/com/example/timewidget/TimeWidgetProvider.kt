package com.example.timewidget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.RemoteViews

/**
 * Renders each configured zone into a fixed set of rows (unused rows hidden). The
 * TextClock in every row ticks on its own once given a time zone, so we only re-render
 * when the configuration changes — no AlarmManager or periodic update needed.
 */
class TimeWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (appWidgetId in appWidgetIds) {
            renderWidget(context, appWidgetManager, appWidgetId)
        }
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        for (appWidgetId in appWidgetIds) {
            WidgetPrefs.delete(context, appWidgetId)
        }
    }

    companion object {
        private val ROW_IDS = intArrayOf(R.id.row_0, R.id.row_1, R.id.row_2, R.id.row_3)
        private val LABEL_IDS = intArrayOf(R.id.label_0, R.id.label_1, R.id.label_2, R.id.label_3)
        private val CLOCK_IDS = intArrayOf(R.id.clock_0, R.id.clock_1, R.id.clock_2, R.id.clock_3)

        private const val PATTERN_12H = "h:mm a"
        private const val PATTERN_24H = "HH:mm"

        /** Builds and pushes the RemoteViews for one widget instance from its saved config. */
        fun renderWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int
        ) {
            val views = RemoteViews(context.packageName, R.layout.time_widget)
            val config = WidgetPrefs.load(context, appWidgetId)
            val zones = config?.zones.orEmpty()

            if (zones.isEmpty()) {
                ROW_IDS.forEach { views.setViewVisibility(it, View.GONE) }
                views.setViewVisibility(R.id.empty_hint, View.VISIBLE)
            } else {
                views.setViewVisibility(R.id.empty_hint, View.GONE)
                for (i in ROW_IDS.indices) {
                    if (i < zones.size) {
                        val zone = zones[i]
                        val is24h = when (zone.format) {
                            HourFormat.H24 -> true
                            HourFormat.H12 -> false
                            HourFormat.DEFAULT -> config?.globalIs24h ?: false
                        }
                        // TextClock picks format12Hour or format24Hour based on the system
                        // setting; set both to the same pattern to force our choice.
                        val pattern = if (is24h) PATTERN_24H else PATTERN_12H
                        views.setViewVisibility(ROW_IDS[i], View.VISIBLE)
                        views.setTextViewText(LABEL_IDS[i], zone.label)
                        views.setString(CLOCK_IDS[i], "setTimeZone", zone.zoneId)
                        views.setCharSequence(CLOCK_IDS[i], "setFormat12Hour", pattern)
                        views.setCharSequence(CLOCK_IDS[i], "setFormat24Hour", pattern)
                    } else {
                        views.setViewVisibility(ROW_IDS[i], View.GONE)
                    }
                }
            }

            views.setOnClickPendingIntent(R.id.widget_root, configPendingIntent(context, appWidgetId))
            appWidgetManager.updateAppWidget(appWidgetId, views)
        }

        /** Tapping the widget reopens its configuration screen for this instance. */
        private fun configPendingIntent(context: Context, appWidgetId: Int): PendingIntent {
            val intent = Intent(context, WidgetConfigActivity::class.java).apply {
                action = AppWidgetManager.ACTION_APPWIDGET_CONFIGURE
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                // Unique data so each widget's PendingIntent is distinct.
                data = Uri.parse("timewidget://configure/$appWidgetId")
            }
            return PendingIntent.getActivity(
                context,
                appWidgetId,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
    }
}
