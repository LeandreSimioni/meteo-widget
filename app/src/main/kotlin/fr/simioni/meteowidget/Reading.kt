package fr.simioni.meteowidget

/**
 * Une mesure de température, datée de l'instant où elle a été *mesurée*
 * (pas de l'instant où on l'a récupérée).
 *
 * L'écart de date compte : l'Aranet diffuse une trame qui peut déjà avoir
 * plusieurs minutes, et l'ARPA FVG publie un relevé horaire ~30 min après
 * l'heure ronde. Conseiller d'ouvrir les fenêtres sur une valeur d'il y a
 * trois heures n'a aucun sens, d'où la date portée avec la valeur.
 */
data class Reading(val value: Float, val timestampMs: Long) {

    fun ageMs(now: Long = System.currentTimeMillis()): Long = now - timestampMs

    fun isFresh(maxAgeMs: Long, now: Long = System.currentTimeMillis()): Boolean =
        ageMs(now) in 0..maxAgeMs

    companion object {
        /** Une mesure BLE reste exploitable 90 min ; l'Aranet émet toutes les 1-5 min. */
        const val MAX_AGE_INDOOR_MS = 90 * 60 * 1000L

        /** Les relevés FVG sont horaires : 3 h laisse passer un ou deux ratés. */
        const val MAX_AGE_OUTDOOR_MS = 3 * 60 * 60 * 1000L

        /** "il y a 12 min", "il y a 2 h 05", "" si la mesure est toute fraîche. */
        fun formatAge(ageMs: Long): String {
            val minutes = ageMs / 60_000
            return when {
                minutes < 0L   -> ""
                minutes < 2L   -> "à l'instant"
                minutes < 60L  -> "il y a $minutes min"
                else           -> "il y a %d h %02d".format(minutes / 60, minutes % 60)
            }
        }
    }
}
