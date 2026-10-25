package app.banksync

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.transition.ChangeBounds
import android.transition.Fade
import android.transition.TransitionManager
import android.transition.TransitionSet
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.view.animation.PathInterpolator
import android.widget.TextView

/**
 * Small, short animations for things that change on the home screen. Each one plays only when a value
 * actually changed, and the first draw just appears. They follow the phone's animation settings, so
 * "remove animations" turns them off.
 */
private val smooth = PathInterpolator(0.4f, 0f, 0.2f, 1f)
private const val MOVE_MS = 380L

/** Changes made to [root]'s children from here on animate: things resize, slide and cross-fade. */
fun animateLayout(root: ViewGroup) {
    TransitionManager.beginDelayedTransition(
        root,
        TransitionSet().addTransition(ChangeBounds()).addTransition(Fade()).setOrdering(TransitionSet.ORDERING_TOGETHER)
            .setDuration(MOVE_MS).setInterpolator(smooth)
    )
}

private class Counted(var shown: Int, var running: ValueAnimator? = null)

/** Shows [target], counting up or down to it with a small bump when it changes; the first call just sets it. */
fun TextView.countTo(target: Int) {
    val state = tag as? Counted
    if (state == null) {
        tag = Counted(target)
        text = target.toString()
        return
    }
    if (state.shown == target) return
    state.running?.cancel()
    val from = text.toString().toIntOrNull() ?: state.shown
    state.shown = target
    state.running = ValueAnimator.ofInt(from, target).setDuration(500).apply {
        interpolator = smooth
        addUpdateListener { text = (it.animatedValue as Int).toString() }
        start()
    }
    // A quick bump so the eye catches it.
    animate().cancel()
    scaleX = 1.25f
    scaleY = 1.25f
    animate().scaleX(1f).scaleY(1f).setDuration(420).setInterpolator(OvershootInterpolator(2f)).start()
}

/** Scales a badge or icon in from small with a slight overshoot. */
fun View.pop() {
    scaleX = 0.4f
    scaleY = 0.4f
    animate().scaleX(1f).scaleY(1f).setDuration(420).setInterpolator(OvershootInterpolator(2.2f)).start()
}

/** A new row drops in from just above and fades in; [index] staggers a few at once. */
fun View.dropIn(index: Int) {
    alpha = 0f
    translationY = -12f * resources.displayMetrics.density
    animate().alpha(1f).translationY(0f).setStartDelay(minOf(index, 6) * 45L).setDuration(320).setInterpolator(smooth).start()
}

/** A row that changed state flashes from faint back to full. */
fun View.flash() {
    alpha = 0.35f
    animate().alpha(1f).setDuration(450).setInterpolator(smooth).start()
}

/** Fades from colours [from] to [to] (a fill, a text colour and so on) and hands each step to [apply]. */
fun fadeColors(from: IntArray, to: IntArray, apply: (IntArray) -> Unit) {
    val evaluator = ArgbEvaluator()
    ValueAnimator.ofFloat(0f, 1f).setDuration(MOVE_MS).apply {
        interpolator = smooth
        addUpdateListener { a ->
            apply(IntArray(to.size) { evaluator.evaluate(a.animatedFraction, from[it], to[it]) as Int })
        }
        start()
    }
}
