package fr.simioni.meteowidget

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat

object NotificationHelper {
    const val CHANNEL_SCAN = "ble_scan"
    const val CHANNEL_ALERT = "temp_alert"
    const val CHANNEL_DEBUG = "debug"

    const val NOTIF_SCAN_ID = 1
    const val NOTIF_ALERT_ID = 2
    const val NOTIF_DEBUG_ID = 3

    fun createChannels(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_SCAN, "Scan BLE", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERT, "Alertes température", NotificationManager.IMPORTANCE_DEFAULT)
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_DEBUG, "Debug", NotificationManager.IMPORTANCE_LOW)
        )
    }

    fun buildScanNotification(context: Context): Notification =
        NotificationCompat.Builder(context, CHANNEL_SCAN)
            .setContentTitle("Meteo Widget")
            .setContentText("Lecture BLE en cours...")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .build()

    fun showTemperatureAlert(context: Context, indoor: Float, outdoor: Float, openWindows: Boolean) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val pi = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val msg = if (openWindows)
            "Ouvrir les fenêtres : %.1f°C dehors, %.1f°C dedans".format(outdoor, indoor)
        else
            "Fermer les fenêtres : %.1f°C dehors, %.1f°C dedans".format(outdoor, indoor)

        nm.notify(NOTIF_ALERT_ID,
            NotificationCompat.Builder(context, CHANNEL_ALERT)
                .setContentTitle("Meteo Widget")
                .setContentText(msg)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
        )
    }

    fun showDebug(context: Context, msg: String) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_DEBUG_ID,
            NotificationCompat.Builder(context, CHANNEL_DEBUG)
                .setContentTitle("Debug")
                .setContentText(msg)
                .setStyle(NotificationCompat.BigTextStyle().bigText(msg))
                .setSmallIcon(android.R.drawable.ic_menu_info_details)
                .setAutoCancel(true)
                .build()
        )
    }
}
