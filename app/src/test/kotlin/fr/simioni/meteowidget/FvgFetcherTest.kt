package fr.simioni.meteowidget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class FvgFetcherTest {

    private fun xml(body: String) = """
        <?xml version="1.0" encoding="UTF-8"?>
        <data id="meteo.fvg_osservazioni_xml">
             <last_update>28/07/2026 17:18:14 UTC</last_update>
             $body
        </data>
    """.trimIndent()

    private val realResponse = xml(
        """
        <meteo_data>
             <observation_time>28/07/2026 17.00 UTC</observation_time>
             <station_id>D101</station_id>
             <station_name>Mure</station_name>
             <station_altitude quota="m">8</station_altitude>
             <rr um="mm" description="precipitazione">0.0</rr>
             <t180 um="°C" description="temperatura a 2m">30.1</t180>
        </meteo_data>
        <meteo_data_daily>
             <station_name>Mure</station_name>
             <t180_min um="°C" description="temperatura a 2m">20.5</t180_min>
             <t180_max um="°C" description="temperatura a 2m">31.8</t180_max>
        </meteo_data_daily>
        """
    )

    @Test
    fun `lit la temperature et la station du releve courant`() {
        val obs = FvgFetcher.parseObservation(realResponse)
        assertNotNull(obs)
        assertEquals("Mure", obs!!.stationName)
        assertEquals(30.1f, obs.temperatureC, 0.001f)
    }

    /** Le bloc journalier porte aussi un t180_* : il ne doit pas être confondu avec le relevé. */
    @Test
    fun `ne confond pas le releve courant avec le resume du jour`() {
        val obs = FvgFetcher.parseObservation(realResponse)!!
        assertEquals(30.1f, obs.temperatureC, 0.001f)
    }

    @Test
    fun `date le releve en UTC`() {
        val obs = FvgFetcher.parseObservation(realResponse)!!
        // 28/07/2026 17:00 UTC
        assertEquals(1785258000000L, obs.observedAtMs)
    }

    @Test
    fun `parseUtc rejette une date illisible`() {
        assertNull(FvgFetcher.parseUtc("pas une date"))
        assertNull(FvgFetcher.parseUtc(""))
        assertNull(FvgFetcher.parseUtc("32/13/2026 99.99 UTC"))
    }

    /** Certaines stations publient un relevé sans température (capteur en panne). */
    @Test
    fun `renvoie null quand la temperature manque`() {
        val sansTemp = xml(
            """
            <meteo_data>
                 <observation_time>28/07/2026 01.00 UTC</observation_time>
                 <station_name>San Vito al Tagliamento</station_name>
            </meteo_data>
            """
        )
        assertNull(FvgFetcher.parseObservation(sansTemp))
    }

    @Test
    fun `renvoie null sur une temperature aberrante`() {
        val absurde = xml(
            """
            <meteo_data>
                 <observation_time>28/07/2026 17.00 UTC</observation_time>
                 <station_name>Mure</station_name>
                 <t180 um="°C">999.9</t180>
            </meteo_data>
            """
        )
        assertNull(FvgFetcher.parseObservation(absurde))
    }

    @Test
    fun `renvoie null sur une reponse qui n'est pas du XML FVG`() {
        assertNull(FvgFetcher.parseObservation("<html><body>404 Not Found</body></html>"))
        assertNull(FvgFetcher.parseObservation(""))
    }

    @Test
    fun `accepte une temperature negative avec virgule decimale`() {
        val hiver = xml(
            """
            <meteo_data>
                 <observation_time>15/01/2026 06.00 UTC</observation_time>
                 <station_name>Mure</station_name>
                 <t180 um="°C">-3,5</t180>
            </meteo_data>
            """
        )
        assertEquals(-3.5f, FvgFetcher.parseObservation(hiver)!!.temperatureC, 0.001f)
    }
}
