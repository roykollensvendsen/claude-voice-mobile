package org.claudevoice.mobile.capabilities.contacts

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract.CommonDataKinds.Phone

/** One phone number in the owner's contacts: the contact's name, the number, and its label. */
data class Entry(val name: String, val number: String, val label: String)

/**
 * Which numbers in the owner's contacts match a name (ADR-CVM-010). It answers that one question
 * and nothing else: no tool lists or walks the contacts. What counts as a match is the caller's
 * rule, handed in as `matches`, and so is the most it answers, so the rule lives where it is
 * tested and mutated. [permitted] says whether the owner lets the app read contacts; [rows] reads
 * every number with its contact's name.
 */
class Contacts(private val permitted: () -> Boolean, private val rows: () -> List<Entry>) {
    /** At most [most] numbers whose contact's name [matches] accepts, or null when not permitted. */
    fun numbers(matches: (String) -> Boolean, most: Int): List<Entry>? =
        if (permitted()) rows().filter { matches(it.name) }.take(most) else null

    companion object {
        // The columns read, and where each is in a row.
        private val COLUMNS = arrayOf(Phone.DISPLAY_NAME, Phone.NUMBER, Phone.NORMALIZED_NUMBER, Phone.TYPE)
        private const val NAME = 0
        private const val RAW = 1
        private const val NORMALIZED = 2
        private const val TYPE = 3
        private val GAPS = Regex("""[\s-]+""")

        /** The owner's contacts on this phone, read each time a question is asked. */
        fun on(context: Context): Contacts {
            val app = context.applicationContext
            return Contacts(
                { app.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED },
                { read(app.contentResolver) },
            )
        }

        /** The words for a number's type: mobile, home, work, or other for any other type. */
        fun label(type: Int): String = when (type) {
            Phone.TYPE_MOBILE -> "mobile"
            Phone.TYPE_HOME -> "home"
            Phone.TYPE_WORK -> "work"
            else -> "other"
        }

        /**
         * The number in the phone's own international form when it has one, else as written,
         * without spaces or dashes; null when nothing is left.
         */
        fun number(normalized: String?, raw: String?): String? =
            normalized ?: raw?.let { GAPS.replace(it, "") }?.takeIf { it.isNotEmpty() }

        private fun read(resolver: ContentResolver): List<Entry> =
            resolver.query(Phone.CONTENT_URI, COLUMNS, null, null, null)?.use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        val name = cursor.getString(NAME)
                        val number = number(cursor.getString(NORMALIZED), cursor.getString(RAW))
                        if (name != null && number != null) add(Entry(name, number, label(cursor.getInt(TYPE))))
                    }
                }
            }.orEmpty()
    }
}
