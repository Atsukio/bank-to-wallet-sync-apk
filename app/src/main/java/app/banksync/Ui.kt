package app.banksync

import android.content.res.ColorStateList
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import com.google.android.material.color.MaterialColors
import com.google.android.material.R as M

/** How a status should look: fine, worth a look, or broken. Each maps to a theme colour pair. */
enum class Tone(val container: Int, val onContainer: Int) {
    GOOD(M.attr.colorPrimaryContainer, M.attr.colorOnPrimaryContainer),
    NEUTRAL(M.attr.colorSecondaryContainer, M.attr.colorOnSecondaryContainer),
    WARN(M.attr.colorTertiaryContainer, M.attr.colorOnTertiaryContainer),
    BAD(M.attr.colorErrorContainer, M.attr.colorOnErrorContainer),
}

fun View.themeColor(attr: Int) = MaterialColors.getColor(this, attr)

/** A row's status as a solid, clearly different colour: money in, money out, waiting, needs you, failed, transfer. */
enum class Mark(val fill: Int, val glyph: Int) {
    IN(R.color.mark_in, R.color.mark_on_in),
    OUT(R.color.mark_out, R.color.mark_on_out),
    WAIT(R.color.mark_wait, R.color.mark_on_wait),
    ATTENTION(R.color.mark_attention, R.color.mark_on_attention),
    FAILED(R.color.mark_failed, R.color.mark_on_failed),
    TRANSFER(R.color.mark_transfer, R.color.mark_on_transfer),
}

/** Sets a round badge's icon and fills it for [mark]. */
fun ImageView.mark(icon: Int, mark: Mark) {
    setImageResource(icon)
    backgroundTintList = ColorStateList.valueOf(context.getColor(mark.fill))
    imageTintList = ColorStateList.valueOf(context.getColor(mark.glyph))
}

/** How a line of status text should be coloured: fine, still to do, or broken. */
enum class Status(val color: Int) {
    GOOD(R.color.status_good),
    WARN(R.color.status_warn),
    BAD(R.color.status_bad),
}

/** Shows [text] in the colour for [status]. */
fun TextView.status(text: CharSequence, status: Status) {
    this.text = text
    setTextColor(context.getColor(status.color))
}

/** Sets a round badge's icon and colours it for [tone]. */
fun ImageView.badge(icon: Int, tone: Tone) {
    setImageResource(icon)
    backgroundTintList = ColorStateList.valueOf(themeColor(tone.container))
    imageTintList = ColorStateList.valueOf(themeColor(tone.onContainer))
}
