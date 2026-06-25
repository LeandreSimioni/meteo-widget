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
    const val CHANNEL_STATUS = "temp_status_v2" // v2 = importance DEFAULT (l'ancien était LOW/silencieux)

    const val NOTIF_SCAN_ID = 1
    const val NOTIF_STATUS_ID = 3
    private const val NOTIF_ALERT_ID_LEGACY = 2

    fun createChannels(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_SCAN, "Scan BLE", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_STATUS, "Statut température", NotificationManager.IMPORTANCE_DEFAULT)
        )
        // Supprimer l'ancien canal silencieux (Android ignore si inexistant)
        nm.deleteNotificationChannel("temp_status")
    }

    fun buildScanNotification(context: Context): Notification =
        NotificationCompat.Builder(context, CHANNEL_SCAN)
            .setContentTitle("Meteo Widget")
            .setContentText("Lecture BLE en cours...")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .build()

    fun updateStatusNotification(context: Context, indoor: Float?, outdoor: Float?, openWindows: Boolean?) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(NOTIF_ALERT_ID_LEGACY) // supprime l'ancienne alerte transiente si présente
        val pi = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val indoorStr = if (indoor != null) "%.1f°C dedans".format(indoor) else "-- dedans"
        val outdoorStr = if (outdoor != null) "%.1f°C dehors".format(outdoor) else "-- dehors"
        val (title, icon) = when (openWindows) {
            true  -> Pair("↑ Ouvrir les fenêtres", android.R.drawable.arrow_up_float)
            false -> Pair("↓ Fermer les fenêtres", android.R.drawable.arrow_down_float)
            null  -> Pair("Meteo Widget · en attente", android.R.drawable.ic_menu_compass)
        }
        nm.notify(NOTIF_STATUS_ID,
            NotificationCompat.Builder(context, CHANNEL_STATUS)
                .setContentTitle(title)
                .setContentText("$outdoorStr · $indoorStr")
                .setSmallIcon(icon)
                .setContentIntent(pi)
                .setOngoing(true)
                .setOnlyAlertOnce(true) // pas de son à chaque mise à jour des 15 min
                .build()
        )
    }
}
