package org.claudevoice.mobile.app

import android.content.Context
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import org.claudevoice.mobile.core.Confirmed
import org.claudevoice.mobile.core.MachineName

/**
 * The first step, before anything can pair: the owner confirms the name the phone gives itself.
 * It is filled in with [suggested], made from the phone's model; two phones of one model would
 * get the same, which is why it is asked. [done] runs once a name is kept.
 */
class NameStep(context: Context, private val machine: MachineName, suggested: String, private val done: () -> Unit) {
    private val why = TextView(context).apply {
        textSize = TEXT_SIZE_SP
        setText(R.string.name_first)
    }
    private val field = EditText(context).apply {
        setText(suggested)
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
    }
    private val answer = TextView(context)
    private val confirm = Button(context).apply {
        setText(R.string.name_confirm)
        setOnClickListener { answered(machine.confirm(field.text.toString().trim())) }
    }

    /** The step's own part of the screen, gone once a name is kept. */
    val view = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(why)
        addView(field)
        addView(confirm)
        addView(answer)
        visibility = if (NameAnswer.shown(machine.name)) View.VISIBLE else View.GONE
    }

    private fun answered(result: Confirmed) {
        answer.setText(NameAnswer.of(result))
        if (NameAnswer.ends(result)) {
            view.visibility = View.GONE
            done()
        }
    }

    private companion object {
        const val TEXT_SIZE_SP = 18f
    }
}

/** The step's rules: when it shows, which words answer, and which answer ends it. */
object NameAnswer {
    /** Shown only while the phone has no name. */
    fun shown(name: String?): Boolean = name == null

    /** Only a kept name ends the step. */
    fun ends(result: Confirmed): Boolean = result == Confirmed.Kept

    fun of(result: Confirmed): Int = when (result) {
        Confirmed.Kept -> R.string.name_kept
        Confirmed.NotAllowed -> R.string.name_not_allowed
        Confirmed.AlreadyNamed -> R.string.name_already_named
        Confirmed.NotWritten -> R.string.name_not_written
    }
}
