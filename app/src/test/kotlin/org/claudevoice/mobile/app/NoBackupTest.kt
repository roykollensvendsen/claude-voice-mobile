package org.claudevoice.mobile.app

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element

/**
 * Nothing the app keeps leaves the phone by backup or by moving to a new phone: not the pairing
 * key's hash, not the pinned hub, not the settings, not the call log. A new phone pairs again,
 * as ADR-CVM-009 assumes. From Android 12 `allowBackup="false"` no longer stops a transfer from
 * phone to phone, so the data extraction rules must exclude everything for both.
 *
 * It reads the merged manifest of the variant under test, the one that ships, not the source.
 */
class NoBackupTest {
    private val android = "http://schemas.android.com/apk/res/android"

    private fun xml(file: File): Document = DocumentBuilderFactory.newInstance()
        .apply { isNamespaceAware = true }
        .newDocumentBuilder()
        .parse(file)

    private val application: Element by lazy {
        val path = checkNotNull(System.getProperty("mergedManifest")) { "the build passes the merged manifest's path" }
        xml(File(path)).getElementsByTagName("application").item(0) as Element
    }

    @Test
    fun backup_is_off_for_android_8_to_11() {
        assertEquals("false", application.getAttributeNS(android, "allowBackup"))
        assertEquals("false", application.getAttributeNS(android, "fullBackupContent"))
    }

    @Test
    fun cloud_backup_and_transfer_to_a_new_phone_exclude_every_place_the_app_keeps_data() {
        val rules = application.getAttributeNS(android, "dataExtractionRules")
        assertEquals("@xml/data_extraction_rules", rules)
        val resources = checkNotNull(System.getProperty("resources")) { "the build passes the res folder" }
        val file = File(resources, "xml/data_extraction_rules.xml")
        val root = xml(file).documentElement
        for (section in listOf("cloud-backup", "device-transfer")) {
            val found = root.getElementsByTagName(section).item(0) as Element?
            assertNotNull(section, found)
            assertEquals("$section includes nothing", 0, found!!.getElementsByTagName("include").length)
            val excluded = (0 until found.getElementsByTagName("exclude").length)
                .map { found.getElementsByTagName("exclude").item(it) as Element }
                .filter { it.getAttribute("path") == "." }
                .map { it.getAttribute("domain") }
                .toSet()
            assertTrue("$section leaves out ${DOMAINS - excluded}", excluded.containsAll(DOMAINS))
        }
    }

    private companion object {
        /** Every domain Android's data extraction rules know, in credential and device storage. */
        val DOMAINS = setOf(
            "root", "file", "database", "sharedpref", "external",
            "device_root", "device_file", "device_database", "device_sharedpref",
        )
    }
}
