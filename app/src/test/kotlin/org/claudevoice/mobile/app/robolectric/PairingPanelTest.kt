package org.claudevoice.mobile.app.robolectric

import org.claudevoice.mobile.app.PairingPanel
import android.app.Activity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import java.net.InetAddress
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import org.claudevoice.mobile.core.ListenStatus
import org.claudevoice.mobile.core.Pairing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PairingPanelTest {
    private val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
    private var now = Instant.parse("2026-11-22T10:00:00Z")
    private var pairing: Pairing? = Pairing(clock = { now }, random = SecureRandom())
    private var status: ListenStatus? = ListenStatus.Listening(InetAddress.getByName("100.64.0.10"), PORT)
    private val panel = PairingPanel(activity, { status }, { pairing })
    private val show = panel.views[0] as Button
    private val code = panel.views[1] as TextView

    private fun secure() = activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0

    @Test
    fun the_button_shows_only_while_the_phone_listens() {
        panel.refresh()
        assertEquals(View.VISIBLE, show.visibility)
        status = ListenStatus.Stopped("Tailscale is off")
        panel.refresh()
        assertEquals(View.GONE, show.visibility)
        status = null
        panel.refresh()
        assertEquals(View.GONE, show.visibility)
    }

    @Test
    fun a_code_shows_with_the_address_and_keeps_the_window_out_of_screenshots() {
        panel.refresh()
        show.performClick()
        assertEquals(View.VISIBLE, code.visibility)
        assertTrue(code.text.contains("100.64.0.10"))
        assertTrue(secure())
    }

    @Test
    fun a_code_that_ran_out_clears_the_text_and_the_flag() {
        panel.refresh()
        show.performClick()
        now += Duration.ofMinutes(11)
        panel.refresh()
        assertEquals(View.GONE, code.visibility)
        assertEquals("", code.text.toString())
        assertFalse(secure())
    }

    @Test
    fun a_code_still_working_stays() {
        panel.refresh()
        show.performClick()
        now += Duration.ofMinutes(9)
        panel.refresh()
        assertEquals(View.VISIBLE, code.visibility)
        assertTrue(secure())
    }

    @Test
    fun when_the_agent_goes_the_code_goes_too() {
        panel.refresh()
        show.performClick()
        pairing = null
        panel.refresh()
        assertEquals(View.GONE, code.visibility)
        assertFalse(secure())
    }

    @Test
    fun with_no_agent_or_no_address_a_tap_shows_nothing() {
        status = ListenStatus.Stopped("Tailscale is off")
        show.performClick()
        assertEquals(View.GONE, code.visibility)
        status = ListenStatus.Listening(InetAddress.getByName("100.64.0.10"), PORT)
        pairing = null
        show.performClick()
        assertEquals(View.GONE, code.visibility)
        assertFalse(secure())
    }

    private companion object {
        const val PORT = 8812
    }
}
