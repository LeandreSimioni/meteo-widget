package fr.simioni.meteowidget

import android.content.Context

object Prefs {
    private const val FILE = "meteo"
    const val KEY_INDOOR = "indoor_temp"
    const val KEY_OUTDOOR = "outdoor_temp"
    const val KEY_LAST_STATE = "last_state"
    // v2 : la v1 était écrite automatiquement par l'ancienne sélection GPS (peu fiable,
    // ex. station 07260 sans relevé) — nouvelle clé pour repartir sur la station par défaut.
    const val KEY_STATION_CODE = "station_code_v2"
    const val STATE_NONE = "NONE"
    const val STATE_OPEN = "OPEN"
    const val STATE_CLOSE = "CLOSE"

    fun get(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
