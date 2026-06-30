package fr.simioni.meteowidget

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager

// Android n'expose pas de capteur de température ambiante fiable sur la plupart
// des téléphones : on lit la température de la batterie (sonde toujours présente),
// qui reflète la chaleur de l'appareil et non celle de la pièce.
object PhoneTemperature {
    fun read(context: Context): Float? {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val tenths = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
        return if (tenths <= 0) null else tenths / 10f
    }
}
