package org.claudevoice.mobile.app

import android.app.Activity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import org.claudevoice.mobile.core.PairingOffer
import org.claudevoice.mobile.core.PhoneAgent

/**
 * The button that shows a pairing code, and the code with the address the hub adds. What it
 * offers and how long a code stays is the core's [PairingOffer]; this only draws it. A code is a
 * secret: while one shows, the window is kept out of screenshots and the recent-apps picture,
 * and it is never logged.
 */
class PairingPanel(private val activity: Activity, private val agent: () -> PhoneAgent?) {
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
        val now = agent()
        show.visibility = if (now?.let { PairingOffer.address(it.status) } == null) View.GONE else View.VISIBLE
        if (showing && (now == null || !PairingOffer.showsCode(now.pairing))) hideCode()
    }

    private fun showCode() {
        val now = agent() ?: return
        val address = PairingOffer.address(now.status) ?: return
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        code.text = activity.getString(R.string.pairing_code, now.pairing.newCode(), address)
        code.visibility = View.VISIBLE
        showing = true
    }

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
