package org.claudevoice.mobile.plugins.sms.rules

/**
 * Character sets the rules match on, written out. On the JVM a flag gives `\s` all of Unicode,
 * but Android's regex engine (ICU) refuses that flag and throws when the class loads,
 * which crashed the receiver on its first run on a phone. Both engines read these the same way.
 */
object CharacterSets {
    /** Every character Unicode calls white space: the ASCII ones, next line, and every separator. */
    const val WHITE_SPACE = "[\\t\\n\\u000B\\f\\r\\u0085\\p{Z}]"

    /** Every control and format character that is not white space, such as a zero-width space. */
    const val HIDDEN = "[\\u0000-\\u0008\\u000E-\\u001F\\u007F-\\u0084\\u0086-\\u009F\\p{Cf}]"
}
