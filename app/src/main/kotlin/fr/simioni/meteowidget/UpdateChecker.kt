package fr.simioni.meteowidget

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Mise à jour depuis la release GitHub "latest", celle que republie la CI à
 * chaque push sur `main`.
 *
 * La version publiée est lue dans le corps de la release, où le workflow écrit
 * `versionCode=` et `versionName=` : le tag est glissant ("latest"), il ne dit
 * rien du contenu.
 *
 * L'installation par-dessus n'est possible que parce que les APK sont signés
 * avec la keystore fixe du dépôt (voir app/build.gradle.kts).
 */
object UpdateChecker {
    private const val ASSET_NAME = "app-debug.apk"
    private const val CACHE_DIR = "updates"

    data class Release(val versionCode: Int, val versionName: String, val apkUrl: String)

    // Le corps n'est pas déséchappé : un guillemet y apparaît comme \" — d'où le
    // préfixe toléré avant la valeur.
    private val versionCodeRegex = Regex("""versionCode\s*=\s*[\\"]*(\d+)""")
    private val versionNameRegex = Regex("""versionName\s*=\s*[\\"]*([0-9][0-9A-Za-z.\-]*)""")
    private val apkUrlRegex = Regex(""""browser_download_url"\s*:\s*"([^"]+/$ASSET_NAME)"""")

    /**
     * Extrait la version et l'URL de l'APK de la réponse de l'API GitHub.
     * Pur : pas de réseau, pas d'Android — testable.
     */
    fun parseRelease(json: String): Release? {
        val url = apkUrlRegex.find(json)?.groupValues?.get(1) ?: return null
        // Le corps de la release arrive échappé dans le JSON ("\r\n" littéral).
        val body = json.substringAfter("\"body\":\"", "").substringBefore("\",")
        val code = versionCodeRegex.find(body)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        val name = versionNameRegex.find(body)?.groupValues?.get(1) ?: code.toString()
        return Release(code, name, url)
    }

    /** Version installée, pour savoir s'il y a mieux en ligne. */
    fun installedVersionCode(ctx: Context): Int = try {
        @Suppress("DEPRECATION")
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionCode
    } catch (_: Exception) {
        0
    }

    suspend fun fetchLatest(repo: String): Release? = withNetworkRetry {
        val conn = (URL("https://api.github.com/repos/$repo/releases/tags/latest")
            .openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "MeteoWidget")
            connectTimeout = 15_000
            readTimeout = 15_000
        }
        try {
            if (conn.responseCode != 200) return@withNetworkRetry null
            parseRelease(conn.inputStream.bufferedReader().readText())
        } finally {
            conn.disconnect()
        }
    }

    /** Télécharge l'APK dans le cache. [onProgress] reçoit un pourcentage, ou -1 si la taille est inconnue. */
    suspend fun download(ctx: Context, url: String, onProgress: (Int) -> Unit): File? = withNetworkRetry {
        val dir = File(ctx.cacheDir, CACHE_DIR).apply { mkdirs() }
        val target = File(dir, ASSET_NAME)
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "MeteoWidget")
            connectTimeout = 15_000
            readTimeout = 60_000
        }
        try {
            if (conn.responseCode != 200) return@withNetworkRetry null
            val total = conn.contentLength
            var read = 0L
            var lastPercent = -1
            conn.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        read += n
                        if (total > 0) {
                            val percent = (read * 100 / total).toInt()
                            // Ne pas inonder l'UI : une notification par tranche de 10 %.
                            if (percent / 10 != lastPercent / 10) {
                                lastPercent = percent
                                onProgress(percent)
                            }
                        }
                    }
                }
            }
            if (total <= 0) onProgress(-1)
            target
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Passe la main à l'installeur système. Android demandera confirmation, et
     * l'autorisation "installer des applications inconnues" si elle manque.
     */
    fun install(ctx: Context, apk: File) {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", apk)
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    }

    fun canInstall(ctx: Context): Boolean = ctx.packageManager.canRequestPackageInstalls()
}
