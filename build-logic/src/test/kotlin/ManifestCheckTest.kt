import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * ADR-CVM-007: the app's merged manifest asks only for what the app, or a module the build took,
 * declares in its own manifest. A library's entry is refused until one of ours declares it.
 */
class ManifestCheckTest {
    private fun manifest(body: String) =
        """
        <manifest xmlns:android="http://schemas.android.com/apk/res/android">
        $body
        </manifest>
        """.trimIndent()

    private val app =
        ManifestEntries.of(manifest("""<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />"""))

    @Test
    fun `a manifest's permissions and service types are read, in both permission forms`() {
        val read =
            ManifestEntries.of(
                manifest(
                    """
                    <uses-permission android:name="android.permission.RECEIVE_SMS" />
                    <uses-permission-sdk-23 android:name="android.permission.READ_SMS" />
                    <application>
                        <service android:name=".Talk" android:foregroundServiceType="microphone|location" />
                        <service android:name=".Quiet" />
                    </application>
                    """,
                ),
            )
        assertEquals(setOf("android.permission.RECEIVE_SMS", "android.permission.READ_SMS"), read.permissions)
        assertEquals(setOf("microphone", "location"), read.serviceTypes)
    }

    @Test
    fun `a merged manifest asking only for what is declared passes`() {
        assertEquals(listOf<String>(), ManifestCheck.undeclared(app, listOf(app)))
    }

    @Test
    fun `a permission no module declares fails the check`() {
        val merged =
            ManifestEntries.of(
                manifest(
                    """
                    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
                    <uses-permission android:name="android.permission.SEND_SMS" />
                    """,
                ),
            )
        assertEquals(
            listOf("the app asks for android.permission.SEND_SMS, which neither the app nor a module it took declares"),
            ManifestCheck.undeclared(merged, listOf(app)),
        )
    }

    @Test
    fun `a service type no module declares fails the check`() {
        val merged =
            ManifestEntries.of(
                manifest("""<application><service android:name=".S" android:foregroundServiceType="camera" /></application>"""),
            )
        assertEquals(
            listOf("the app runs a service of type camera, which neither the app nor a module it took declares"),
            ManifestCheck.undeclared(merged, listOf(app)),
        )
    }

    @Test
    fun `an entry a module declares passes, whichever module declares it`() {
        val sms = ManifestEntries.of(manifest("""<uses-permission android:name="android.permission.RECEIVE_SMS" />"""))
        val merged =
            ManifestEntries.of(
                manifest(
                    """
                    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
                    <uses-permission android:name="android.permission.RECEIVE_SMS" />
                    """,
                ),
            )
        assertEquals(listOf<String>(), ManifestCheck.undeclared(merged, listOf(app, sms)))
    }
}
