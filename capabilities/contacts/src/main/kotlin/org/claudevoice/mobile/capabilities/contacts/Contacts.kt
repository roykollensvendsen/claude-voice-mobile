package org.claudevoice.mobile.capabilities.contacts

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.telephony.PhoneNumberUtils

/**
 * One phone number in the owner's contacts: the contact's name, the number, and its label.
 * [guessed] is the country code put in front of a number saved without one; null when no code was
 * guessed.
 */
data class Entry(val name: String, val number: String, val label: String, val guessed: String? = null)

/**
 * Which numbers in the owner's contacts match a name (ADR-CVM-010). It answers that one question
 * and nothing else: no tool lists or walks the contacts. What counts as a match is the caller's
 * rule, handed in as `matches`, so the rule lives where it is tested and mutated. [permitted]
 * says whether the owner lets the app read contacts; [rows] reads every number with its
 * contact's name.
 */
class Contacts(private val permitted: () -> Boolean, private val rows: () -> List<Entry>) {
    /**
     * Every number whose contact's name [matches] accepts, or null when not permitted. It caps
     * nothing: the caller drops repeats and caps after, so a contact listed twice cannot crowd
     * out another.
     */
    fun numbers(matches: (String) -> Boolean): List<Entry>? =
        if (permitted()) rows().filter { matches(it.name) } else null

    companion object {
        // The columns read, and where each is in a row.
        private val COLUMNS = arrayOf(Phone.DISPLAY_NAME, Phone.NUMBER, Phone.NORMALIZED_NUMBER, Phone.TYPE)
        private const val NAME = 0
        private const val RAW = 1
        private const val NORMALIZED = 2
        private const val TYPE = 3
        private val GAPS = Regex("""[\s-]+""")

        /**
         * The owner's contacts on this phone, read each time a question is asked. [country] is the
         * two-letter country a number saved without a code is read in, or null for none.
         */
        fun on(context: Context, country: () -> String?): Contacts {
            val app = context.applicationContext
            return Contacts(
                { app.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED },
                { read(app.contentResolver, country) },
            )
        }

        /** What a guess names when its code cannot be told from the number. */
        const val SOME_CODE = "a country code"

        /**
         * One row as an entry, or null when it has no name or no number. The number is the one
         * the contacts provider wrote in full international form when it did: that is no guess,
         * since the provider wrote it from the country at the time the contact was saved, and the
         * code trusts it as given. Else a number saved with its "+" is used as written. Else
         * [toE164] reads it in the phone's country, and the code put in front is a guess.
         */
        fun entry(name: String?, normalized: String?, raw: String?, type: Int, toE164: (String) -> String?): Entry? {
            val written = raw?.let { GAPS.replace(it, "") }?.takeIf { it.isNotEmpty() }
            val guess = written?.takeUnless { normalized != null || it.startsWith("+") }?.let(toE164)
            val number = normalized ?: guess ?: written
            return if (name == null || number == null) {
                null
            } else {
                Entry(name, number, label(type), guess?.let { code(it, written.orEmpty()) })
            }
        }

        /**
         * The code a guess put in front of [raw]: what comes before it in [number], or before it
         * without its trunk zero. [SOME_CODE] when neither is the end of [number].
         */
        fun code(number: String, raw: String): String = when {
            number.endsWith(raw) -> number.removeSuffix(raw)
            number.endsWith(raw.removePrefix("0")) -> number.removeSuffix(raw.removePrefix("0"))
            else -> SOME_CODE
        }

        /** The words for a number's type: mobile, home, work, or other for any other type. */
        fun label(type: Int): String = when (type) {
            Phone.TYPE_MOBILE -> "mobile"
            Phone.TYPE_HOME -> "home"
            Phone.TYPE_WORK -> "work"
            else -> "other"
        }

        private fun read(resolver: ContentResolver, country: () -> String?): List<Entry> {
            val toE164 = { raw: String -> country()?.let { PhoneNumberUtils.formatNumberToE164(raw, it) } }
            return resolver.query(Phone.CONTENT_URI, COLUMNS, null, null, null)?.use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        val row = entry(
                            cursor.getString(NAME),
                            cursor.getString(NORMALIZED),
                            cursor.getString(RAW),
                            cursor.getInt(TYPE),
                            toE164,
                        )
                        if (row != null) add(row)
                    }
                }
            }.orEmpty()
        }
    }
}
