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
 * stations décrochent plusieurs heures. La date du relevé est donc portée par
 * la [Reading] et c'est l'appelant qui décide si elle est encore exploitable.
 *
 * Données © ARPA FVG - OSMER e GRN (CC BY-SA 3.0 IT), http://www.meteo.fvg.it/
 */
object FvgFetcher {
    private const val TAG = "FvgFetcher"
    private const val BASE_URL = "https://dev.meteo.fvg.it/xml/stazioni"

    data class Observation(
        val stationName: String,
        val temperatureC: Float,
        val observedAtMs: Long,
    )

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
            val url = "$BASE_URL/$station.xml"
            log(ctx, "Requête → $url")

            val xml = withNetworkRetry(
                onRetry = { attempt, e -> log(ctx, "Réseau KO (essai $attempt): ${e.message} — on retente") }
            ) {
                Jsoup.connect(url)
                    .parser(Parser.xmlParser())
                    .userAgent("Mozilla/5.0 (Linux; Android) MeteoWidget/1.1")
                    .header("Accept", "application/xml,text/xml;q=0.9,*/*;q=0.8")
                    .timeout(15_000)
                    .get()
                    .outerHtml()
            }

            val obs = parseObservation(xml) ?: run {
                log(ctx, "ERREUR: relevé illisible dans la réponse")
                return null
            }

            val reading = Reading(obs.temperatureC, obs.observedAtMs)
            log(ctx, "${obs.stationName} ($station) → ${obs.temperatureC}°C, ${Reading.formatAge(reading.ageMs())}")
            reading
        } catch (e: Exception) {
            log(ctx, "ERREUR: ${e.message}")
            Log.e(TAG, "Échec récupération température FVG", e)
            null
        }
    }

    /** Parsing pur, sans Android ni réseau — testable. */
    fun parseObservation(xml: String): Observation? {
        val doc = Jsoup.parse(xml, "", Parser.xmlParser())
        val obs = doc.selectFirst("data > meteo_data") ?: return null

        val temp = obs.selectFirst("t180")?.text()
            ?.replace(",", ".")?.trim()
            ?.toFloatOrNull()
            ?.takeIf { it in -50f..60f }
            ?: return null

        val observedAt = obs.selectFirst("observation_time")?.text()?.let(::parseUtc) ?: return null
        val name = obs.selectFirst("station_name")?.text()?.takeIf { it.isNotBlank() } ?: "?"

        return Observation(name, temp, observedAt)
    }

    /** "28/07/2026 17.00 UTC" → epoch millis, ou null si illisible. */
    fun parseUtc(observationTime: String): Long? = try {
        val fmt = SimpleDateFormat("dd/MM/yyyy HH.mm", Locale.ROOT).apply {
            timeZone = TimeZone.getTimeZone("UTC")
            isLenient = false
        }
        fmt.parse(observationTime.removeSuffix("UTC").trim())?.time
    } catch (_: Exception) {
        null
    }
}
