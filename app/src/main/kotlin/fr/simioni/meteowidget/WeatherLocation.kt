package fr.simioni.meteowidget

import android.content.Context

/**
 * Lieux disponibles pour la température extérieure.
 *
 * Chaque lieu sait quelle source interroger — Meteociel (scraping HTML) pour la
 * France, ARPA FVG (XML officiel) pour le Frioul-Vénétie Julienne.
 *
 * Côté France, le code de station reste modifiable dans l'app : le lieu
 * [FRANCE] lit celui enregistré dans les préférences. Côté FVG, la station est
 * figée sur la plus proche de Cordovado, il n'y a rien à régler.
 */
enum class WeatherLocation(
    val id: String,
    /** Libellé court — widget, notification. */
    val label: String,
) {
    FRANCE(id = "avignon", label = "France"),
    CORDOVADO(id = "cordovado", label = "Cordovado");

    /** Détail de la source, affiché dans le sélecteur. */
    fun sourceLabel(ctx: Context): String = when (this) {
        FRANCE -> "Meteociel · station ${Prefs.getStationCode(ctx)}"
        CORDOVADO -> "ARPA FVG · Mure (Sesto al Reghena), 6 km"
    }

    /** Le code de station n'est modifiable que pour Meteociel. */
    val usesStationCode: Boolean get() = this == FRANCE

    suspend fun fetchOutdoorTemperature(ctx: Context): Reading? = when (this) {
        FRANCE -> MeteocielFetcher.fetchOutdoorTemperature(ctx, Prefs.getStationCode(ctx))
        CORDOVADO -> FvgFetcher.fetchOutdoorTemperature(ctx, FVG_MURE)
    }

    companion object {
        /** Sigle ARPA FVG de la station la plus proche de Cordovado (6,2 km). */
        private const val FVG_MURE = "D101"

        val DEFAULT = FRANCE

        fun fromId(id: String?): WeatherLocation =
            entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}
