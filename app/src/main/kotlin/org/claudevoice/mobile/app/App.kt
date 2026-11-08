package org.claudevoice.mobile.app

import android.app.Application
import org.claudevoice.mobile.core.Plugins
import org.claudevoice.mobile.plugins.status.StatusPlugin

/** The app: the one place that knows which plugins there are, and hands them to the core. */
class App : Application() {
    val plugins by lazy { Plugins(listOf(StatusPlugin())) }
}
