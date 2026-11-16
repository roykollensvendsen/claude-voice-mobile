package org.claudevoice.mobile.app

import android.app.Activity
import android.content.ActivityNotFoundException
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** For now one thing: whether the hub can reach this phone, and what to do if not. */
class MainActivity : Activity() {
    private lateinit var watcher: TailnetWatcher

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val message = TextView(this).apply {
            textSize = TEXT_SIZE_SP
            setText(R.string.tailnet_checking)
        }
        val open = Button(this).apply {
            setText(R.string.open_tailscale)
            visibility = View.GONE
            setOnClickListener {
                try {
                    startActivity(OpenTailscale.intent(this@MainActivity))
                } catch (_: ActivityNotFoundException) {
                    // No browser for the download page: say where to get Tailscale instead.
                    message.text = getString(R.string.no_browser, TAILSCALE_DOWNLOAD)
                    message.setTextIsSelectable(true)
                }
            }
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(MARGIN, TOP_MARGIN, MARGIN, MARGIN)
            addView(message)
            addView(open)
        }
        // From Android 15 the screen is drawn under the bars; keep the message clear of them.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            root.setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                view.setPadding(MARGIN, bars.top + MARGIN, MARGIN, bars.bottom + MARGIN)
                insets
            }
        }
        setContentView(root)
        watcher = TailnetWatcher(this) { state ->
            runOnUiThread {
                val notice = TailnetNotice.of(state)
                message.setText(notice.text)
                open.visibility = if (notice.action == NoticeAction.OPEN_TAILSCALE) View.VISIBLE else View.GONE
            }
        }
    }

    override fun onStart() {
        super.onStart()
        watcher.start()
    }

    override fun onStop() {
        watcher.stop()
        super.onStop()
    }

    private companion object {
        const val TEXT_SIZE_SP = 18f

        /** In pixels: room around the message, and more at the top, clear of the status bar. */
        const val MARGIN = 48
        const val TOP_MARGIN = 96
    }
}
