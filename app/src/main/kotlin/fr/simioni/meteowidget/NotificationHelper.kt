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

    // v3 : canal unique. Les deux précédents (statut + température téléphone) affichaient
    // deux notifications côte à côte pour la même information. Nouvel identifiant car
    // Android ignore un changement d'importance sur un canal déjà créé.
    const val CHANNEL_STATUS = "temp_status_v3"

    const val NOTIF_SCAN_ID = 1
    const val NOTIF_STATUS_ID = 4

    // Notifications d'anciennes versions, à effacer au démarrage.
    private val LEGACY_NOTIF_IDS = intArrayOf(2, 3)
    private val LEGACY_CHANNELS = arrayOf("temp_status", "temp_status_v2", "phone_temp")

    fun createChannels(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_SCAN, "Scan BLE", NotificationManager.IMPORTANCE_LOW)
        )
        // DEFAULT et non LOW : la notification reste muette en temps normal
        // (setOnlyAlertOnce), mais doit pouvoir sonner sur un changement de conseil.
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_STATUS, "Températures", NotificationManager.IMPORTANCE_DEFAULT)
        )
        LEGACY_NOTIF_IDS.forEach { nm.cancel(it) }
        LEGACY_CHANNELS.forEach { nm.deleteNotificationChannel(it) }
    }

    fun buildScanNotification(context: Context): Notification =
        NotificationCompat.Builder(context, CHANNEL_SCAN)
            .setContentTitle("Meteo Widget")
            .setContentText("Lecture BLE en cours...")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .build()

    /**
     * L'unique notification de l'app : les trois températures, plus une flèche
     * quand il y a quelque chose à faire des fenêtres.
     *
     * Elle sert aussi de notification de service foreground à
     * [PhoneTempMonitorService], d'où l'absence de sous-texte : le titre doit se
     * suffire à lui-même dans la barre.
     */
    fun buildStatusNotification(
        context: Context,
        phoneC: Float?,
        indoorC: Float?,
        outdoorC: Float?,
        state: String = Prefs.STATE_NONE,
        alert: Boolean = false,
    ): Notification {
        val pi = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        fun fmt(v: Float?) = if (v != null) "%.1f°C".format(v) else "--°C"

        // Flèche en tête de ligne : c'est l'information qui demande une action,
        // elle doit être lue en premier et survivre à une troncature du titre.
        val (arrow, icon) = when (state) {
            Prefs.STATE_OPEN -> "↑ " to android.R.drawable.arrow_up_float
            Prefs.STATE_CLOSE -> "↓ " to android.R.drawable.arrow_down_float
            else -> "" to android.R.drawable.ic_lock_idle_low_battery
        }
        val title = "$arrow📱 ${fmt(phoneC)}   🏠 ${fmt(indoorC)}   🌳 ${fmt(outdoorC)}"

        return NotificationCompat.Builder(context, CHANNEL_STATUS)
            .setContentTitle(title)
            .setSmallIcon(icon)
            .setContentIntent(pi)
            .setOngoing(true)
            .setShowWhen(false)
            .setOnlyAlertOnce(!alert) // son uniquement sur changement de conseil
            .build()
    }

    fun updateStatusNotification(
        context: Context,
        phoneC: Float?,
        indoorC: Float?,
        outdoorC: Float?,
        state: String = Prefs.STATE_NONE,
        alert: Boolean = false,
    ) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_STATUS_ID, buildStatusNotification(context, phoneC, indoorC, outdoorC, state, alert))
    }
}
