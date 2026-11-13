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
            textSize = 18f
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
            setPadding(48, 96, 48, 48)
            addView(message)
            addView(open)
        }
        // From Android 15 the screen is drawn under the bars; keep the message clear of them.
        if (Build.VERSION.SDK_INT >= 30) {
            root.setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                view.setPadding(48, bars.top + 48, 48, bars.bottom + 48)
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
}
