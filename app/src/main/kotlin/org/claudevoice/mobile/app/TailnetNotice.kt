package org.claudevoice.mobile.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import org.claudevoice.mobile.core.TailnetState

/** What the person can do from a notice: so far only open Tailscale. */
enum class NoticeAction { OPEN_TAILSCALE }

/** The words for a tailnet state (a string resource), and the button with them, if any. */
data class TailnetNotice(val text: Int, val action: NoticeAction?) {
    companion object {
        fun of(state: TailnetState): TailnetNotice = when (state) {
            TailnetState.NoNetwork -> TailnetNotice(R.string.tailnet_no_network, null)
            TailnetState.TailscaleOff -> TailnetNotice(R.string.tailnet_tailscale_off, NoticeAction.OPEN_TAILSCALE)
            TailnetState.OtherVpn -> TailnetNotice(R.string.tailnet_other_vpn, NoticeAction.OPEN_TAILSCALE)
            is TailnetState.HubNotAnswering -> TailnetNotice(R.string.tailnet_hub_silent, null)
            is TailnetState.OnTailnet -> TailnetNotice(R.string.tailnet_ok, null)
        }
    }
}

const val TAILSCALE_PACKAGE = "com.tailscale.ipn"
const val TAILSCALE_DOWNLOAD = "https://tailscale.com/download/android"

/** Where the Tailscale button leads: the app if it is installed, else its download page. */
sealed interface OpenTailscale {
    data object App : OpenTailscale

    data class DownloadPage(val url: String) : OpenTailscale

    companion object {
        fun choose(installed: Boolean): OpenTailscale = if (installed) App else DownloadPage(TAILSCALE_DOWNLOAD)

        /** The intent for the button. Seeing Tailscale needs the `<queries>` entry in the manifest. */
        fun intent(context: Context): Intent {
            val launch = context.packageManager.getLaunchIntentForPackage(TAILSCALE_PACKAGE)
            return when (val target = choose(installed = launch != null)) {
                App -> launch!!
                is DownloadPage -> Intent(Intent.ACTION_VIEW, Uri.parse(target.url))
            }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
}
