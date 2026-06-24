package fr.simioni.meteowidget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class TemperatureCheckWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    companion object {
        const val TAG = "TempCheckWorker"
        const val THRESHOLD = 1.0f
        const val BLE_TIMEOUT_SEC = 15L
    }

    override fun doWork(): Result {
        Log.d(TAG, "Worker démarré")

        val indoorTemp = scanBleForIndoorTemp() ?: run {
            Log.w(TAG, "Pas de données BLE")
            broadcast("Aucune donnée BLE reçue (Aranet hors portée?)")
            return Result.success()
        }

        val outdoorTemp = MeteocielFetcher.fetchOutdoorTemperature() ?: run {
            Log.w(TAG, "Météo extérieure indisponible")
            broadcast("Indoor: %.1f°C | Météo extérieure indisponible".format(indoorTemp))
            saveTemps(indoorTemp, null)
            return Result.success()
        }

        val diff = indoorTemp - outdoorTemp
        Log.d(TAG, "Indoor=$indoorTemp Outdoor=$outdoorTemp Diff=$diff")
        broadcast("Indoor: %.1f°C | Outdoor: %.1f°C | Écart: %+.1f°C".format(indoorTemp, outdoorTemp, diff))

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

        // Notifier uniquement si l'état change
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

    private fun broadcast(msg: String) {
        NotificationHelper.showDebug(applicationContext, msg)
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
