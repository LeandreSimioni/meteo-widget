package fr.simioni.meteowidget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class TemperatureCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    companion object {
        const val TAG = "TempCheckWorker"
        const val THRESHOLD = 1.0f
        const val BLE_TIMEOUT_SEC = 15L
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val notif = NotificationHelper.buildScanNotification(applicationContext)
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ->
                ForegroundInfo(NotificationHelper.NOTIF_SCAN_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
                ForegroundInfo(NotificationHelper.NOTIF_SCAN_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            else ->
                ForegroundInfo(NotificationHelper.NOTIF_SCAN_ID, notif)
        }
    }

    private fun log(msg: String) {
        Log.d(TAG, msg)
        LogStore.append(applicationContext, "[Worker] $msg")
        applicationContext.sendBroadcast(Intent(BleScanService.ACTION_LOG).apply {
            setPackage(applicationContext.packageName)
            putExtra(BleScanService.EXTRA_LOG_MSG, "[Worker] $msg")
        })
    }

    override suspend fun doWork(): Result {
        setForeground(getForegroundInfo())
        log("Démarré")

        val freshIndoor = withContext(Dispatchers.IO) { scanBleForIndoorTemp() }
        val freshOutdoor = withContext(Dispatchers.IO) { MeteocielFetcher.fetchOutdoorTemperature(applicationContext) }

        val prefs = Prefs.get(applicationContext)

        // Persist fresh readings (ne pas écraser si null)
        prefs.edit().apply {
            if (freshIndoor != null) putFloat(Prefs.KEY_INDOOR, freshIndoor)
            if (freshOutdoor != null) putFloat(Prefs.KEY_OUTDOOR, freshOutdoor)
        }.apply()

        // Utiliser la dernière valeur connue si la lecture fraîche a échoué
        val indoor = freshIndoor ?: prefs.getFloat(Prefs.KEY_INDOOR, Float.NaN).takeIf { !it.isNaN() }
        val outdoor = freshOutdoor ?: prefs.getFloat(Prefs.KEY_OUTDOOR, Float.NaN).takeIf { !it.isNaN() }

        if (freshIndoor == null) log("Aranet hors portée${if (indoor != null) " — dernière valeur: %.1f°C".format(indoor) else ""}")
        if (freshOutdoor == null) log("Meteociel indisponible${if (outdoor != null) " — dernière valeur: %.1f°C".format(outdoor) else ""}")

        if (indoor == null && outdoor == null) {
            log("Aucune donnée disponible")
            return Result.success()
        }

        val openWindows: Boolean? = if (indoor != null && outdoor != null) {
            val diff = indoor - outdoor
            val state = when {
                diff > THRESHOLD  -> Prefs.STATE_OPEN
                diff < -THRESHOLD -> Prefs.STATE_CLOSE
                else              -> Prefs.STATE_NONE
            }
            log("%.1f°C dedans · %.1f°C dehors → %s".format(indoor, outdoor, state))
            prefs.edit().putString(Prefs.KEY_LAST_STATE, state).apply()
            when (state) { Prefs.STATE_OPEN -> true; Prefs.STATE_CLOSE -> false; else -> null }
        } else null

        NotificationHelper.updateStatusNotification(applicationContext, indoor, outdoor, openWindows)
        TemperatureWidgetProvider.updateAll(applicationContext)
        return Result.success()
    }

    private fun scanBleForIndoorTemp(): Float? {
        val latch = CountDownLatch(1)
        var temperature: Float? = null

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val t = intent.getFloatExtra(BleScanService.EXTRA_TEMPERATURE, Float.NaN)
                if (!t.isNaN()) temperature = t
                latch.countDown()
            }
        }

        val filter = IntentFilter(BleScanService.ACTION_RESULT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            applicationContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            applicationContext.registerReceiver(receiver, filter)
        }

        try {
            applicationContext.startForegroundService(Intent(applicationContext, BleScanService::class.java))
        } catch (e: Exception) {
            Log.e(TAG, "Impossible de démarrer BleScanService: ${e.message}")
            try { applicationContext.unregisterReceiver(receiver) } catch (_: Exception) {}
            return null
        }

        latch.await(BLE_TIMEOUT_SEC, TimeUnit.SECONDS)
        try { applicationContext.unregisterReceiver(receiver) } catch (_: Exception) {}
        return temperature
    }
}
