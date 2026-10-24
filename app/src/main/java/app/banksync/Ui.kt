package app.banksync

import android.content.res.ColorStateList
import android.view.View
import android.widget.ImageView
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

/** Sets a round badge's icon and colours it for [tone]. */
fun ImageView.badge(icon: Int, tone: Tone) {
    setImageResource(icon)
    backgroundTintList = ColorStateList.valueOf(themeColor(tone.container))
    imageTintList = ColorStateList.valueOf(themeColor(tone.onContainer))
}
