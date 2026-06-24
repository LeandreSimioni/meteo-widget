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
        const val BLE_TIMEOUT_SEC = 10L
    }

    override fun doWork(): Result {
        Log.d(TAG, "Worker triggered")

        val indoorTemp = scanBleForIndoorTemp() ?: run {
            Log.w(TAG, "No BLE result received within ${BLE_TIMEOUT_SEC}s")
            NotificationHelper.showDebug(applicationContext, "Aucune donnée BLE reçue (Aranet hors portée ?)")
            return Result.success()
        }

        val outdoorTemp = MeteocielFetcher.fetchOutdoorTemperature() ?: run {
            Log.w(TAG, "Outdoor temperature unavailable")
            NotificationHelper.showDebug(applicationContext,
                "Indoor: %.1f°C | Météo extérieure indisponible".format(indoorTemp))
            return Result.success()
        }

        val diff = indoorTemp - outdoorTemp
        Log.d(TAG, "Indoor: ${indoorTemp}°C | Outdoor: ${outdoorTemp}°C | Diff: ${diff}°C")
        NotificationHelper.showDebug(applicationContext,
            "Indoor: %.1f°C | Outdoor: %.1f°C | Écart: %+.1f°C".format(indoorTemp, outdoorTemp, diff))

        when {
            diff > THRESHOLD ->
                NotificationHelper.showTemperatureAlert(applicationContext, indoorTemp, outdoorTemp, openWindows = true)
            diff < -THRESHOLD ->
                NotificationHelper.showTemperatureAlert(applicationContext, indoorTemp, outdoorTemp, openWindows = false)
        }

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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            applicationContext.registerReceiver(
                receiver,
                IntentFilter(BleScanService.ACTION_RESULT),
                Context.RECEIVER_NOT_EXPORTED
            )
        } else {
            applicationContext.registerReceiver(receiver, IntentFilter(BleScanService.ACTION_RESULT))
        }

        applicationContext.startForegroundService(Intent(applicationContext, BleScanService::class.java))
        latch.await(BLE_TIMEOUT_SEC, TimeUnit.SECONDS)

        try { applicationContext.unregisterReceiver(receiver) } catch (_: Exception) {}
        return temperature
    }
}
