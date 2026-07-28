package fr.simioni.meteowidget

import android.content.Context

object Prefs {
    private const val FILE = "meteo"
    const val KEY_INDOOR = "indoor_temp"
    const val KEY_OUTDOOR = "outdoor_temp"
    const val KEY_LAST_STATE = "last_state"
    const val KEY_LOCATION = "location"
    const val STATE_NONE = "NONE"
    const val STATE_OPEN = "OPEN"
    const val STATE_CLOSE = "CLOSE"

    fun get(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun getLocation(context: Context): WeatherLocation =
        WeatherLocation.fromId(get(context).getString(KEY_LOCATION, null))

    /**
     * Change de lieu et repart de zéro côté extérieur : l'ancienne température
     * et l'état ouvrir/fermer ne veulent plus rien dire pour la nouvelle station.
     */
    fun setLocation(context: Context, location: WeatherLocation) {
        get(context).edit()
            .putString(KEY_LOCATION, location.id)
            .remove(KEY_OUTDOOR)
            .putString(KEY_LAST_STATE, STATE_NONE)
            .apply()
    }
}
