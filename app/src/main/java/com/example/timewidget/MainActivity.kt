package com.example.timewidget

import android.app.Activity
import android.os.Bundle

/**
 * A minimal host screen. The app's real feature is the home-screen widget
 * (see [TimeWidgetProvider]); this Activity exists so the app has a normal
 * launcher entry point and a future home for widget settings.
 */
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
    }
}
