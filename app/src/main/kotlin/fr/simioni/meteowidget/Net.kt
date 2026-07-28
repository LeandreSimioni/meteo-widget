package fr.simioni.meteowidget

import kotlinx.coroutines.delay
import java.io.IOException

/**
 * Un échec réseau ponctuel (4G qui accroche, DNS lent) ne devrait pas coûter
 * un cycle entier de 15 min. On retente deux fois avec un délai croissant.
 *
 * Seules les erreurs d'entrée/sortie sont retentées : un 404 ou un XML
 * illisible ne s'arrangera pas en réessayant.
 */
suspend fun <T> withNetworkRetry(
    attempts: Int = 3,
    initialDelayMs: Long = 2_000,
    onRetry: (attempt: Int, error: Exception) -> Unit = { _, _ -> },
    block: () -> T,
): T {
    var delayMs = initialDelayMs
    repeat(attempts - 1) { index ->
        try {
            return block()
        } catch (e: IOException) {
            onRetry(index + 1, e)
            delay(delayMs)
            delayMs *= 2
        }
    }
    return block()
}
