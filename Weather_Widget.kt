package com.example.weatherapp

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.widget.RemoteViews
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

private const val WIDGET_PREFS = "weather_widget_prefs"

class WeatherWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (id in appWidgetIds) updateAppWidget(context, appWidgetManager, id)
        WorkManager.getInstance(context).enqueue(OneTimeWorkRequestBuilder<WeatherWidgetWorker>().build())
    }

    companion object {
        fun updateAppWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
            val views = RemoteViews(context.packageName, R.layout.weather_widget_layout)
            val prefs = context.getSharedPreferences(WIDGET_PREFS, Context.MODE_PRIVATE)
            views.setTextViewText(R.id.widget_temperature, prefs.getString("temperature", "--°"))
            views.setTextViewText(R.id.widget_condition, prefs.getString("condition", "Loading..."))
            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }
}

/**
 * Fetches fresh weather data and updates the widget. Requires location
 * permission to already be granted via the main app - if it never was,
 * this fails gracefully and the widget keeps showing its last known values.
 */
class WeatherWidgetWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        return try {
            val location = getCurrentLocation(applicationContext) ?: return Result.failure()
            val weather = WeatherApi.service.getCurrentWeather(location.first, location.second, WeatherApi.API_KEY)

            applicationContext.getSharedPreferences(WIDGET_PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString("temperature", "${weather.main.temp.toInt()}°C")
                .putString(
                    "condition",
                    weather.weather.firstOrNull()?.description?.replaceFirstChar { it.uppercase() } ?: "Unknown"
                )
                .apply()

            val appWidgetManager = AppWidgetManager.getInstance(applicationContext)
            val componentName = ComponentName(applicationContext, WeatherWidgetProvider::class.java)
            for (id in appWidgetManager.getAppWidgetIds(componentName)) {
                WeatherWidgetProvider.updateAppWidget(applicationContext, appWidgetManager, id)
            }
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }
}

/** Android enforces a minimum periodic interval of 15 minutes; 30 balances freshness vs. battery. */
fun scheduleWidgetUpdates(context: Context) {
    val request = PeriodicWorkRequestBuilder<WeatherWidgetWorker>(30, TimeUnit.MINUTES).build()
    WorkManager.getInstance(context).enqueueUniquePeriodicWork(
        "weather_widget_periodic_update", ExistingPeriodicWorkPolicy.KEEP, request
    )
}
