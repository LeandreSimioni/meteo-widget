package fr.simioni.meteowidget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
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

            val phoneTemp = getPhoneTemperature(context)
            views.setTextViewText(R.id.widgetPhoneTemp,
                if (phoneTemp == null) "" else "📱%.0f°C".format(phoneTemp))

            val pi = PendingIntent.getActivity(
                context, 0,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widgetRoot, pi)
            return views
        }

        // Android n'expose pas de capteur de température ambiante fiable sur la plupart
        // des téléphones : on lit la température de la batterie (sonde toujours présente),
        // qui reflète la chaleur de l'appareil et non celle de la pièce.
        private fun getPhoneTemperature(context: Context): Float? {
            val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val tenths = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
            return if (tenths <= 0) null else tenths / 10f
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
