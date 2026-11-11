package org.claudevoice.mobile.pluginapi

/**
 * The settings only the phone changes, as a plugin may read them. They are changed in the app's
 * own screens and nowhere else: this type has no way to write them, and no tool may take one as
 * an input (ADR-CVM-003, and the privacy rules of protocol version 1).
 */
interface PhoneSettings {
    /** Whether, and how, anything on this phone may open the microphone. */
    val microphone: Microphone

    /** Whether location is logged over time. Off unless switched on in the app. */
    val locationLog: Boolean

    /** The numbers the phone calls straight away; any other waits for a spoken yes. */
    val directCalls: List<String>

    /** Whether anything on this phone may use the camera. */
    val camera: Boolean
}

/** The microphone setting, by the names protocol version 1 gives it on the wire. */
enum class Microphone(val wire: String) {
    /** Nothing here opens a microphone. */
    NONE("none"),

    /** The person at the phone accepts first. */
    MUST_ANSWER("must-answer"),

    /** It opens after the warning sound. The sound plays under both. */
    AFTER_WARNING("after-warning"),
}
