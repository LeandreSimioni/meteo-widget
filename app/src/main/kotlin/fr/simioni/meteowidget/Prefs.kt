package fr.simioni.meteowidget

import android.content.Context

object Prefs {
    private const val FILE = "meteo"
    const val KEY_INDOOR = "indoor_temp"
    const val KEY_OUTDOOR = "outdoor_temp"
    private const val KEY_INDOOR_TS = "indoor_temp_ts"
    private const val KEY_OUTDOOR_TS = "outdoor_temp_ts"
    const val KEY_LAST_STATE = "last_state"
    const val KEY_LOCATION = "location"
    const val STATE_NONE = "NONE"
    const val STATE_OPEN = "OPEN"
    const val STATE_CLOSE = "CLOSE"

    fun get(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun putIndoor(context: Context, reading: Reading) = put(context, KEY_INDOOR, KEY_INDOOR_TS, reading)

    fun putOutdoor(context: Context, reading: Reading) = put(context, KEY_OUTDOOR, KEY_OUTDOOR_TS, reading)

    fun getIndoor(context: Context): Reading? = read(context, KEY_INDOOR, KEY_INDOOR_TS)

    fun getOutdoor(context: Context): Reading? = read(context, KEY_OUTDOOR, KEY_OUTDOOR_TS)

    private fun put(context: Context, valueKey: String, tsKey: String, reading: Reading) {
        get(context).edit()
            .putFloat(valueKey, reading.value)
            .putLong(tsKey, reading.timestampMs)
            .apply()
    }

    /**
     * Une valeur écrite par une version antérieure n'a pas de date : on la
     * renvoie datée de l'époque zéro, donc périmée, plutôt que de la faire
     * passer pour fraîche.
     */
    private fun read(context: Context, valueKey: String, tsKey: String): Reading? {
        val prefs = get(context)
        val value = prefs.getFloat(valueKey, Float.NaN)
        if (value.isNaN()) return null
        return Reading(value, prefs.getLong(tsKey, 0L))
    }

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
            .remove(KEY_OUTDOOR_TS)
            .putString(KEY_LAST_STATE, STATE_NONE)
            .apply()
    }
}
