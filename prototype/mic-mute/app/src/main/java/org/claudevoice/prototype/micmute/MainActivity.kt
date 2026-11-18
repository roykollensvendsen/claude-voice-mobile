package org.claudevoice.prototype.micmute

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView

/**
 * The tester's screen: pick the measurement, start what it needs, and note what was heard. The
 * steps of each measurement are in ADR-CVM-008 and in this folder's README.
 */
class MainActivity : Activity() {
    private lateinit var lines: TextView
    private val shown = ArrayDeque<String>()
    private val listener: (String) -> Unit = { line -> runOnUiThread { show(line) } }
    private var standInOn = false
    private var sessionOn = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        EventLog.open(this)
        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS), 1)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 96, 32, 32)
        }
        val measure = spinner(listOf("M1a", "M1b", "M1c", "M2", "M3", "M4", "M5", "M6")) { EventLog.measure = it }
        val source = spinner(MuteService.SOURCES.map { it.first }) {}
        val device = spinner(MuteService.DEVICES.map { it.first }) {}
        root.addView(label("Measurement"))
        root.addView(measure)
        root.addView(button("Start the test service") { send(Intent(this, MuteService::class.java)) })
        root.addView(button("Mute / unmute (phone)") { send(action(MuteService.TOGGLE).putExtra(MuteService.SOURCE, "phone")) })
        root.addView(label("Stand-in: the Claude app's source and device"))
        root.addView(source)
        root.addView(device)
        root.addView(button("Stand-in recording on / off") {
            if (!standInOn && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                EventLog.add("no-record-permission")
                return@button
            }
            standInOn = !standInOn
            send(
                action(MuteService.STAND_IN).putExtra(MuteService.ON, standInOn)
                    .putExtra(MuteService.AUDIO_SOURCE, MuteService.SOURCES[source.selectedItemPosition].second)
                    .putExtra(MuteService.DEVICE, MuteService.DEVICES[device.selectedItemPosition].second),
            )
        })
        root.addView(button("Earbud media session on / off") {
            sessionOn = !sessionOn
            send(action(MuteService.SESSION).putExtra(MuteService.ON, sessionOn))
        })
        root.addView(button("M1c: start a word schedule at the next minute") { send(action(MuteService.SCHEDULE)) })
        root.addView(label("M6: a random tone, then say which you heard"))
        root.addView(row(
            button("Tone (call)") { send(action(MuteService.TONE).putExtra(MuteService.CALL_STREAM, true)) },
            button("Tone (media)") { send(action(MuteService.TONE).putExtra(MuteService.CALL_STREAM, false)) },
        ))
        root.addView(row(
            note("Heard mute"), note("Heard unmute"), note("Heard nothing"),
        ))
        root.addView(label("M2 and M3: what the Claude app did"))
        root.addView(row(note("Claude paused"), note("Call ended"), note("Claude gave the word"), note("Claude lost it")))
        root.addView(button("Save the log to Downloads") {
            val name = EventLog.saveToDownloads(this)
            EventLog.add("log-saved", mapOf("name" to (name ?: "failed")))
        })
        lines = TextView(this).apply { textSize = 11f; setTextIsSelectable(true) }
        root.addView(lines)
        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onStart() {
        super.onStart()
        EventLog.listen(listener)
    }

    override fun onStop() {
        EventLog.unlisten(listener)
        super.onStop()
    }

    private fun show(line: String) {
        shown.addFirst(line)
        while (shown.size > 30) shown.removeLast()
        lines.text = shown.joinToString("\n")
    }

    private fun action(name: String) = Intent(this, MuteService::class.java).setAction(name)

    private fun send(intent: Intent) {
        startForegroundService(intent)
    }

    private fun label(text: String) = TextView(this).apply { this.text = text; setPadding(0, 24, 0, 4) }

    private fun button(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun note(text: String) = button(text) { EventLog.add("note", mapOf("text" to text)) }

    private fun row(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        views.forEach { addView(it, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)) }
    }

    private fun spinner(items: List<String>, onPick: (String) -> Unit) = Spinner(this).apply {
        adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, items)
        onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                onPick(items[position])
                EventLog.add("picked", mapOf("item" to items[position]))
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }
}
