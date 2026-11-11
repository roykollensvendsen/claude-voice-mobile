package org.claudevoice.mobile.core

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.claudevoice.mobile.pluginapi.Microphone
import org.claudevoice.mobile.pluginapi.PhoneSettings

/**
 * The one writer of the settings only the phone changes. Only the app's own screens are given
 * it. Plugins get [settings], a view that reads them and cannot be turned back into this: a
 * plugin cannot reach the core at all (`checkLayers`), and the view is a separate object.
 * Everything starts at its most private.
 */
class SettingsKeeper {
    @Volatile private var microphone = Microphone.NONE

    @Volatile private var locationLog = false

    @Volatile private var directCalls: List<String> = emptyList()

    @Volatile private var camera = false

    val settings: PhoneSettings = View()

    fun setMicrophone(value: Microphone) {
        microphone = value
    }

    fun setLocationLog(value: Boolean) {
        locationLog = value
    }

    fun setDirectCalls(numbers: List<String>) {
        directCalls = java.util.Collections.unmodifiableList(numbers.toList())
    }

    fun setCamera(value: Boolean) {
        camera = value
    }

    private inner class View : PhoneSettings {
        override val microphone get() = this@SettingsKeeper.microphone
        override val locationLog get() = this@SettingsKeeper.locationLog
        override val directCalls get() = this@SettingsKeeper.directCalls
        override val camera get() = this@SettingsKeeper.camera
    }
}

/** The `privacy` part of `agent_info`, as protocol version 1's `privacy.json` gives it. */
fun privacyReport(settings: PhoneSettings): JsonObject =
    buildJsonObject {
        put("microphone", settings.microphone.wire)
        put("location_log", settings.locationLog)
    }
