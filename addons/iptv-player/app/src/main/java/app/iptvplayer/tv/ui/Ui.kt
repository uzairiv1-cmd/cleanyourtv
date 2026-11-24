package app.iptvplayer.tv.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.TextView
import kotlin.math.roundToInt

object Palette {
    const val ORANGE = 0xFFFE5D09.toInt()
    const val ORANGE_LIGHT = 0xFFFF7A2E.toInt()
    const val ORANGE_DEEP = 0xFFE04E00.toInt()
    const val TEXT = 0xFFEEF0F4.toInt()
    const val DIM = 0xFF9AA0AA.toInt()
}

fun Context.dp(v: Number): Int = (v.toFloat() * resources.displayMetrics.density).roundToInt()

/** Near-black with a soft orange glow in the top-right and bottom-left corners. */
class GlowBackground : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun draw(canvas: Canvas) {
        val b = bounds
        val w = b.width().toFloat()
        val h = b.height().toFloat()
        paint.shader = LinearGradient(0f, 0f, 0f, h, 0xFF030303.toInt(), 0xFF0B0B0B.toInt(), Shader.TileMode.CLAMP)
        canvas.drawRect(b, paint)
        paint.shader = RadialGradient(w, 0f, w * 0.48f, 0x29FE5D09, 0x00FE5D09, Shader.TileMode.CLAMP)
        canvas.drawRect(b, paint)
        paint.shader = RadialGradient(0f, h, w * 0.42f, 0x21C83C00, 0x00C83C00, Shader.TileMode.CLAMP)
        canvas.drawRect(b, paint)
    }

    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.OPAQUE
}

/** A channel's initials on an orange wash, for logos that are missing or still loading. */
class MonogramDrawable(name: String, private val circle: Boolean = false) : Drawable() {
    private val initials = name.split(' ', '-', '_').filter { it.isNotBlank() && it[0].isLetterOrDigit() }
        .take(2).joinToString("") { it.take(1) }.uppercase().ifEmpty { "TV" }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.ORANGE_LIGHT
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val rect = RectF()

    override fun draw(canvas: Canvas) {
        val b = bounds
        rect.set(b)
        fill.shader = LinearGradient(rect.left, rect.top, rect.right, rect.bottom, 0x47FE5D09, 0x14C83C00, Shader.TileMode.CLAMP)
        if (circle) canvas.drawOval(rect, fill) else canvas.drawRoundRect(rect, b.height() * 0.14f, b.height() * 0.14f, fill)
        text.textSize = b.height() * 0.36f
        canvas.drawText(initials, rect.centerX(), rect.centerY() - (text.descent() + text.ascent()) / 2, text)
    }

    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/** Grows a little and lifts while focused: the TV's "you are here". */
fun View.zoomOnFocus(scale: Float = 1.05f, also: ((Boolean) -> Unit)? = null) {
    setOnFocusChangeListener { v, has ->
        val s = if (has) scale else 1f
        v.animate().scaleX(s).scaleY(s).setDuration(140).start()
        v.translationZ = if (has) 6f else 0f
        also?.invoke(has)
    }
}

/** Clips a view (an ImageView) to its background's shape: an oval, or rounded corners. */
fun View.clipToBackground() {
    outlineProvider = ViewOutlineProvider.BACKGROUND
    clipToOutline = true
}

/** "Living Room" with the last word in orange. */
fun TextView.setTitleWithAccent(title: String) {
    val i = title.trim().lastIndexOf(' ')
    val s = SpannableString(title)
    if (i > 0) s.setSpan(ForegroundColorSpan(Palette.ORANGE_LIGHT), i + 1, title.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    else s.setSpan(ForegroundColorSpan(Color.WHITE), 0, title.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    text = s
}

fun formatClock(ms: Long): String {
    if (ms <= 0) return "0:00"
    val s = ms / 1000
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}
