package fr.simioni.meteowidget

data class AranetReading(
    val co2Ppm: Int,
    val temperatureC: Float,
    val pressureHpa: Float,
    val humidity: Int,
    val battery: Int,
    val intervalSec: Int,
    val ageSec: Int
)

object AranetDecoder {
    // ByteArray from Android (manufacturer ID already stripped):
    // [0]   flags
    // [1]   version patch
    // [2]   version minor
    // [3-4] version major
    // [5]   hw rev
    // [6-7] unknown
    // [8-9] CO2 ppm (uint16 LE)
    // [10-11] temperature / 20.0 = °C (uint16 LE)
    // [12-13] pressure / 10.0 = hPa (uint16 LE)
    // [14] humidity %
    // [15] battery %
    // [16] status
    // [17-18] interval seconds (uint16 LE)
    // [19-20] age seconds (uint16 LE)
    private const val MIN_SIZE = 21

    fun decode(data: ByteArray): AranetReading? {
        if (data.size < MIN_SIZE) return null
        return AranetReading(
            co2Ppm = uint16LE(data, 8),
            temperatureC = uint16LE(data, 10) / 20.0f,
            pressureHpa = uint16LE(data, 12) / 10.0f,
            humidity = data[14].toInt() and 0xFF,
            battery = data[15].toInt() and 0xFF,
            intervalSec = uint16LE(data, 17),
            ageSec = uint16LE(data, 19)
        )
    }

    private fun uint16LE(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)

    fun toHex(data: ByteArray): String = data.joinToString(" ") { "%02X".format(it) }
}
