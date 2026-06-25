package fr.simioni.meteowidget

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object WorkScheduler {
    private const val WORK_NAME = "temp_check"
    private const val WORK_NAME_NOW = "temp_check_now"

    // Enregistre le cycle périodique (idempotent — ne relance pas si déjà actif)
    fun schedule(context: Context) {
        val periodic = PeriodicWorkRequestBuilder<TemperatureCheckWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, periodic
        )
    }

    // Force un cycle immédiat (remplace toute tâche one-shot en attente)
    fun runNow(context: Context) {
        val oneTime = OneTimeWorkRequestBuilder<TemperatureCheckWorker>().build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            WORK_NAME_NOW, ExistingWorkPolicy.REPLACE, oneTime
        )
    }
}
