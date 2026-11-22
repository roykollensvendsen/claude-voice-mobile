package org.claudevoice.mobile.app

import android.app.Activity
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Whether the hub can reach this phone, and what to do if not; once the phone has a name, the
 * listener runs and a pairing code can be shown.
 */
class MainActivity : Activity() {
    private lateinit var watcher: TailnetWatcher
    private lateinit var pairing: PairingPanel
    private lateinit var hidden: TextView
    private val line = NoticeLine(
        ask = { AskNotifications.fromLine(this) },
        openSettings = { AskNotifications.openSettings(this) },
    )
    private val ticks = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            pairing.refresh()
            ticks.postDelayed(this, TICK_MS)
        }
    }

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
            val app = application as App
            addView(NameStep(this@MainActivity, app.machine, app.suggestedName) { listen() }.view)
            addView(message)
            addView(open)
            hidden = TextView(this@MainActivity).apply {
                textSize = TEXT_SIZE_SP
                setText(R.string.notice_hidden)
                visibility = View.GONE
                setOnClickListener { line.tap(canAsk = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) }
            }
            addView(hidden)
            pairing = PairingPanel(this@MainActivity) { app.agent() }
            pairing.views.forEach(::addView)
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
        begin(firstStart = savedInstanceState == null)
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
        tick.run()
    }

    override fun onResume() {
        super.onResume()
        val allowed = getSystemService(NotificationManager::class.java).areNotificationsEnabled()
        val listening = (application as App).machine.name != null
        hidden.visibility = if (NoticeLine.shows(listening, allowed)) View.VISIBLE else View.GONE
    }

    // The request shows over the app: the screen pauses, or loses the top place when two apps share it.
    override fun onPause() {
        line.leftScreen()
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) line.leftScreen()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != AskNotifications.LINE_REQUEST) return
        line.answered(granted = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED)
    }

    override fun onStop() {
        ticks.removeCallbacks(tick)
        watcher.stop()
        super.onStop()
    }

    // Listens once the phone has a name, and asks for the notice's permission at a first start.
    private fun begin(firstStart: Boolean) {
        if ((application as App).machine.name != null) listen()
        if (firstStart) AskNotifications.once(this)
    }

    // Started from here, in the foreground, as Android requires; it finds the agent itself.
    private fun listen() = ListenerService.start(this)

    private companion object {
        const val TEXT_SIZE_SP = 18f

        /** In pixels: room around the message, and more at the top, clear of the status bar. */
        const val MARGIN = 48
        const val TOP_MARGIN = 96

        /** How often the pairing panel looks again: a code that was used or ran out leaves soon. */
        const val TICK_MS = 1000L
    }
}
