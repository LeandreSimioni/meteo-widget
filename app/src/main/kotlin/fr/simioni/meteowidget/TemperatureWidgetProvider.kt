package fr.simioni.meteowidget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

class TemperatureWidgetProvider : AppWidgetProvider() {

    companion object {
        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(
                ComponentName(context, TemperatureWidgetProvider::class.java))
            if (ids.isNotEmpty()) {
                val views = buildViews(context)
                ids.forEach { manager.updateAppWidget(it, views) }
            }
        }

        fun buildViews(context: Context): RemoteViews {
            val prefs = Prefs.get(context)
            val indoor = prefs.getFloat(Prefs.KEY_INDOOR, Float.NaN)
            val outdoor = prefs.getFloat(Prefs.KEY_OUTDOOR, Float.NaN)
            val state = prefs.getString(Prefs.KEY_LAST_STATE, Prefs.STATE_NONE)

            val views = RemoteViews(context.packageName, R.layout.widget_meteo)
            views.setTextViewText(R.id.widgetIndoor,
                if (indoor.isNaN()) "--°C" else "%.1f°C".format(indoor))
            views.setTextViewText(R.id.widgetOutdoor,
                if (outdoor.isNaN()) "--°C" else "%.1f°C".format(outdoor))
            views.setTextViewText(R.id.widgetStatus, when (state) {
                Prefs.STATE_OPEN -> "↑ Ouvrir"
                Prefs.STATE_CLOSE -> "↓ Fermer"
                else -> ""
            })

            val pi = PendingIntent.getActivity(
                context, 0,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widgetRoot, pi)
            return views
        }
    }

    override fun onEnabled(context: Context) {
        // Premier widget ajouté à l'écran d'accueil : démarre WorkManager automatiquement
        WorkScheduler.schedule(context)
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        // S'assure que WorkManager tourne toujours (ex: après redémarrage)
        WorkScheduler.schedule(context)
        val views = buildViews(context)
        appWidgetIds.forEach { appWidgetManager.updateAppWidget(it, views) }
    }
}
