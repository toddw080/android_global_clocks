package com.example.timewidget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.RemoteViews
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Renders each configured zone into a fixed set of rows (unused rows hidden). The TextClock in
 * every row ticks on its own once given a time zone, so the time never needs a refresh. The
 * per-row day badge (+1 / -1) is static, though, so we re-render at the next relevant midnight
 * via an alarm (see [scheduleNextMidnight]) and on boot / time / time-zone changes.
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
        scheduleNextMidnight(context)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        for (appWidgetId in appWidgetIds) {
            WidgetPrefs.delete(context, appWidgetId)
        }
        scheduleNextMidnight(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent) // dispatches APPWIDGET_UPDATE/DELETED to onUpdate/onDeleted
        when (intent.action) {
            ACTION_DAY_REFRESH,
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED -> {
                renderAll(context)
                scheduleNextMidnight(context)
            }
        }
    }

    companion object {
        private const val ACTION_DAY_REFRESH = "com.example.timewidget.ACTION_DAY_REFRESH"

        private val ROW_IDS = intArrayOf(R.id.row_0, R.id.row_1, R.id.row_2, R.id.row_3)
        private val LABEL_IDS = intArrayOf(R.id.label_0, R.id.label_1, R.id.label_2, R.id.label_3)
        private val CLOCK_IDS = intArrayOf(R.id.clock_0, R.id.clock_1, R.id.clock_2, R.id.clock_3)
        private val BADGE_IDS = intArrayOf(R.id.badge_0, R.id.badge_1, R.id.badge_2, R.id.badge_3)

        private const val PATTERN_12H = "h:mm a"
        private const val PATTERN_24H = "HH:mm"

        /** Re-render every placed instance (used by alarm / boot / time-change). */
        fun renderAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            for (id in placedIds(context, manager)) {
                renderWidget(context, manager, id)
            }
        }

        /** Builds and pushes the RemoteViews for one widget instance from its saved config. */
        fun renderWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int
        ) {
            val views = RemoteViews(context.packageName, R.layout.time_widget)
            val config = WidgetPrefs.load(context, appWidgetId)
            val zones = config?.zones.orEmpty()
            val localDate = LocalDate.now()

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

                        // Day badge: how many calendar days this zone is ahead/behind "today".
                        val offset = dayOffset(zone.zoneId, localDate)
                        if (offset == 0) {
                            views.setViewVisibility(BADGE_IDS[i], View.GONE)
                        } else {
                            views.setViewVisibility(BADGE_IDS[i], View.VISIBLE)
                            views.setTextViewText(BADGE_IDS[i], if (offset > 0) "+$offset" else "$offset")
                        }
                    } else {
                        views.setViewVisibility(ROW_IDS[i], View.GONE)
                    }
                }
            }

            views.setOnClickPendingIntent(R.id.widget_root, configPendingIntent(context, appWidgetId))
            appWidgetManager.updateAppWidget(appWidgetId, views)
        }

        /** Calendar-day difference between [zoneId]'s current date and the device's [localDate]. */
        private fun dayOffset(zoneId: String, localDate: LocalDate): Int =
            runCatching {
                (LocalDate.now(ZoneId.of(zoneId)).toEpochDay() - localDate.toEpochDay()).toInt()
            }.getOrDefault(0)

        /**
         * Arm an alarm for the next upcoming midnight among the device zone and all displayed
         * zones — that's exactly when some row's day badge can change. Re-armed on each fire,
         * so it only wakes a few times a day rather than polling.
         */
        private fun scheduleNextMidnight(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val zones = HashSet<ZoneId>()
            zones.add(ZoneId.systemDefault())
            for (id in placedIds(context, manager)) {
                WidgetPrefs.load(context, id)?.zones?.forEach { z ->
                    runCatching { ZoneId.of(z.zoneId) }.getOrNull()?.let { zones.add(it) }
                }
            }

            var next = Long.MAX_VALUE
            for (z in zones) {
                val midnight = LocalDate.now(z).plusDays(1).atStartOfDay(z).toInstant().toEpochMilli()
                if (midnight < next) next = midnight
            }
            if (next == Long.MAX_VALUE) return

            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            // Inexact alarm (no special permission); a few minutes' slack at midnight is fine.
            alarmManager.set(AlarmManager.RTC, next + 1_000L, alarmPendingIntent(context))
        }

        private fun placedIds(context: Context, manager: AppWidgetManager): IntArray =
            manager.getAppWidgetIds(ComponentName(context, TimeWidgetProvider::class.java))

        private fun alarmPendingIntent(context: Context): PendingIntent {
            val intent = Intent(context, TimeWidgetProvider::class.java).setAction(ACTION_DAY_REFRESH)
            return PendingIntent.getBroadcast(
                context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        /** Tapping the widget reopens its configuration screen for this instance. */
        private fun configPendingIntent(context: Context, appWidgetId: Int): PendingIntent {
            val intent = Intent(context, WidgetConfigActivity::class.java).apply {
                action = AppWidgetManager.ACTION_APPWIDGET_CONFIGURE
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
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
