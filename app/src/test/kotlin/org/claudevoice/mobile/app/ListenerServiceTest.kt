package org.claudevoice.mobile.app

import android.net.ConnectivityManager
import org.claudevoice.mobile.core.ListenStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class ListenerServiceTest {
    private val app = RuntimeEnvironment.getApplication() as App
    private val callbacks get() = shadowOf(app.getSystemService(ConnectivityManager::class.java)).networkCallbacks

    @Test
    fun with_no_confirmed_name_there_is_no_agent_and_it_stops_itself() {
        val service = Robolectric.buildService(ListenerService::class.java).create().get()
        assertTrue(shadowOf(service).isStoppedBySelf)
        assertEquals(0, callbacks.size)
    }

    @Test
    fun once_named_it_runs_in_the_foreground_and_follows_the_network() {
        app.machine.confirm("phone-a")
        val service = Robolectric.buildService(ListenerService::class.java).create().get()
        val notice = shadowOf(service).lastForegroundNotification
        assertEquals(app.getString(R.string.listener_running), shadowOf(notice).contentTitle)
        assertEquals(false, shadowOf(service).isStoppedBySelf)
        assertEquals(1, callbacks.size)
    }

    @Test
    fun when_it_ends_it_stops_watching_and_tells_the_agent_there_is_no_network() {
        app.machine.confirm("phone-a")
        val controller = Robolectric.buildService(ListenerService::class.java).create()
        val agent = app.agent()!!
        assertNotEquals(NO_NETWORK, agent.status)
        controller.destroy()
        assertEquals(0, callbacks.size)
        assertEquals(NO_NETWORK, agent.status)
    }

    private companion object {
        // The core's words for a phone with no network, as the owner reads them.
        val NO_NETWORK = ListenStatus.Stopped("not listening: no network; connect to a network")
    }
}
