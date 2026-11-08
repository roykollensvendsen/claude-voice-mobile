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
        val self = RadioButton(this).apply { text = "Spilleren demper seg selv"; id = View.generateViewId() }
        val system = RadioButton(this).apply { text = "Android demper"; id = View.generateViewId() }
        val mode = RadioGroup(this).apply { addView(self); addView(system); check(self.id) }
        val url = EditText(this).apply { hint = "Valgfri lenke til en podkast (tomt = testtone)"; isSingleLine = true }

        fun button(label: String, onClick: () -> Unit) =
            Button(this).apply { text = label; setOnClickListener { onClick() } }

        fun row(vararg views: View) = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            views.forEach { addView(it, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)) }
        }

        val start = button("Start") {
            startService(
                Intent(this, PlayerService::class.java)
                    .setAction(PlayerService.ACTION_START)
                    .putExtra(PlayerService.EXTRA_RUN, run.selectedItemPosition + 1)
                    .putExtra(PlayerService.EXTRA_MODE, if (mode.checkedRadioButtonId == self.id) Mode.SELF_DUCK.name else Mode.SYSTEM_DUCK.name)
                    .putExtra(PlayerService.EXTRA_URL, url.text.toString()),
            )
        }
        val stop = button("Stopp") {
            startService(Intent(this, PlayerService::class.java).setAction(PlayerService.ACTION_STOP))
        }
        fun heard(what: String) = button(what) { EventLog.add("heard", mapOf("what" to what)) }

        val share = button("Del loggen") {
            val text = EventLog.file(this).takeIf { it.exists() }?.readText().orEmpty()
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
            addView(run)
            addView(mode)
            addView(url)
            addView(row(start, stop))
            addView(TextView(this@MainActivity).apply { text = "Hva hørte du?" })
            addView(row(heard("dempet"), heard("pauset"), heard("full styrke")))
            addView(row(heard("tilbake"), heard("Claude hørte meg ikke"), heard("Claude var utydelig")))
            addView(row(share, clear))
            addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        setContentView(root)

        EventLog.file(this).takeIf { it.exists() }?.readLines()?.takeLast(200)?.forEach(::append)
        EventLog.listen(onLine)
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
