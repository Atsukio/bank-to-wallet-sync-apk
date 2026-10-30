package app.banksync

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.R as M

/**
 * What tapping or holding a Recent activity row opens: the row's summary, the record's details and what
 * can be done with it.
 * It stays open under any dialog an action opens, and [fill] redraws it as the record changes.
 */
class ActionSheet(activity: Activity, onClose: () -> Unit) {
    /** One line. A [danger] one is red. [close]: the sheet closes on tap; otherwise it stays under the dialog the action opens. */
    class Action(
        val icon: Int, val text: String, val hint: String? = null,
        val danger: Boolean = false, val close: Boolean = true, val run: () -> Unit,
    )

    /** The top of the sheet, as the row shows it: the bank's [icon], else a [symbol], else the payee's [monogram]. */
    class Header(
        val icon: Drawable?, val symbol: Int?, val monogram: String,
        val title: CharSequence, val sub: CharSequence, val amount: CharSequence?, val amountColor: Int,
    )

    private val inflater = LayoutInflater.from(activity)
    private val view = inflater.inflate(R.layout.sheet_actions, null)
    private val dialog = BottomSheetDialog(activity).apply {
        setContentView(view)
        // All of it at once: the destructive lines are at the bottom, and they should be seen.
        behavior.state = BottomSheetBehavior.STATE_EXPANDED
        behavior.skipCollapsed = true
        setOnDismissListener { onClose() }
    }

    /** Called on every tap in the sheet, before the action runs; it counts as the user still being here. */
    var onTap: () -> Unit = {}

    /**
     * Redraws the sheet. [note] is a line under the header, such as sending being paused while the sheet
     * is open, with [noteIcon]; null shows none. [facts] are the record's details.
     */
    fun fill(
        header: Header, note: String?, facts: List<Dialogs.Fact>, actions: List<Action>,
        noteIcon: Int = R.drawable.ic_pause_circle,
    ) {
        val icon = view.findViewById<ImageView>(R.id.sheetIcon)
        val monogram = view.findViewById<TextView>(R.id.sheetMonogram)
        icon.visibility = if (header.icon != null || header.symbol != null) View.VISIBLE else View.INVISIBLE
        monogram.visibility = if (icon.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        monogram.text = header.monogram
        if (header.icon != null) {
            // The bank's own icon as it is, without the tinted circle.
            icon.setImageDrawable(header.icon)
            icon.background = null
            icon.imageTintList = null
            icon.setPadding(0, 0, 0, 0)
        } else if (header.symbol != null) {
            icon.setImageResource(header.symbol)
        }
        view.findViewById<TextView>(R.id.sheetTitle).text = header.title
        view.findViewById<TextView>(R.id.sheetSub).text = header.sub
        view.findViewById<TextView>(R.id.sheetAmount).apply {
            text = header.amount
            setTextColor(header.amountColor)
        }
        view.findViewById<View>(R.id.sheetNote).visibility = if (note == null) View.GONE else View.VISIBLE
        view.findViewById<ImageView>(R.id.sheetNoteIcon).setImageResource(noteIcon)
        view.findViewById<TextView>(R.id.sheetNoteText).text = note

        val factList = view.findViewById<LinearLayout>(R.id.sheetFacts)
        factList.removeAllViews()
        for (f in facts) {
            val item = inflater.inflate(R.layout.item_fact, factList, false) as LinearLayout
            item.findViewById<TextView>(R.id.factLabel).text = f.label
            item.findViewById<TextView>(R.id.factValue).apply {
                text = f.value
                if (f.color != null) setTextColor(context.getColor(f.color))
                // A long value reads better under its label than squeezed beside it.
                if (f.value.length > 60) {
                    item.orientation = LinearLayout.VERTICAL
                    gravity = android.view.Gravity.START
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                        .apply { topMargin = (2 * resources.displayMetrics.density).toInt() }
                }
            }
            factList.addView(item)
        }
        (factList.parent as View).visibility = if (facts.isEmpty()) View.GONE else View.VISIBLE

        val list = view.findViewById<LinearLayout>(R.id.sheetActions)
        list.removeAllViews()
        for (a in actions) {
            val item = inflater.inflate(R.layout.item_action, list, false)
            val red = item.themeColor(M.attr.colorError)
            item.findViewById<ImageView>(R.id.actionIcon).apply {
                setImageResource(a.icon)
                if (a.danger) imageTintList = ColorStateList.valueOf(red)
            }
            item.findViewById<TextView>(R.id.actionText).apply {
                text = a.text
                if (a.danger) setTextColor(red)
            }
            item.findViewById<TextView>(R.id.actionHint).apply {
                text = a.hint
                visibility = if (a.hint == null) View.GONE else View.VISIBLE
            }
            item.setOnClickListener {
                onTap()
                if (a.close) dialog.dismiss()
                a.run()
            }
            list.addView(item)
        }
    }

    val isShowing get() = dialog.isShowing

    fun show() = dialog.show()

    fun dismiss() = dialog.dismiss()
}
