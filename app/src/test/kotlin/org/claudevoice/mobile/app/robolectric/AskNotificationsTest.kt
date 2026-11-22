package org.claudevoice.mobile.app.robolectric

import org.claudevoice.mobile.app.App
import org.claudevoice.mobile.app.AskNotifications
import android.Manifest
import android.app.Activity
import android.provider.Settings
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class AskNotificationsTest {
    private val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
    private val notices = arrayOf(Manifest.permission.POST_NOTIFICATIONS)

    private fun asked() = (activity.application as App).asks.asked(Manifest.permission.POST_NOTIFICATIONS)

    @Test
    @Config(sdk = [33])
    fun at_first_start_it_asks_once_and_writes_the_ask_down_first() {
        AskNotifications.once(activity)
        val request = shadowOf(activity).lastRequestedPermission
        assertArrayEquals(notices, request.requestedPermissions)
        assertEquals(1, request.requestCode)
        assertTrue(asked())
    }

    @Test
    @Config(sdk = [33])
    fun it_never_asks_on_its_own_a_second_time() {
        AskNotifications.once(activity)
        // A later start: a new screen, the same install.
        val later = Robolectric.buildActivity(Activity::class.java).setup().get()
        AskNotifications.once(later)
        assertNull(shadowOf(later).lastRequestedPermission)
    }

    @Test
    @Config(sdk = [33])
    fun with_notifications_allowed_it_does_not_ask() {
        shadowOf(activity.application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        AskNotifications.once(activity)
        assertNull(shadowOf(activity).lastRequestedPermission)
    }

    @Test
    @Config(sdk = [32])
    fun before_android_13_there_is_nothing_to_ask() {
        AskNotifications.once(activity)
        assertNull(shadowOf(activity).lastRequestedPermission)
    }

    @Test
    @Config(sdk = [33])
    fun the_line_asks_with_its_own_request_code_and_writes_the_ask_down_first() {
        AskNotifications.fromLine(activity)
        val request = shadowOf(activity).lastRequestedPermission
        assertArrayEquals(notices, request.requestedPermissions)
        assertEquals(AskNotifications.LINE_REQUEST, request.requestCode)
        assertTrue(asked())
    }

    @Test
    fun the_settings_open_on_the_apps_own_notification_page() {
        AskNotifications.openSettings(activity)
        val started = shadowOf(activity).nextStartedActivity
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, started.action)
        assertEquals(activity.packageName, started.getStringExtra(Settings.EXTRA_APP_PACKAGE))
    }
}
