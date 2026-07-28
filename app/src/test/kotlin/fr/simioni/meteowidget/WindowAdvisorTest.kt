package fr.simioni.meteowidget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WindowAdvisorTest {

    @Test
    fun `conseille d'ouvrir quand il fait plus frais dehors`() {
        val advice = WindowAdvisor.advise(indoor = 25f, outdoor = 21f, previous = Prefs.STATE_NONE)
        assertEquals(Prefs.STATE_OPEN, advice.state)
        assertTrue(advice.alert)
    }

    @Test
    fun `conseille de fermer quand il fait plus chaud dehors`() {
        val advice = WindowAdvisor.advise(indoor = 25f, outdoor = 31f, previous = Prefs.STATE_NONE)
        assertEquals(Prefs.STATE_CLOSE, advice.state)
        assertTrue(advice.alert)
    }

    @Test
    fun `ne conseille rien quand l'ecart est negligeable`() {
        val advice = WindowAdvisor.advise(indoor = 25f, outdoor = 24.8f, previous = Prefs.STATE_NONE)
        assertEquals(Prefs.STATE_NONE, advice.state)
        assertFalse(advice.alert)
    }

    @Test
    fun `ne sonne pas deux fois pour le meme conseil`() {
        val advice = WindowAdvisor.advise(indoor = 25f, outdoor = 21f, previous = Prefs.STATE_OPEN)
        assertEquals(Prefs.STATE_OPEN, advice.state)
        assertFalse(advice.alert)
    }

    /** Le cas qui faisait sonner l'app en boucle avec un seuil unique. */
    @Test
    fun `un ecart qui oscille autour du seuil ne fait pas clignoter le conseil`() {
        var state = Prefs.STATE_NONE
        var alerts = 0

        // 0.6 franchit le seuil d'entrée, puis on oscille entre 0.4 et 0.6.
        listOf(0.6f, 0.4f, 0.6f, 0.4f, 0.55f).forEach { diff ->
            val advice = WindowAdvisor.advise(indoor = 25f, outdoor = 25f - diff, previous = state)
            state = advice.state
            if (advice.alert) alerts++
        }

        assertEquals(Prefs.STATE_OPEN, state)
        assertEquals("une seule alerte pour une situation qui n'a pas changé", 1, alerts)
    }

    @Test
    fun `l'etat est relache quand l'ecart repasse sous le seuil de sortie`() {
        val advice = WindowAdvisor.advise(indoor = 25f, outdoor = 24.9f, previous = Prefs.STATE_OPEN)
        assertEquals(Prefs.STATE_NONE, advice.state)
        assertFalse(advice.alert)
    }

    @Test
    fun `bascule directement d'ouvrir a fermer si la situation s'inverse`() {
        val advice = WindowAdvisor.advise(indoor = 25f, outdoor = 29f, previous = Prefs.STATE_OPEN)
        assertEquals(Prefs.STATE_CLOSE, advice.state)
        assertTrue(advice.alert)
    }
}
