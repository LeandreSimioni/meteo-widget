package fr.simioni.meteowidget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            WorkScheduler.schedule(context)
            WorkScheduler.runNow(context)
            context.startForegroundService(Intent(context, PhoneTempMonitorService::class.java))
        }
    }
}
