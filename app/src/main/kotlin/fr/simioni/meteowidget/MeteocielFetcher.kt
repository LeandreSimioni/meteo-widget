package fr.simioni.meteowidget

import android.util.Log
import org.jsoup.Jsoup

object MeteocielFetcher {
    private const val TAG = "MeteocielFetcher"
    private const val URL = "https://www.meteociel.fr/temps-reel/obs_villes.php?code2=58304005"

    fun fetchOutdoorTemperature(): Float? {
        return try {
            val doc = Jsoup.connect(URL)
                .userAgent("Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36")
                .timeout(15_000)
                .get()

            val rows = doc.select("table tr")
            Log.d(TAG, "Found ${rows.size} table rows")

            // Log the first few rows for debugging on first run
            rows.take(4).forEachIndexed { i, row ->
                Log.d(TAG, "Row $i: ${row.text().take(200)}")
            }

            // First data row where a cell contains a degree symbol
            val dataRow = rows.firstOrNull { row ->
                val cells = row.select("td")
                cells.size > 2 && cells.any { it.text().contains("°") }
            } ?: run {
                Log.w(TAG, "No data row found with degree symbol")
                return null
            }

            // Try each cell: parse the first float that looks like a temperature (-50..60)
            val cells = dataRow.select("td")
            Log.d(TAG, "Data row cells: ${cells.map { it.text() }}")

            cells.mapNotNull { cell ->
                val raw = cell.text()
                    .replace("°C", "").replace("°", "")
                    .replace(",", ".").trim()
                raw.toFloatOrNull()?.takeIf { it in -50f..60f }
            }.firstOrNull().also { temp ->
                Log.d(TAG, "Outdoor temperature: $temp°C")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch outdoor temperature", e)
            null
        }
    }
}
