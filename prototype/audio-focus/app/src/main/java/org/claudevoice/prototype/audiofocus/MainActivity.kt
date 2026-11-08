package org.claudevoice.prototype.audiofocus

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView

/** One screen: pick the run and the mode, start, note what you heard, share the log. */
class MainActivity : Activity() {
    private lateinit var logView: TextView
    private lateinit var scroll: ScrollView
    private val onLine: (String) -> Unit = { line -> runOnUiThread { append(line) } }

    private val runs = listOf(
        "1: telefonhøyttaler, telefonmikrofon",
        "2: hodetelefon med ledning",
        "3: Bluetooth-hodetelefon",
        "4: Bluetooth ut, telefonmikrofon inn",
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        val run = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, runs)
        }
        val watch = RadioButton(this).apply { text = "Spilleren tar ikke lyden, demper seg selv"; id = View.generateViewId() }
        val self = RadioButton(this).apply { text = "Tar lyden, demper seg selv (kan bryte Claude)"; id = View.generateViewId() }
        val system = RadioButton(this).apply { text = "Tar lyden, Android demper (kan bryte Claude)"; id = View.generateViewId() }
        val prefs = getSharedPreferences("choices", MODE_PRIVATE)
        val saved = Mode.fromSaved(prefs.getString("mode", null))
        val mode = RadioGroup(this).apply {
            addView(watch); addView(self); addView(system)
            check(when (saved) { Mode.SELF_DUCK -> self.id; Mode.SYSTEM_DUCK -> system.id; Mode.WATCH -> watch.id })
        }
        val url = EditText(this).apply { hint = "Valgfri lenke til en podkast (tomt = testtone)"; isSingleLine = true }
        val podcast = Button(this).apply {
            text = "Bruk opplest bok (LibriVox)"
            setOnClickListener { url.setText(SPOKEN_PODCAST) }
        }

        fun button(label: String, onClick: () -> Unit) =
            Button(this).apply { text = label; setOnClickListener { onClick() } }

        fun row(vararg views: View) = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            views.forEach { addView(it, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)) }
        }

        val start = button("Start") {
            val chosen = when (mode.checkedRadioButtonId) {
                self.id -> Mode.SELF_DUCK
                system.id -> Mode.SYSTEM_DUCK
                else -> Mode.WATCH
            }
            prefs.edit().putString("mode", chosen.name).apply()
            startService(
                Intent(this, PlayerService::class.java)
                    .setAction(PlayerService.ACTION_START)
                    .putExtra(PlayerService.EXTRA_RUN, run.selectedItemPosition + 1)
                    .putExtra(PlayerService.EXTRA_MODE, chosen.name)
                    .putExtra(PlayerService.EXTRA_URL, url.text.toString()),
            )
        }
        val stop = button("Stopp") {
            startService(Intent(this, PlayerService::class.java).setAction(PlayerService.ACTION_STOP))
        }
        fun heard(what: String) = button(what) { EventLog.add("heard", mapOf("what" to what)) }

        val share = button("Del loggen") {
            val text = runCatching { EventLog.file(this).takeIf { it.exists() }?.readText() }.getOrNull().orEmpty()
            startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(Intent.EXTRA_SUBJECT, "Audio focus log")
                        .putExtra(Intent.EXTRA_TEXT, text),
                    "Del loggen",
                ),
            )
        }
        val clear = button("Tøm loggen") { EventLog.file(this).delete(); logView.text = "" }

        logView = TextView(this).apply { textSize = 11f; setTextIsSelectable(true) }
        scroll = ScrollView(this).apply { addView(logView) }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 48, 32, 16)
            addView(TextView(this@MainActivity).apply { text = "Oppsett (trykk for å velge):" })
            addView(run)
            addView(mode)
            addView(url)
            addView(podcast)
            addView(row(start, stop))
            addView(TextView(this@MainActivity).apply { text = "Hva hørte du?" })
            addView(row(heard("dempet"), heard("pauset"), heard("full styrke")))
            addView(row(heard("tilbake"), heard("Claude hørte meg ikke"), heard("Claude var utydelig")))
            addView(row(heard("Claudes samtale falt ut"), heard("Claude startet ikke")))
            addView(row(share, clear))
            addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        // From Android 15 the screen is drawn under the status bar; keep the controls clear of it.
        if (Build.VERSION.SDK_INT >= 30) {
            root.setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars())
                view.setPadding(32, bars.top + 16, 32, bars.bottom + 16)
                insets
            }
        }
        setContentView(root)

        // A log the app cannot read (left by an earlier install) must not stop the screen opening.
        runCatching { EventLog.file(this).takeIf { it.exists() }?.readLines()?.takeLast(200) }
            .getOrNull()?.forEach(::append)
        EventLog.listen(onLine)
    }

    companion object {
        /** Public domain, read aloud: LibriVox, The Adventures of Sherlock Holmes, chapter 1. */
        const val SPOKEN_PODCAST = "https://archive.org/download/adventures_holmes/adventureholmes_01_doyle_64kb.mp3"
    }

    private fun append(line: String) {
        logView.append(line + "\n")
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    override fun onDestroy() {
        EventLog.unlisten(onLine)
        super.onDestroy()
    }
}
