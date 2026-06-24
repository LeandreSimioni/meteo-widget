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
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                NotificationHelper.NOTIF_SCAN_ID,
                notif,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            ForegroundInfo(NotificationHelper.NOTIF_SCAN_ID, notif)
        }
    }

    override suspend fun doWork(): Result {
        // Se déclare foreground pour survivre en arrière-plan
        setForeground(getForegroundInfo())

        Log.d(TAG, "Worker démarré")

        val indoorTemp = withContext(Dispatchers.IO) { scanBleForIndoorTemp() } ?: run {
            Log.w(TAG, "Pas de données BLE")
            NotificationHelper.showDebug(applicationContext, "Aucune donnée BLE (Aranet hors portée?)")
            return Result.success()
        }

        val outdoorTemp = withContext(Dispatchers.IO) {
            MeteocielFetcher.fetchOutdoorTemperature()
        } ?: run {
            Log.w(TAG, "Météo extérieure indisponible")
            saveTemps(indoorTemp, null)
            TemperatureWidgetProvider.updateAll(applicationContext)
            return Result.success()
        }

        val diff = indoorTemp - outdoorTemp
        Log.d(TAG, "Indoor=$indoorTemp Outdoor=$outdoorTemp Diff=$diff")

        saveTemps(indoorTemp, outdoorTemp)
        TemperatureWidgetProvider.updateAll(applicationContext)
        sendSmartNotification(indoorTemp, outdoorTemp, diff)

        return Result.success()
    }

    private fun sendSmartNotification(indoor: Float, outdoor: Float, diff: Float) {
        val prefs = Prefs.get(applicationContext)
        val prevState = prefs.getString(Prefs.KEY_LAST_STATE, Prefs.STATE_NONE) ?: Prefs.STATE_NONE
        val newState = when {
            diff > THRESHOLD -> Prefs.STATE_OPEN
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

    private fun saveTemps(indoor: Float, outdoor: Float?) {
        Prefs.get(applicationContext).edit()
            .putFloat(Prefs.KEY_INDOOR, indoor)
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

        applicationContext.startForegroundService(Intent(applicationContext, BleScanService::class.java))
        latch.await(BLE_TIMEOUT_SEC, TimeUnit.SECONDS)

        try { applicationContext.unregisterReceiver(receiver) } catch (_: Exception) {}
        return temperature
    }
}
