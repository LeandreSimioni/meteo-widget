package fr.simioni.meteowidget

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper

// Service foreground dédié, séparé du cycle de 15 min (BLE + Meteociel) qui est trop lent
// pour suivre une surchauffe en cours de tournage : ne fait que lire/loguer/notifier la
// température de la batterie toutes les minutes, sans réseau ni BLE.
class PhoneTempMonitorService : Service() {
    companion object {
        private const val INTERVAL_MS = 60_000L

        // Mêmes règles de péremption que le Worker : la notification ne doit pas
        // afficher comme actuelle une valeur que l'app juge trop vieille pour agir.
        private fun freshIndoor(ctx: Service): Float? =
            Prefs.getIndoor(ctx)?.takeIf { it.isFresh(Reading.MAX_AGE_INDOOR_MS) }?.value

        private fun freshOutdoor(ctx: Service): Float? =
            Prefs.getOutdoor(ctx)?.takeIf { it.isFresh(Reading.MAX_AGE_OUTDOOR_MS) }?.value

        private fun currentState(ctx: Service): String =
            Prefs.get(ctx).getString(Prefs.KEY_LAST_STATE, Prefs.STATE_NONE) ?: Prefs.STATE_NONE
    }

    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            val temp = PhoneTemperature.read(this@PhoneTempMonitorService)
            if (temp != null) {
                val msg = "[Téléphone] Température lue → %.1f°C".format(temp)
                LogStore.append(this@PhoneTempMonitorService, msg)
                sendBroadcast(Intent(BleScanService.ACTION_LOG).apply {
                    setPackage(packageName)
                    putExtra(BleScanService.EXTRA_LOG_MSG, msg)
                })
            }
            // Intérieur/extérieur restent sur leur cycle de 15 min (Aranet + Meteociel) —
            // on relit juste la dernière valeur connue, sans déclencher de nouvelle mesure.
            val ctx = this@PhoneTempMonitorService
            NotificationHelper.updateStatusNotification(
                ctx, temp, freshIndoor(ctx), freshOutdoor(ctx), currentState(ctx)
            )
            handler.postDelayed(this, INTERVAL_MS)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notif = NotificationHelper.buildStatusNotification(
            this, PhoneTemperature.read(this), freshIndoor(this), freshOutdoor(this), currentState(this)
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NotificationHelper.NOTIF_STATUS_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NotificationHelper.NOTIF_STATUS_ID, notif)
        }
        handler.removeCallbacks(tick)
        handler.post(tick)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(tick)
    }
}
