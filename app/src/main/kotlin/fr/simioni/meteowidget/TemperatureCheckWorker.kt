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
            // API 34+: SHORT_SERVICE n'a aucun prérequis Bluetooth, parfait pour un Worker de ~20s
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
    }

    override suspend fun doWork(): Result {
        setForeground(getForegroundInfo())
        log("Démarré")

        val indoorTemp = withContext(Dispatchers.IO) { scanBleForIndoorTemp() }
        if (indoorTemp == null) log("Aranet hors portée — on continue avec Meteociel")

        val outdoorTemp = withContext(Dispatchers.IO) { MeteocielFetcher.fetchOutdoorTemperature(applicationContext) }
        if (outdoorTemp == null) log("Meteociel indisponible")

        if (indoorTemp == null && outdoorTemp == null) {
            log("Aucune donnée disponible")
            return Result.success()
        }

        log("Indoor=${indoorTemp?.let { "%.1f°C".format(it) } ?: "N/A"} Outdoor=${outdoorTemp?.let { "%.1f°C".format(it) } ?: "N/A"}")
        saveTemps(indoorTemp, outdoorTemp)
        TemperatureWidgetProvider.updateAll(applicationContext)

        val openWindows: Boolean? = if (indoorTemp != null && outdoorTemp != null) {
            val diff = indoorTemp - outdoorTemp
            sendSmartNotification(indoorTemp, outdoorTemp, diff)
            when {
                diff > THRESHOLD  -> true
                diff < -THRESHOLD -> false
                else -> null
            }
        } else null

        NotificationHelper.updateStatusNotification(applicationContext, indoorTemp, outdoorTemp, openWindows)

        return Result.success()
    }

    private fun sendSmartNotification(indoor: Float, outdoor: Float, diff: Float) {
        val prefs = Prefs.get(applicationContext)
        val prevState = prefs.getString(Prefs.KEY_LAST_STATE, Prefs.STATE_NONE) ?: Prefs.STATE_NONE
        val newState = when {
            diff > THRESHOLD  -> Prefs.STATE_OPEN
            diff < -THRESHOLD -> Prefs.STATE_CLOSE
            else -> Prefs.STATE_NONE
        }
        if (newState != Prefs.STATE_NONE && newState != prevState) {
            NotificationHelper.showTemperatureAlert(
                applicationContext, indoor, outdoor,
                openWindows = (newState == Prefs.STATE_OPEN)
            )
        }
        prefs.edit().putString(Prefs.KEY_LAST_STATE, newState).apply()
    }

    private fun saveTemps(indoor: Float?, outdoor: Float?) {
        Prefs.get(applicationContext).edit()
            .apply { if (indoor != null) putFloat(Prefs.KEY_INDOOR, indoor) }
            .apply { if (outdoor != null) putFloat(Prefs.KEY_OUTDOOR, outdoor) }
            .apply()
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
