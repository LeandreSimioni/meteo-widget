package fr.simioni.meteowidget

import android.content.Context
import android.content.Intent
import android.util.Log
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Observations temps réel de l'ARPA FVG (OSMER).
 *
 * Un XML par station : https://dev.meteo.fvg.it/xml/stazioni/{SIGLA}.xml
 * Attention : la sigle est obligatoire ("D101"), l'identifiant numérique ("101")
 * pointe vers une tout autre station.
 *
 * Les relevés sont horaires et publiés ~30 min après l'heure ronde ; certaines
 * stations décrochent plusieurs heures. On rejette donc les données trop vieilles
 * plutôt que de piloter les fenêtres sur un relevé périmé.
 *
 * Données © ARPA FVG - OSMER e GRN (CC BY-SA 3.0 IT), http://www.meteo.fvg.it/
 */
object FvgFetcher {
    private const val TAG = "FvgFetcher"
    private const val BASE_URL = "https://dev.meteo.fvg.it/xml/stazioni"

    /** Au-delà, le relevé horaire est considéré comme périmé. */
    private const val MAX_AGE_MS = 3 * 60 * 60 * 1000L

    private fun log(ctx: Context, msg: String) {
        Log.d(TAG, msg)
        LogStore.append(ctx, "[Météo] $msg")
        ctx.sendBroadcast(Intent(BleScanService.ACTION_LOG).apply {
            setPackage(ctx.packageName)
            putExtra(BleScanService.EXTRA_LOG_MSG, "[Météo] $msg")
        })
    }

    fun fetchOutdoorTemperature(ctx: Context, station: String): Float? {
        return try {
            val url = "$BASE_URL/$station.xml"
            log(ctx, "Requête → $url")

            val doc = Jsoup.connect(url)
                .parser(Parser.xmlParser())
                .userAgent("Mozilla/5.0 (Linux; Android) MeteoWidget/1.0")
                .header("Accept", "application/xml,text/xml;q=0.9,*/*;q=0.8")
                .timeout(15_000)
                .get()

            val obs = doc.selectFirst("data > meteo_data") ?: run {
                log(ctx, "ERREUR: bloc meteo_data absent")
                return null
            }

            val temp = obs.selectFirst("t180")?.text()
                ?.replace(",", ".")?.trim()
                ?.toFloatOrNull()
                ?.takeIf { it in -50f..60f }
                ?: run {
                    log(ctx, "ERREUR: température t180 absente ou hors plage")
                    return null
                }

            val name = obs.selectFirst("station_name")?.text() ?: station
            val obsTime = obs.selectFirst("observation_time")?.text()
            val ageMs = obsTime?.let { ageOf(it) }

            if (ageMs != null && ageMs > MAX_AGE_MS) {
                log(ctx, "Relevé périmé ($obsTime, ${ageMs / 60_000} min) — ignoré")
                return null
            }

            log(ctx, "$name ($station) $obsTime → $temp°C")
            temp
        } catch (e: Exception) {
            log(ctx, "ERREUR: ${e.message}")
            Log.e(TAG, "Échec récupération température FVG", e)
            null
        }
    }

    /** "28/07/2026 17.00 UTC" → âge en millisecondes, ou null si illisible. */
    private fun ageOf(observationTime: String): Long? = try {
        val fmt = SimpleDateFormat("dd/MM/yyyy HH.mm", Locale.ROOT).apply {
            timeZone = TimeZone.getTimeZone("UTC")
            isLenient = false
        }
        val parsed = fmt.parse(observationTime.removeSuffix("UTC").trim())
        parsed?.let { System.currentTimeMillis() - it.time }
    } catch (_: Exception) {
        null
    }
}
