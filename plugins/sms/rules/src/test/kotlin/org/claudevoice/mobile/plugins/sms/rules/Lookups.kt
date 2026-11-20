package org.claudevoice.mobile.plugins.sms.rules

/** A phone that cannot put a national number in E.164 form, so it keeps the digits. */
val noCountry: (String) -> String? = { null }

/** A seal that keeps the bytes as they are, for tests where the seal is not the point. */
val plainSeal = object : Sealer {
    override fun seal(plain: ByteArray) = plain

    override fun open(sealed: ByteArray) = sealed
}

/** One install part, and cursors counted up. */
class FixedChance(private val install: String) : Chance {
    private var cursors = 0

    override fun installPart() = install

    override fun cursor(): String {
        cursors += 1
        return "K" + cursors.toString().padStart(11, '0')
    }
}

/** A folder sync that does nothing, for tests where a power cut is not the point. */
val noFolderSync = FolderSync { }
