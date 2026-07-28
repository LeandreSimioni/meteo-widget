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
        private const val COLOR_INDOOR = 0xFFFFFFFF.toInt()
        private const val COLOR_OUTDOOR = 0xFF90CAF9.toInt()
        private const val COLOR_STALE = 0xFF6E6E6E.toInt()

        private fun format(reading: Reading?): String =
            if (reading == null) "--°C" else "%.1f°C".format(reading.value)

        private fun tint(reading: Reading?, maxAgeMs: Long, fresh: Int): Int =
            if (reading != null && reading.isFresh(maxAgeMs)) fresh else COLOR_STALE

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
            val indoor = Prefs.getIndoor(context)
            val outdoor = Prefs.getOutdoor(context)
            val state = prefs.getString(Prefs.KEY_LAST_STATE, Prefs.STATE_NONE)

            val views = RemoteViews(context.packageName, R.layout.widget_meteo)
            views.setTextViewText(R.id.widgetIndoor, format(indoor))
            views.setTextViewText(R.id.widgetOutdoor, format(outdoor))
            views.setTextViewText(R.id.widgetOutdoorLabel,
                "extérieur · ${Prefs.getLocation(context).label}")
            views.setTextViewText(R.id.widgetStatus, when (state) {
                Prefs.STATE_OPEN -> "↑ Ouvrir"
                Prefs.STATE_CLOSE -> "↓ Fermer"
                else -> ""
            })
            val oldest = listOfNotNull(indoor, outdoor).maxByOrNull { it.ageMs() }
            views.setTextViewText(R.id.widgetAge, oldest?.let { Reading.formatAge(it.ageMs()) } ?: "")

            // Une valeur périmée reste affichée — elle renseigne encore — mais elle
            // est grisée pour qu'on ne la lise pas comme la température actuelle.
            views.setTextColor(R.id.widgetIndoor, tint(indoor, Reading.MAX_AGE_INDOOR_MS, COLOR_INDOOR))
            views.setTextColor(R.id.widgetOutdoor, tint(outdoor, Reading.MAX_AGE_OUTDOOR_MS, COLOR_OUTDOOR))

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
