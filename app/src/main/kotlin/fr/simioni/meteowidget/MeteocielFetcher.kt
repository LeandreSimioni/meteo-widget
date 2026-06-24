package fr.simioni.meteowidget

import android.util.Log
import org.jsoup.Jsoup
import org.json.JSONObject

object MeteocielFetcher {
    private const val TAG = "OutdoorTempFetcher"

    // Open-Meteo: gratuit, sans clé, mis à jour toutes les 15 min
    // Coordonnées : Varzy, Nièvre
    private const val LAT = 47.36
    private const val LON = 3.39
    private const val URL =
        "https://api.open-meteo.com/v1/forecast?latitude=$LAT&longitude=$LON&current=temperature_2m&timezone=auto"

    fun fetchOutdoorTemperature(): Float? {
        return try {
            val body = Jsoup.connect(URL)
                .ignoreContentType(true)
                .userAgent("Mozilla/5.0 (Linux; Android 10)")
                .timeout(10_000)
                .get()
                .body()
                .text()

            val temp = JSONObject(body)
                .getJSONObject("current")
                .getDouble("temperature_2m")
                .toFloat()

            Log.d(TAG, "Température extérieure: $temp°C")
            temp
        } catch (e: Exception) {
            Log.e(TAG, "Échec récupération température extérieure", e)
            null
        }
    }
}
