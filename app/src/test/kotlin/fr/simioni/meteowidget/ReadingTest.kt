package fr.simioni.meteowidget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadingTest {

    private val now = 1_700_000_000_000L

    @Test
    fun `une mesure recente est fraiche`() {
        val reading = Reading(21f, now - 10 * 60_000)
        assertTrue(reading.isFresh(Reading.MAX_AGE_INDOOR_MS, now))
    }

    @Test
    fun `une mesure trop vieille ne l'est pas`() {
        val reading = Reading(21f, now - 4 * 60 * 60_000)
        assertFalse(reading.isFresh(Reading.MAX_AGE_OUTDOOR_MS, now))
    }

    /** Une valeur écrite par une version sans horodatage arrive avec un timestamp nul. */
    @Test
    fun `une mesure sans date est consideree comme perimee`() {
        assertFalse(Reading(21f, 0L).isFresh(Reading.MAX_AGE_OUTDOOR_MS, now))
    }

    /** Horloge reculée (changement d'heure, resync NTP) : ni fraîche, ni crash. */
    @Test
    fun `une mesure datee du futur n'est pas consideree comme fraiche`() {
        assertFalse(Reading(21f, now + 60 * 60_000).isFresh(Reading.MAX_AGE_INDOOR_MS, now))
    }

    @Test
    fun `formatAge est lisible`() {
        assertEquals("à l'instant", Reading.formatAge(30_000))
        assertEquals("il y a 12 min", Reading.formatAge(12 * 60_000))
        assertEquals("il y a 2 h 05", Reading.formatAge((2 * 60 + 5) * 60_000L))
    }
}
