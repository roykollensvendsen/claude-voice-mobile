package org.claudevoice.mobile.app

import java.net.InetAddress
import org.claudevoice.mobile.core.TailnetState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TailnetNoticeTest {
    private val address = InetAddress.getByName("100.101.102.103")

    @Test
    fun tailscale_off_says_so_and_offers_to_open_tailscale() {
        val notice = TailnetNotice.of(TailnetState.TailscaleOff)
        assertEquals(R.string.tailnet_tailscale_off, notice.text)
        assertEquals(NoticeAction.OPEN_TAILSCALE, notice.action)
    }

    @Test
    fun another_vpn_says_it_seems_to_be_on_and_offers_to_open_tailscale() {
        val notice = TailnetNotice.of(TailnetState.OtherVpn)
        assertEquals(R.string.tailnet_other_vpn, notice.text)
        assertEquals(NoticeAction.OPEN_TAILSCALE, notice.action)
    }

    @Test
    fun a_silent_hub_asks_whether_the_laptop_is_awake_with_no_button() {
        val notice = TailnetNotice.of(TailnetState.HubNotAnswering(address))
        assertEquals(R.string.tailnet_hub_silent, notice.text)
        assertNull(notice.action)
    }

    @Test
    fun on_the_tailnet_says_only_that() {
        val notice = TailnetNotice.of(TailnetState.OnTailnet(address))
        assertEquals(R.string.tailnet_ok, notice.text)
        assertNull(notice.action)
    }

    @Test
    fun the_tailscale_button_opens_the_app_or_else_its_download_page() {
        assertEquals(OpenTailscale.App, OpenTailscale.choose(installed = true))
        assertEquals(OpenTailscale.DownloadPage(TAILSCALE_DOWNLOAD), OpenTailscale.choose(installed = false))
    }
}
