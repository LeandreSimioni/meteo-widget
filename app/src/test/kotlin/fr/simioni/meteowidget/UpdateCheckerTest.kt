package fr.simioni.meteowidget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class UpdateCheckerTest {

    /** Réponse de l'API GitHub, réduite aux champs que le parseur regarde. */
    private fun releaseJson(
        body: String = "versionCode=7\\r\\nversionName=1.6\\r\\n",
        assetName: String = "app-debug.apk",
    ) = """
        {"url":"https://api.github.com/repos/x/y/releases/1","tag_name":"latest",
         "name":"Latest build","body":"$body","draft":false,"prerelease":false,
         "assets":[{"name":"$assetName","size":6239260,
           "browser_download_url":"https://github.com/x/y/releases/download/latest/$assetName"}]}
    """.trimIndent()

    @Test
    fun `lit la version et l'url de l'apk`() {
        val release = UpdateChecker.parseRelease(releaseJson())
        assertNotNull(release)
        assertEquals(7, release!!.versionCode)
        assertEquals("1.6", release.versionName)
        assertEquals(
            "https://github.com/x/y/releases/download/latest/app-debug.apk",
            release.apkUrl,
        )
    }

    /** Une release publiée avant l'ajout du numéro de version dans le corps. */
    @Test
    fun `renvoie null si le corps ne porte pas de version`() {
        assertNull(UpdateChecker.parseRelease(releaseJson(body = "Build du 4 juillet")))
    }

    @Test
    fun `renvoie null si l'apk n'est pas dans les assets`() {
        assertNull(UpdateChecker.parseRelease(releaseJson(assetName = "mapping.txt")))
    }

    @Test
    fun `renvoie null sur une reponse d'erreur`() {
        assertNull(UpdateChecker.parseRelease("""{"message":"Not Found"}"""))
        assertNull(UpdateChecker.parseRelease(""))
    }

    @Test
    fun `accepte un versionName entre guillemets`() {
        val release = UpdateChecker.parseRelease(
            releaseJson(body = "versionCode=12\\nversionName=\\\"2.0.1\\\"")
        )
        assertEquals(12, release!!.versionCode)
        assertEquals("2.0.1", release.versionName)
    }
}
