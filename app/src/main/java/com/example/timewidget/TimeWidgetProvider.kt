package com.example.timewidget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.widget.RemoteViews

/**
 * The TextClock views in the layout tick on their own, so onUpdate only needs to
 * inflate the layout once per widget instance — no AlarmManager or periodic refresh.
 */
class TimeWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (appWidgetId in appWidgetIds) {
            val views = RemoteViews(context.packageName, R.layout.time_widget)
            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }
}
