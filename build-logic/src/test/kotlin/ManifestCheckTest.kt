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
            listOf("the app asks for android.permission.SEND_SMS, which no module in the build declares; declare it in the src/main/AndroidManifest.xml of the module that needs it"),
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
            listOf("the app runs a service of type camera, which no module in the build declares; declare it in the src/main/AndroidManifest.xml of the module that needs it"),
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

    @Test
    fun `a line that removes a permission declares nothing`() {
        val removing =
            ManifestEntries.of(
                """
                <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                    xmlns:tools="http://schemas.android.com/tools">
                    <uses-permission android:name="android.permission.WAKE_LOCK" tools:node="remove" />
                    <uses-permission android:name="android.permission.RECEIVE_SMS" />
                </manifest>
                """.trimIndent(),
            )
        assertEquals(setOf("android.permission.RECEIVE_SMS"), removing.permissions)
    }

    @Test
    fun `the application id in a declared permission is the build's own, as in the merged manifest`() {
        val declared =
            ManifestEntries.of(
                manifest("""<uses-permission android:name="${'$'}{applicationId}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION" />"""),
                applicationId = "org.claudevoice.mobile",
            )
        assertEquals(setOf("org.claudevoice.mobile.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"), declared.permissions)
    }
}
