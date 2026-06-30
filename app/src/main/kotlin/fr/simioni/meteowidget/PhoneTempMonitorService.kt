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
            NotificationHelper.updatePhoneTempNotification(this@PhoneTempMonitorService, temp)
            handler.postDelayed(this, INTERVAL_MS)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notif = NotificationHelper.buildPhoneTempNotification(this, PhoneTemperature.read(this))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NotificationHelper.NOTIF_PHONE_TEMP_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NotificationHelper.NOTIF_PHONE_TEMP_ID, notif)
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
