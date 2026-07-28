package fr.simioni.meteowidget

import android.content.Context
import androidx.work.BackoffPolicy
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
        val oneTime = OneTimeWorkRequestBuilder<TemperatureCheckWorker>()
            // Ce remplacement annule le cycle en cours ; le nouveau trouve alors le
            // verrou pris et demande à être relancé. Backoff court pour que ça se
            // rejoue en quelques secondes et pas dans une demi-minute.
            .setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            WORK_NAME_NOW, ExistingWorkPolicy.REPLACE, oneTime
        )
    }
}
