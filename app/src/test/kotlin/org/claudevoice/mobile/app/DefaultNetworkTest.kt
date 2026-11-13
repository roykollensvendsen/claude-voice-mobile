package org.claudevoice.mobile.app

import java.net.InetAddress
import org.claudevoice.mobile.core.SeenNetwork
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DefaultNetworkTest {
    private val wifiAddress = listOf(InetAddress.getByName("192.0.2.10"))
    private val tailnetAddress = listOf(InetAddress.getByName("100.101.102.103"))

    @Test
    fun nothing_is_known_until_a_network_has_both_its_kind_and_its_addresses() {
        val default = DefaultNetwork<String>()
        default.available("wifi")
        assertNull(default.capabilities("wifi", isVpn = false))
        assertEquals(listOf(SeenNetwork(false, wifiAddress)), default.addresses("wifi", wifiAddress))
    }

    @Test
    fun switching_from_wifi_to_the_vpn_never_mixes_the_two() {
        val default = DefaultNetwork<String>()
        default.available("wifi")
        default.capabilities("wifi", isVpn = false)
        default.addresses("wifi", wifiAddress)
        default.available("vpn")
        // The VPN's kind arrives before its addresses: no half-known VPN with Wi-Fi's addresses.
        assertNull(default.capabilities("vpn", isVpn = true))
        assertEquals(listOf(SeenNetwork(true, tailnetAddress)), default.addresses("vpn", tailnetAddress))
    }

    @Test
    fun news_about_a_network_that_is_no_longer_the_default_is_ignored() {
        val default = DefaultNetwork<String>()
        default.available("vpn")
        default.capabilities("vpn", isVpn = true)
        default.addresses("vpn", tailnetAddress)
        assertNull(default.addresses("wifi", wifiAddress))
    }

    @Test
    fun losing_the_default_network_leaves_no_network() {
        val default = DefaultNetwork<String>()
        default.available("wifi")
        default.capabilities("wifi", isVpn = false)
        default.addresses("wifi", wifiAddress)
        assertEquals(emptyList<SeenNetwork>(), default.lost("wifi"))
    }

    @Test
    fun losing_a_network_that_was_already_replaced_changes_nothing() {
        val default = DefaultNetwork<String>()
        default.available("wifi")
        default.available("vpn")
        assertNull(default.lost("wifi"))
    }
}
