package org.claudevoice.mobile.app

import android.app.Activity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import org.claudevoice.mobile.core.ListenStatus
import org.claudevoice.mobile.core.Pairing
import org.claudevoice.mobile.core.PairingOffer

/**
 * The button that shows a pairing code, and the code with the address the hub adds. What it
 * offers and how long a code stays is the core's [PairingOffer]; this only draws it. A code is a
 * secret: while one shows, the window is kept out of screenshots and the recent-apps picture,
 * and it is never logged. [status] and [pairing] are the agent's; both null while there is none.
 */
class PairingPanel(
    private val activity: Activity,
    private val status: () -> ListenStatus?,
    private val pairing: () -> Pairing?,
) {
    private val code = TextView(activity).apply {
        textSize = TEXT_SIZE_SP
        visibility = View.GONE
    }
    private val show = Button(activity).apply {
        setText(R.string.pairing_show)
        visibility = View.GONE
        setOnClickListener { showCode() }
    }
    private var showing = false

    val views: List<View> = listOf(show, code)

    /** Brings the panel up to date with the agent: the button while it listens, the code while it works. */
    fun refresh() {
        show.visibility = if (address() == null) View.GONE else View.VISIBLE
        val now = pairing()
        if (showing && (now == null || !PairingOffer.showsCode(now))) hideCode()
    }

    private fun showCode() {
        val now = pairing() ?: return
        val address = address() ?: return
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        code.text = activity.getString(R.string.pairing_code, now.newCode(), address)
        code.visibility = View.VISIBLE
        showing = true
    }

    private fun address() = status()?.let(PairingOffer::address)

    private fun hideCode() {
        code.text = ""
        code.visibility = View.GONE
        activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        showing = false
    }

    private companion object {
        const val TEXT_SIZE_SP = 18f
    }
}
