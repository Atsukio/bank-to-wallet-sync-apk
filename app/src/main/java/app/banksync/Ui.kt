package app.banksync

import android.content.res.ColorStateList
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
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

/** A Recent activity row's status pill: on its way, waiting for the user, or broken. */
enum class Chip(val bg: Int, val fg: Int, val themed: Boolean) {
    WAIT(M.attr.colorSecondaryContainer, M.attr.colorOnSecondaryContainer, true),
    YOU(R.color.chip_you, R.color.chip_on_you, false),
    BAD(M.attr.colorErrorContainer, M.attr.colorOnErrorContainer, true),
}

/** Shows [text] as a pill coloured for [chip], or hides the view when [text] is null. */
fun TextView.chip(text: CharSequence?, chip: Chip) {
    visibility = if (text == null) View.GONE else View.VISIBLE
    this.text = text
    fun color(c: Int) = if (chip.themed) themeColor(c) else context.getColor(c)
    backgroundTintList = ColorStateList.valueOf(color(chip.bg))
    setTextColor(color(chip.fg))
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

/** Colours a dialog button that deletes or drops something in the theme's error colour. Call after show(). */
fun AlertDialog.danger(which: Int) {
    getButton(which)?.let { it.setTextColor(it.themeColor(M.attr.colorError)) }
}

/** Sets a round badge's icon and colours it for [tone]. */
fun ImageView.badge(icon: Int, tone: Tone) {
    setImageResource(icon)
    backgroundTintList = ColorStateList.valueOf(themeColor(tone.container))
    imageTintList = ColorStateList.valueOf(themeColor(tone.onContainer))
}
