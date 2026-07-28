package fr.simioni.meteowidget

import android.content.Context

/**
 * Lieux disponibles pour la température extérieure.
 * Chaque lieu sait quelle source interroger — Meteociel (scraping HTML) pour la France,
 * ARPA FVG (XML officiel) pour le Frioul-Vénétie Julienne.
 */
enum class WeatherLocation(
    val id: String,
    val label: String,
    val sourceLabel: String,
) {
    AVIGNON(
        id = "avignon",
        label = "Avignon",
        sourceLabel = "Meteociel · station 7563",
    ),
    CORDOVADO(
        id = "cordovado",
        label = "Cordovado",
        sourceLabel = "ARPA FVG · Mure (Sesto al Reghena), 6 km",
    );

    suspend fun fetchOutdoorTemperature(ctx: Context): Reading? = when (this) {
        AVIGNON   -> MeteocielFetcher.fetchOutdoorTemperature(ctx, METEOCIEL_AVIGNON)
        CORDOVADO -> FvgFetcher.fetchOutdoorTemperature(ctx, FVG_MURE)
    }

    companion object {
        private const val METEOCIEL_AVIGNON = "7563"
        private const val FVG_MURE = "D101"

        val DEFAULT = AVIGNON

        fun fromId(id: String?): WeatherLocation =
            entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}
