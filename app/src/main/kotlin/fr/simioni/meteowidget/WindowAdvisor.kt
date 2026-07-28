package fr.simioni.meteowidget

/**
 * Décide s'il faut ouvrir ou fermer les fenêtres, et s'il faut sonner.
 *
 * Logique pure et sans dépendance Android — c'est le cœur de l'app, donc
 * la seule partie qu'on peut réellement tester.
 *
 * Deux seuils plutôt qu'un : on n'entre dans un état qu'à partir de
 * [ENTER_THRESHOLD], mais on n'en sort qu'en dessous de [EXIT_THRESHOLD].
 * Avec un seuil unique, un écart qui oscille autour de 0,5 °C (0,4 → 0,6 →
 * 0,4 → 0,6) faisait sonner « Ouvrir » à chaque cycle. L'hystérésis colle
 * l'état en place tant que la situation n'a pas vraiment changé.
 */
object WindowAdvisor {

    /** Écart à partir duquel le conseil devient actionnable. */
    const val ENTER_THRESHOLD = 0.5f

    /** Écart en dessous duquel on relâche le conseil en cours. */
    const val EXIT_THRESHOLD = 0.2f

    data class Advice(
        /** Un des Prefs.STATE_*. */
        val state: String,
        /** true → transition vers un conseil actionnable, la notif doit sonner. */
        val alert: Boolean,
    )

    /**
     * @param indoor température intérieure
     * @param outdoor température extérieure
     * @param previous état précédent (Prefs.STATE_*)
     */
    fun advise(indoor: Float, outdoor: Float, previous: String): Advice {
        val diff = indoor - outdoor
        val state = when {
            // On tient l'état courant tant qu'on reste au-dessus du seuil de sortie.
            previous == Prefs.STATE_OPEN && diff > EXIT_THRESHOLD -> Prefs.STATE_OPEN
            previous == Prefs.STATE_CLOSE && diff < -EXIT_THRESHOLD -> Prefs.STATE_CLOSE
            diff > ENTER_THRESHOLD -> Prefs.STATE_OPEN
            diff < -ENTER_THRESHOLD -> Prefs.STATE_CLOSE
            else -> Prefs.STATE_NONE
        }
        return Advice(state, alert = state != Prefs.STATE_NONE && state != previous)
    }
}
