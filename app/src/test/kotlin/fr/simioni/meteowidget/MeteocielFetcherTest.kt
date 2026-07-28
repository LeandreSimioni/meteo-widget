package fr.simioni.meteowidget

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class MeteocielFetcherTest {

    private fun fixture(): Document {
        val html = checkNotNull(javaClass.classLoader!!.getResourceAsStream("meteociel_obs.html")) {
            "fixture meteociel_obs.html absente"
        }.bufferedReader().readText()
        return Jsoup.parse(html)
    }

    /**
     * Régression : la version précédente lisait cells[2], une colonne de
     * pictogramme vide, et ne renvoyait donc jamais rien. Ce test tourne sur
     * une vraie page Meteociel enregistrée.
     */
    @Test
    fun `lit le releve le plus recent d'une vraie page`() {
        val obs = MeteocielFetcher.parseLatest(fixture())
        assertNotNull("le scraper doit trouver un relevé", obs)
        assertEquals(19, obs!!.hour)
        assertEquals(30, obs.minute)
        assertEquals(30.0f, obs.temperatureC, 0.001f)
    }

    /** La première ligne du tableau n'a pas encore de mesures : il faut passer à la suivante. */
    @Test
    fun `saute les lignes sans temperature`() {
        val obs = MeteocielFetcher.parseLatest(fixture())!!
        assertTrue("19h36 est vide, on doit tomber sur 19h30", obs.minute == 30)
    }

    @Test
    fun `repere la colonne par l'en-tete et pas par sa position`() {
        // Colonne insérée avant la température : un index en dur casserait ici.
        val html = """
            <table>
              <tr><td>Heure locale</td><td>Néb.</td><td>Temps</td><td>Visi</td>
                  <td>Nouveauté</td><td>Température</td><td>Humi.</td></tr>
              <tr><td>14h30</td><td></td><td></td><td>10 km</td>
                  <td>x</td><td>18.4 °C</td><td>60%</td></tr>
            </table>
        """.trimIndent()
        val obs = MeteocielFetcher.parseLatest(Jsoup.parse(html))!!
        assertEquals(18.4f, obs.temperatureC, 0.001f)
    }

    /** Repli quand l'en-tête est absent ou renommé : on cherche un motif "n °C". */
    @Test
    fun `se rabat sur le motif quand l'en-tete manque`() {
        val html = """
            <table>
              <tr><td>08h00</td><td></td><td></td><td>25 km</td><td>12.5 °C</td><td>80%</td></tr>
            </table>
        """.trimIndent()
        val obs = MeteocielFetcher.parseLatest(Jsoup.parse(html))!!
        assertEquals(12.5f, obs.temperatureC, 0.001f)
    }

    @Test
    fun `ne confond pas une distance ou un pourcentage avec une temperature`() {
        val html = """
            <table>
              <tr><td>08h00</td><td>60 km</td><td>51%</td><td>1013 hPa</td><td>7 °C</td></tr>
            </table>
        """.trimIndent()
        val obs = MeteocielFetcher.parseLatest(Jsoup.parse(html))!!
        assertEquals(7f, obs.temperatureC, 0.001f)
    }

    @Test
    fun `renvoie null sur une page sans tableau d'observations`() {
        assertNull(MeteocielFetcher.parseLatest(Jsoup.parse("<html><body>Erreur 500</body></html>")))
    }

    @Test
    fun `date le releve dans la journee en cours`() {
        val now = Calendar.getInstance().apply {
            set(2026, Calendar.JULY, 28, 19, 45, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val ts = MeteocielFetcher.timestampFor(19, 30, now.timeInMillis)
        assertEquals(15 * 60_000L, now.timeInMillis - ts)
    }

    /** Relevé de 23h50 lu à 00h10 : c'est celui d'hier, pas dans 23 h 40. */
    @Test
    fun `rattache au jour precedent un releve qui tomberait dans le futur`() {
        val now = Calendar.getInstance().apply {
            set(2026, Calendar.JULY, 29, 0, 10, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val ts = MeteocielFetcher.timestampFor(23, 50, now.timeInMillis)
        assertEquals(20 * 60_000L, now.timeInMillis - ts)
    }
}
