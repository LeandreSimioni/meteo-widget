package fr.simioni.meteowidget

import android.content.Context
import android.content.Intent
import android.util.Log
import org.jsoup.Jsoup
import java.util.Calendar

object MeteocielFetcher {
    private const val TAG = "MeteocielFetcher"
    private const val BASE_URL = "https://www.meteociel.fr/temps-reel/obs_villes.php"
    private const val STATION_CODE = "58304005"

    private val timePattern = Regex("""^\d{1,2}h\d{2}$""")

    private fun log(ctx: Context, msg: String) {
        Log.d(TAG, msg)
        ctx.sendBroadcast(Intent(BleScanService.ACTION_LOG).apply {
            setPackage(ctx.packageName)
            putExtra(BleScanService.EXTRA_LOG_MSG, "[Météo] $msg")
        })
    }

    fun fetchOutdoorTemperature(ctx: Context): Float? {
        return try {
            val cal = Calendar.getInstance()
            val url = "$BASE_URL?affint=1&code2=$STATION_CODE" +
                "&jour2=${cal.get(Calendar.DAY_OF_MONTH)}" +
                "&mois2=${cal.get(Calendar.MONTH) + 1}" +
                "&annee2=${cal.get(Calendar.YEAR)}"

            log(ctx, "Requête → $url")

            val doc = Jsoup.connect(url)
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "fr-FR,fr;q=0.9")
                .header("Referer", "https://www.meteociel.fr/")
                .timeout(15_000)
                .get()

            val rows = doc.select("table tr")
            log(ctx, "${rows.size} lignes — début réponse: ${doc.body()?.text()?.take(120) ?: "(vide)"}")

            // Tableau anti-chronologique — première ligne avec heure ET température valide
            val dataRow = rows.firstOrNull { row ->
                val cells = row.select("td")
                if (cells.size < 3) return@firstOrNull false
                val timeOk = timePattern.matches(cells[0].text().trim())
                val tempOk = cells.getOrNull(2)?.text()
                    ?.replace("°C", "")?.replace(",", ".")?.trim()
                    ?.toFloatOrNull()?.let { it in -50f..60f } == true
                timeOk && tempOk
            } ?: run {
                log(ctx, "ERREUR: aucun relevé valide trouvé")
                return null
            }

            val cells = dataRow.select("td")
            val timeStr = cells[0].text()
            val temp = cells[2].text()
                .replace("°C", "").replace(",", ".").trim()
                .toFloat()

            log(ctx, "Relevé $timeStr → $temp°C")
            temp
        } catch (e: Exception) {
            log(ctx, "ERREUR: ${e.message}")
            Log.e(TAG, "Échec récupération température", e)
            null
        }
    }
}
