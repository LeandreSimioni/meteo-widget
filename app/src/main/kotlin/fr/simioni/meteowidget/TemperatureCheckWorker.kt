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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class TemperatureCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    companion object {
        const val TAG = "TempCheckWorker"
        const val BLE_TIMEOUT_SEC = 20L
        private val mutex = Mutex()
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
        if (!mutex.tryLock()) {
            Log.d(TAG, "Cycle déjà en cours, ignoré")
            LogStore.append(applicationContext, "[Worker] Cycle déjà en cours, ignoré")
            return Result.success()
        }
        try {
        return doWorkLocked()
        } finally {
            mutex.unlock()
        }
    }

    private suspend fun doWorkLocked(): Result {
        setForeground(getForegroundInfo())
        log("Démarré")

        val location = Prefs.getLocation(applicationContext)
        val freshIndoor = withContext(Dispatchers.IO) { scanBleForIndoorTemp() }
        val freshOutdoor = location.fetchOutdoorTemperature(applicationContext)

        val prefs = Prefs.get(applicationContext)

        // Persister les relevés frais (ne pas écraser si null)
        if (freshIndoor != null) Prefs.putIndoor(applicationContext, freshIndoor)
        if (freshOutdoor != null) Prefs.putOutdoor(applicationContext, freshOutdoor)

        // Se rabattre sur la dernière valeur connue — mais seulement si elle est
        // encore d'actualité. Une température d'il y a trois heures ne dit plus
        // rien de l'état des fenêtres maintenant.
        val indoor = usable(freshIndoor ?: Prefs.getIndoor(applicationContext), Reading.MAX_AGE_INDOOR_MS)
        val outdoor = usable(freshOutdoor ?: Prefs.getOutdoor(applicationContext), Reading.MAX_AGE_OUTDOOR_MS)

        if (freshIndoor == null) log("Aranet hors portée${describeFallback(indoor)}")
        if (freshOutdoor == null) log("${location.label} indisponible${describeFallback(outdoor)}")

        if (indoor == null && outdoor == null) {
            log("Aucune donnée exploitable")
            NotificationHelper.updateStatusNotification(applicationContext, null, null, null, false, location)
            TemperatureWidgetProvider.updateAll(applicationContext)
            return Result.success()
        }

        var stateChanged = false
        val openWindows: Boolean? = if (indoor != null && outdoor != null) {
            val previous = prefs.getString(Prefs.KEY_LAST_STATE, Prefs.STATE_NONE) ?: Prefs.STATE_NONE
            val advice = WindowAdvisor.advise(indoor.value, outdoor.value, previous)
            log("%.1f°C dedans · %.1f°C dehors → %s".format(indoor.value, outdoor.value, advice.state))
            stateChanged = advice.alert
            prefs.edit().putString(Prefs.KEY_LAST_STATE, advice.state).apply()
            when (advice.state) {
                Prefs.STATE_OPEN -> true
                Prefs.STATE_CLOSE -> false
                else -> null
            }
        } else null

        NotificationHelper.updateStatusNotification(applicationContext, indoor, outdoor, openWindows, stateChanged, location)
        TemperatureWidgetProvider.updateAll(applicationContext)
        return Result.success()
    }

    /** Écarte une mesure trop vieille pour fonder un conseil. */
    private fun usable(reading: Reading?, maxAgeMs: Long): Reading? {
        if (reading == null) return null
        if (reading.isFresh(maxAgeMs)) return reading
        log("Valeur périmée ignorée (%.1f°C, %s)".format(reading.value, Reading.formatAge(reading.ageMs())))
        return null
    }

    private fun describeFallback(reading: Reading?): String =
        if (reading != null) " — dernière valeur: %.1f°C (%s)".format(reading.value, Reading.formatAge(reading.ageMs()))
        else ""

    private fun scanBleForIndoorTemp(): Reading? {
        val latch = CountDownLatch(1)
        var reading: Reading? = null

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val t = intent.getFloatExtra(BleScanService.EXTRA_TEMPERATURE, Float.NaN)
                if (!t.isNaN()) {
                    // L'Aranet indique l'ancienneté de sa mesure : la trame captée
                    // maintenant peut dater de plusieurs minutes.
                    val ageSec = intent.getIntExtra(BleScanService.EXTRA_AGE_SEC, 0).coerceIn(0, 3600)
                    reading = Reading(t, System.currentTimeMillis() - ageSec * 1000L)
                }
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
        return reading
    }
}
