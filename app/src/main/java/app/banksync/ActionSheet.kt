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
 * What holding a Recent activity row opens: the row's summary and what can be done with its record.
 * It stays open under any dialog an action opens, and [fill] redraws it as the record changes.
 */
class ActionSheet(activity: Activity, onClose: () -> Unit) {
    /** One line. A [danger] one is red. [close]: the sheet closes on tap; otherwise it stays under the dialog the action opens. */
    class Action(
        val icon: Int, val text: String, val hint: String? = null,
        val danger: Boolean = false, val close: Boolean = true, val run: () -> Unit,
    )

    /** The top of the sheet, as the row shows it. */
    class Header(val icon: Drawable?, val title: CharSequence, val sub: CharSequence, val amount: CharSequence?, val amountColor: Int)

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

    /** Redraws the sheet. [paused] is the note shown while uploads are held, null when they aren't. */
    fun fill(header: Header, paused: String?, actions: List<Action>) {
        view.findViewById<ImageView>(R.id.sheetIcon).apply {
            visibility = if (header.icon == null) View.GONE else View.VISIBLE
            setImageDrawable(header.icon)
        }
        view.findViewById<TextView>(R.id.sheetTitle).text = header.title
        view.findViewById<TextView>(R.id.sheetSub).text = header.sub
        view.findViewById<TextView>(R.id.sheetAmount).apply {
            text = header.amount
            setTextColor(header.amountColor)
        }
        view.findViewById<View>(R.id.sheetPaused).visibility = if (paused == null) View.GONE else View.VISIBLE
        view.findViewById<TextView>(R.id.sheetPausedText).text = paused

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
