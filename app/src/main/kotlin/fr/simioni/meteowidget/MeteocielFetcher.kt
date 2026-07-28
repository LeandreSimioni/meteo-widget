package fr.simioni.meteowidget

import android.content.Context
import android.content.Intent
import android.util.Log
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.util.Calendar

/**
 * Observations temps réel de Meteociel (scraping du tableau horaire).
 *
 * La colonne de température est repérée par l'en-tête du tableau, pas par un
 * index en dur : la version précédente lisait la 3ᵉ colonne, qui est en fait
 * une colonne de pictogramme vide, et ne renvoyait donc jamais rien. Un
 * repli par motif ("30 °C") couvre le cas où l'en-tête changerait de libellé.
 */
object MeteocielFetcher {
    private const val TAG = "MeteocielFetcher"
    private const val BASE_URL = "https://www.meteociel.fr/temps-reel/obs_villes.php"

    private val timePattern = Regex("""^(\d{1,2})h(\d{2})$""")

    /** "30 °C", "30,1 °C", "-3 °C" — tolérant sur le caractère degré, qui souffre de l'encodage. */
    private val temperaturePattern = Regex("""^(-?\d{1,2}(?:[.,]\d+)?)\s*\S{0,2}C$""")

    private fun log(ctx: Context, msg: String) {
        Log.d(TAG, msg)
        LogStore.append(ctx, "[Météo] $msg")
        ctx.sendBroadcast(Intent(BleScanService.ACTION_LOG).apply {
            setPackage(ctx.packageName)
            putExtra(BleScanService.EXTRA_LOG_MSG, "[Météo] $msg")
        })
    }

    suspend fun fetchOutdoorTemperature(ctx: Context, station: String): Reading? {
        return try {
            val cal = Calendar.getInstance()
            val url = "$BASE_URL?affint=1&code2=$station" +
                "&jour2=${cal.get(Calendar.DAY_OF_MONTH)}" +
                "&mois2=${cal.get(Calendar.MONTH)}" +
                "&annee2=${cal.get(Calendar.YEAR)}"

            log(ctx, "Requête → $url")

            val doc = withNetworkRetry(
                onRetry = { attempt, e -> log(ctx, "Réseau KO (essai $attempt): ${e.message} — on retente") }
            ) {
                Jsoup.connect(url)
                    .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "fr-FR,fr;q=0.9")
                    .header("Referer", "https://www.meteociel.fr/")
                    .timeout(15_000)
                    .maxBodySize(4 * 1024 * 1024)
                    .get()
            }

            val obs = parseLatest(doc) ?: run {
                log(ctx, "ERREUR: aucun relevé valide trouvé")
                return null
            }

            val reading = Reading(obs.temperatureC, timestampFor(obs.hour, obs.minute))
            log(ctx, "Relevé ${obs.hour}h%02d → ${obs.temperatureC}°C, ${Reading.formatAge(reading.ageMs())}".format(obs.minute))
            reading
        } catch (e: Exception) {
            log(ctx, "ERREUR: ${e.message}")
            Log.e(TAG, "Échec récupération température", e)
            null
        }
    }

    data class Observation(val hour: Int, val minute: Int, val temperatureC: Float)

    /**
     * Parsing pur, sans Android ni réseau — testable.
     * Le tableau est anti-chronologique : la première ligne exploitable est la plus récente.
     */
    fun parseLatest(doc: Document): Observation? {
        val rows = doc.select("table tr")
        val column = temperatureColumnIndex(rows)

        for (row in rows) {
            val cells = row.select("td")
            if (cells.size < 3) continue
            val time = timePattern.find(cells[0].text().trim()) ?: continue

            val temp = column?.let { index -> cells.getOrNull(index)?.let(::readTemperature) }
                ?: cells.drop(1).firstNotNullOfOrNull(::readTemperature)
                ?: continue

            return Observation(
                hour = time.groupValues[1].toInt(),
                minute = time.groupValues[2].toInt(),
                temperatureC = temp,
            )
        }
        return null
    }

    /**
     * Index de la colonne "Température" d'après l'en-tête, ou null si introuvable.
     * On écarte "Temps" (la météo du moment) en exigeant un libellé long : le
     * caractère accentué est parfois mal décodé, donc on ne compare que le début.
     */
    private fun temperatureColumnIndex(rows: org.jsoup.select.Elements): Int? {
        for (row in rows) {
            val cells = row.select("td")
            if (cells.size < 5) continue
            val index = cells.indexOfFirst { cell ->
                val label = cell.text().trim().lowercase()
                label.startsWith("temp") && label.length >= 8
            }
            if (index >= 0) return index
        }
        return null
    }

    private fun readTemperature(cell: org.jsoup.nodes.Element): Float? {
        val match = temperaturePattern.find(cell.text().trim()) ?: return null
        return match.groupValues[1].replace(',', '.').toFloatOrNull()?.takeIf { it in -50f..60f }
    }

    /**
     * Meteociel donne une heure locale sans date. On la rattache au jour courant ;
     * si ça tombe dans le futur (relevé d'hier lu juste après minuit), on recule d'un jour.
     */
    fun timestampFor(hour: Int, minute: Int, now: Long = System.currentTimeMillis()): Long {
        val cal = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (cal.timeInMillis > now + 30 * 60 * 1000L) {
            cal.add(Calendar.DAY_OF_MONTH, -1)
        }
        return cal.timeInMillis
    }
}
