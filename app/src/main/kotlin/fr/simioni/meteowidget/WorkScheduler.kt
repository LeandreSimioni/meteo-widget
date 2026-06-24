package fr.simioni.meteowidget

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object WorkScheduler {
    private const val WORK_NAME = "temp_check"

    fun schedule(context: Context) {
        val periodic = PeriodicWorkRequestBuilder<TemperatureCheckWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, periodic
        )
    }

    fun runNow(context: Context) {
        val oneTime = OneTimeWorkRequestBuilder<TemperatureCheckWorker>().build()
        WorkManager.getInstance(context).enqueue(oneTime)
    }
}
