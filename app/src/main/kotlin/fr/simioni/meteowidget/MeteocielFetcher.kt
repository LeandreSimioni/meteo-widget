package fr.simioni.meteowidget

import android.util.Log
import org.jsoup.Jsoup
import java.util.Calendar

object MeteocielFetcher {
    private const val TAG = "MeteocielFetcher"
    private const val BASE_URL = "https://www.meteociel.fr/temps-reel/obs_villes.php"
    private const val STATION_CODE = "58304005"

    private val timePattern = Regex("""^\d{1,2}h\d{2}$""")

    fun fetchOutdoorTemperature(): Float? {
        return try {
            val cal = Calendar.getInstance()
            val url = "$BASE_URL?affint=1&code2=$STATION_CODE" +
                "&jour2=${cal.get(Calendar.DAY_OF_MONTH)}" +
                "&mois2=${cal.get(Calendar.MONTH) + 1}" +
                "&annee2=${cal.get(Calendar.YEAR)}"

            val doc = Jsoup.connect(url)
                .userAgent("Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36")
                .timeout(15_000)
                .get()

            val rows = doc.select("table tr")

            // La première ligne dont la 1ère cellule est une heure ("22h06")
            // est le relevé le plus récent (tableau anti-chronologique)
            val dataRow = rows.firstOrNull { row ->
                val cells = row.select("td")
                cells.size >= 3 && timePattern.matches(cells[0].text().trim())
            } ?: run {
                Log.w(TAG, "Aucun relevé trouvé")
                return null
            }

            // Colonnes : Heure(0) | Visi(1) | Température(2) | Humi(3) | ...
            val timeStr = dataRow.select("td")[0].text()
            val temp = dataRow.select("td").getOrNull(2)
                ?.text()
                ?.replace("°C", "")?.replace(",", ".")?.trim()
                ?.toFloatOrNull()
                ?.takeIf { it in -50f..60f }

            Log.d(TAG, "Relevé $timeStr → $temp°C")
            temp
        } catch (e: Exception) {
            Log.e(TAG, "Échec récupération température", e)
            null
        }
    }
}
