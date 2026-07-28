package fr.simioni.meteowidget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AranetDecoderTest {

    /** Trame de 21 octets telle que diffusée par un Aranet4 (Smart Home désactivé). */
    private fun frame(
        co2: Int = 812,
        tempRaw: Int = 428,      // 428 / 20 = 21.4 °C
        pressureRaw: Int = 10132, // 10132 / 10 = 1013.2 hPa
        humidity: Int = 47,
        battery: Int = 88,
        interval: Int = 300,
        age: Int = 120,
    ): ByteArray {
        val data = ByteArray(21)
        fun put(offset: Int, value: Int) {
            data[offset] = (value and 0xFF).toByte()
            data[offset + 1] = ((value shr 8) and 0xFF).toByte()
        }
        put(8, co2)
        put(10, tempRaw)
        put(12, pressureRaw)
        data[14] = humidity.toByte()
        data[15] = battery.toByte()
        put(17, interval)
        put(19, age)
        return data
    }

    @Test
    fun `decode une trame complete`() {
        val reading = AranetDecoder.decode(frame())!!
        assertEquals(812, reading.co2Ppm)
        assertEquals(21.4f, reading.temperatureC, 0.001f)
        assertEquals(1013.2f, reading.pressureHpa, 0.01f)
        assertEquals(47, reading.humidity)
        assertEquals(88, reading.battery)
        assertEquals(120, reading.ageSec)
    }

    /** Avec "Smart Home" activé, l'Aranet émet une trame courte et inexploitable. */
    @Test
    fun `refuse une trame trop courte`() {
        assertNull(AranetDecoder.decode(ByteArray(20)))
        assertNull(AranetDecoder.decode(ByteArray(0)))
    }

    /** Humidité et batterie sont des octets non signés : 200 % ne doit pas devenir -56. */
    @Test
    fun `lit les octets non signes`() {
        val reading = AranetDecoder.decode(frame(humidity = 200, battery = 255))!!
        assertEquals(200, reading.humidity)
        assertEquals(255, reading.battery)
    }

    @Test
    fun `decode une temperature basse`() {
        // 0 °C et 2.5 °C : le format est un entier non signé, pas de négatif possible.
        assertEquals(0f, AranetDecoder.decode(frame(tempRaw = 0))!!.temperatureC, 0.001f)
        assertEquals(2.5f, AranetDecoder.decode(frame(tempRaw = 50))!!.temperatureC, 0.001f)
    }
}
