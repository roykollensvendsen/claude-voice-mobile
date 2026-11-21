package org.claudevoice.mobile.app

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ADR-CVM-010: after the manifest merger, the sending plugin holds `SEND_SMS` and never
 * `READ_SMS` or `READ_CONTACTS`; reading contacts is the contacts capability's alone. The merged
 * manifest no longer says which module asked for what, so this reads the merger's own record of
 * where each permission came from, for the build under test.
 */
class MergedPermissionsTest {
    private val report: String by lazy {
        val path = checkNotNull(System.getProperty("mergerReport")) { "the build passes the merger report's path" }
        File(path).readText()
    }

    @Test
    fun the_sending_plugin_holds_send_sms_and_never_the_right_to_read_texts_or_contacts() {
        val from = MergerReport.permissions(report)
        val sending = from.filterValues { ":plugins:sms_send" in it }.keys
        if (BUILT_PLUGINS.any { it.first == "sms_send" }) {
            assertEquals(setOf("android.permission.SEND_SMS"), sending)
        } else {
            assertEquals(emptySet<String>(), sending)
        }
    }

    @Test
    fun only_the_contacts_capability_asks_to_read_contacts() {
        val asking = MergerReport.permissions(report)["android.permission.READ_CONTACTS"].orEmpty()
        assertTrue(asking.toString(), asking.all { it == ":capabilities:contacts" })
    }

    @Test
    fun the_record_names_each_module_a_permission_came_from_and_the_app_for_its_own() {
        val record = """
            -- Merging decision tree log ---
            manifest
            ADDED from /x/app/src/main/AndroidManifest.xml:2:1-40:12
            uses-permission#android.permission.INTERNET
            ADDED from /x/app/src/main/AndroidManifest.xml:4:5-67
            	android:name
            		ADDED from /x/app/src/main/AndroidManifest.xml:4:22-64
            uses-permission#android.permission.SEND_SMS
            ADDED from [:plugins:sms_send] /x/plugins/sms_send/build/AndroidManifest.xml:5:5-67
            MERGED from [:plugins:other] /x/plugins/other/build/AndroidManifest.xml:3:5-67
            	android:name
            		ADDED from [:plugins:sms_send] /x/plugins/sms_send/build/AndroidManifest.xml:5:22-64
            uses-permission#android.permission.READ_CONTACTS
            ADDED from [:capabilities:contacts] /x/capabilities/contacts/build/AndroidManifest.xml:4:5-72
            application
            ADDED from [:plugins:sms_send] /x/plugins/sms_send/build/AndroidManifest.xml:9:5-20
        """.trimIndent()
        assertEquals(
            mapOf(
                "android.permission.INTERNET" to setOf(":app"),
                "android.permission.SEND_SMS" to setOf(":plugins:sms_send", ":plugins:other"),
                "android.permission.READ_CONTACTS" to setOf(":capabilities:contacts"),
            ),
            MergerReport.permissions(record),
        )
    }
}

/** The manifest merger's record of where each line of the merged manifest came from. */
object MergerReport {
    private val PERMISSION = Regex("""^uses-permission(?:-sdk-23)?#(\S+)$""")
    private val SOURCE = Regex("""^(?:ADDED|MERGED) from (?:\[([^\]]+)])?""")

    /** Each permission in the merged manifest, and the modules it came from; the app is `:app`. */
    fun permissions(record: String): Map<String, Set<String>> {
        val from = linkedMapOf<String, MutableSet<String>>()
        var current: MutableSet<String>? = null
        for (line in record.lines()) {
            val permission = PERMISSION.find(line)
            val source = SOURCE.find(line)
            when {
                permission != null -> current = from.getOrPut(permission.groupValues[1]) { linkedSetOf() }
                // An attribute's own lines are indented; the element's sources are not.
                line.startsWith("\t") -> Unit
                source != null -> current?.add(source.groupValues[1].ifEmpty { ":app" })
                else -> current = null
            }
        }
        return from
    }
}
