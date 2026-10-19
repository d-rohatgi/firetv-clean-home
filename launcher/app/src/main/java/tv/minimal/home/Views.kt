package tv.minimal.home

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.TextPaint
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.max

fun Context.dp(v: Number): Float = v.toFloat() * resources.displayMetrics.density
fun Context.dpi(v: Number): Int = (dp(v) + 0.5f).toInt()

val MEDIUM: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
val LIGHT: Typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)

private fun View.noDefaultFocusHighlight() {
    if (Build.VERSION.SDK_INT >= 26) defaultFocusHighlightEnabled = false
}

/**
 * A rounded app card. On focus it lifts with a slight spring, gets lit from the top,
 * and a specular sheen sweeps across it once — the tvOS "card" feel, built only from
 * RenderNode transforms plus a cheap overlay so it stays smooth on low-end TV chips.
 */
@SuppressLint("ViewConstructor")
class AppTile(context: Context, val app: AppEntry, cornerDp: Float) : View(context) {
    var onFocusChange: ((Boolean) -> Unit)? = null

    private val radius = context.dp(cornerDp)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val lightPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sheenPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sheenMatrix = Matrix()
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.dp(2.5f)
        color = Color.WHITE
    }
    private val labelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = MEDIUM
        textAlign = Paint.Align.CENTER
        textSize = context.dp(12)
    }
    private val tint = if (app.banner == null) averageColor(app.icon) else 0xFF1C1F26.toInt()

    private var focusAmount = 0f
    private var sheen = -1f
    private var focusAnim: ValueAnimator? = null
    private var sheenAnim: ValueAnimator? = null

    init {
        isFocusable = true
        isClickable = true
        noDefaultFocusHighlight()
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) =
                outline.setRoundRect(0, 0, view.width, view.height, radius)
        }
        clipToOutline = true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        if (app.banner == null) {
            fillPaint.shader = LinearGradient(
                0f, 0f, 0f, h.toFloat(),
                blend(tint, Color.WHITE, 0.10f), blend(tint, Color.BLACK, 0.35f), Shader.TileMode.CLAMP,
            )
        }
        lightPaint.shader = LinearGradient(
            0f, 0f, 0f, h * 0.7f, 0x2EFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP,
        )
        val band = w * 0.55f
        sheenPaint.shader = LinearGradient(
            0f, 0f, band, band * 0.45f,
            intArrayOf(0x00FFFFFF, 0x40FFFFFF, 0x00FFFFFF), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP,
        )
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val banner = app.banner
        if (banner != null) {
            c.drawColor(tint)
            fitCenterCrop(banner, width, height)
            banner.draw(c)
        } else {
            c.drawRect(0f, 0f, w, h, fillPaint)
            val s = (h * 0.42f).toInt()
            val cx = width / 2
            val top = (h * 0.16f).toInt()
            app.icon.setBounds(cx - s / 2, top, cx + s / 2, top + s)
            app.icon.draw(c)
            val label = TextUtils.ellipsize(app.label, labelPaint, w - context.dp(16), TextUtils.TruncateAt.END)
            c.drawText(label, 0, label.length, w / 2, h - context.dp(11), labelPaint)
        }
        if (focusAmount > 0f) {
            lightPaint.alpha = (255 * focusAmount).toInt()
            c.drawRect(0f, 0f, w, h, lightPaint)
        }
        if (sheen in 0f..1f) {
            val band = w * 0.55f
            sheenMatrix.setTranslate(-band + (w + band) * sheen, 0f)
            sheenPaint.shader.setLocalMatrix(sheenMatrix)
            c.drawRect(0f, 0f, w, h, sheenPaint)
        }
        if (focusAmount > 0f) {
            ringPaint.alpha = (255 * focusAmount).toInt()
            val inset = ringPaint.strokeWidth / 2
            c.drawRoundRect(inset, inset, w - inset, h - inset, radius - inset, radius - inset, ringPaint)
        }
    }

    override fun onFocusChanged(gained: Boolean, direction: Int, previous: Rect?) {
        super.onFocusChanged(gained, direction, previous)
        animate().cancel()
        if (gained) {
            animate().scaleX(FOCUS_SCALE).scaleY(FOCUS_SCALE).translationZ(context.dp(12))
                .setDuration(280).setInterpolator(OvershootInterpolator(1.2f)).start()
            animateFocus(1f)
            startSheen()
        } else {
            animate().scaleX(1f).scaleY(1f).translationZ(0f)
                .setDuration(220).setInterpolator(DecelerateInterpolator(1.5f)).start()
            animateFocus(0f)
            sheenAnim?.cancel()
            sheen = -1f
        }
        onFocusChange?.invoke(gained)
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        if (!isFocused) return
        val target = if (isPressed) PRESS_SCALE else FOCUS_SCALE
        animate().scaleX(target).scaleY(target).setDuration(110)
            .setInterpolator(DecelerateInterpolator()).start()
    }

    private fun animateFocus(to: Float) {
        focusAnim?.cancel()
        focusAnim = ValueAnimator.ofFloat(focusAmount, to).apply {
            duration = 220
            addUpdateListener { focusAmount = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    private fun startSheen() {
        sheenAnim?.cancel()
        sheenAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            startDelay = 160
            duration = 950
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { sheen = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    companion object {
        const val FOCUS_SCALE = 1.11f
        const val PRESS_SCALE = 1.04f
    }
}

/** Small frosted shortcut pill (Inputs, Settings). Turns solid white when focused, like tvOS. */
@SuppressLint("ViewConstructor")
class Pill(context: Context, iconRes: Int, text: String) : LinearLayout(context) {
    private val bg = GradientDrawable().apply {
        cornerRadius = context.dp(100)
        setColor(IDLE)
        setStroke(context.dpi(1), 0x40FFFFFF)
    }
    private val icon = ImageView(context).apply { setImageResource(iconRes); setColorFilter(Color.WHITE) }
    private val label = TextView(context).apply {
        this.text = text
        textSize = 13f
        typeface = MEDIUM
        setTextColor(Color.WHITE)
    }
    private var colorAnim: ValueAnimator? = null
    private var current = IDLE

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = bg
        isFocusable = true
        isClickable = true
        noDefaultFocusHighlight()
        setPadding(context.dpi(12), context.dpi(6), context.dpi(15), context.dpi(6))
        addView(icon, LayoutParams(context.dpi(15), context.dpi(15)).apply { marginEnd = context.dpi(6) })
        addView(label)
    }

    override fun onFocusChanged(gained: Boolean, direction: Int, previous: Rect?) {
        super.onFocusChanged(gained, direction, previous)
        val scale = if (gained) 1.08f else 1f
        animate().scaleX(scale).scaleY(scale).setDuration(220)
            .setInterpolator(if (gained) OvershootInterpolator(1.4f) else DecelerateInterpolator()).start()
        val to = if (gained) Color.WHITE else IDLE
        val fg = if (gained) 0xFF101217.toInt() else Color.WHITE
        colorAnim?.cancel()
        colorAnim = ValueAnimator.ofObject(ArgbEvaluator(), current, to).apply {
            duration = 180
            addUpdateListener { current = it.animatedValue as Int; bg.setColor(current) }
            start()
        }
        icon.setColorFilter(fg)
        label.setTextColor(fg)
    }

    companion object {
        private const val IDLE = 0x40000000
    }
}

/**
 * A viewport that never jumps: its single child is measured unbounded along [horizontal]
 * or vertical axis, and focus changes glide the scroll position with an ease-out curve.
 * Uses scrollX/Y (not translation) so D-pad focus search sees on-screen positions.
 */
class GlideFrame(
    context: Context,
    private val horizontal: Boolean,
    private val edgeDp: Float = 56f,
) : FrameLayout(context) {
    private var anim: ValueAnimator? = null
    private var target = 0

    init {
        clipChildren = false
        clipToPadding = false
    }

    override fun measureChildWithMargins(child: View, wSpec: Int, wUsed: Int, hSpec: Int, hUsed: Int) {
        val lp = child.layoutParams as MarginLayoutParams
        val unbounded = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        val w = if (horizontal) unbounded else getChildMeasureSpec(
            wSpec, paddingLeft + paddingRight + lp.leftMargin + lp.rightMargin + wUsed, lp.width)
        val h = if (!horizontal) unbounded else getChildMeasureSpec(
            hSpec, paddingTop + paddingBottom + lp.topMargin + lp.bottomMargin + hUsed, lp.height)
        child.measure(w, h)
    }

    /** Glide so [view] (a descendant) sits comfortably inside the viewport. */
    fun reveal(view: View) {
        val content = getChildAt(0) ?: return
        var pos = 0
        var cur: View = view
        while (cur !== content) {
            pos += if (horizontal) cur.left else cur.top
            cur = cur.parent as? View ?: return
        }
        val size = if (horizontal) view.width else view.height
        val viewport = if (horizontal) width else height
        val max = max(0, (if (horizontal) content.width else content.height) - viewport)
        var t = target
        if (horizontal) {
            // keep a peek of the neighbouring tile visible on whichever side we're moving toward
            val edge = (context.dp(edgeDp) + size * 0.35f).toInt()
            if (pos - edge < t) t = pos - edge
            else if (pos + size + edge > t + viewport) t = pos + size + edge - viewport
        } else {
            // anything on the first screen keeps the page at the top; further down, centre it
            t = if (pos + size <= viewport) 0 else pos + size / 2 - viewport / 2
        }
        glideTo(t.coerceIn(0, max))
    }

    fun glideTo(to: Int, animated: Boolean = true) {
        target = to
        val from = if (horizontal) scrollX else scrollY
        anim?.cancel()
        if (from == to) return
        if (!animated) { apply(to); return }
        anim = ValueAnimator.ofInt(from, to).apply {
            duration = 420
            interpolator = DecelerateInterpolator(2.2f)
            addUpdateListener { apply(it.animatedValue as Int) }
            start()
        }
    }

    private fun apply(v: Int) = if (horizontal) scrollTo(v, 0) else scrollTo(0, v)
}

/** Average opaque colour of an icon, darkened slightly, for icon-only tiles. */
fun averageColor(d: Drawable): Int {
    val n = 8
    val bmp = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
    val copy = d.constantState?.newDrawable()?.mutate() ?: d
    copy.setBounds(0, 0, n, n)
    copy.draw(Canvas(bmp))
    var r = 0L; var g = 0L; var b = 0L; var count = 0L
    for (y in 0 until n) for (x in 0 until n) {
        val p = bmp.getPixel(x, y)
        if (Color.alpha(p) < 128) continue
        r += Color.red(p); g += Color.green(p); b += Color.blue(p); count++
    }
    bmp.recycle()
    if (count == 0L) return 0xFF2A2F3A.toInt()
    return blend(Color.rgb((r / count).toInt(), (g / count).toInt(), (b / count).toInt()), Color.BLACK, 0.45f)
}

fun blend(a: Int, b: Int, t: Float): Int = Color.rgb(
    (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt(),
    (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt(),
    (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt(),
)

private fun fitCenterCrop(d: Drawable, w: Int, h: Int) {
    val iw = d.intrinsicWidth
    val ih = d.intrinsicHeight
    if (iw <= 0 || ih <= 0) { d.setBounds(0, 0, w, h); return }
    val s = max(w / iw.toFloat(), h / ih.toFloat())
    val dw = (iw * s).toInt()
    val dh = (ih * s).toInt()
    d.setBounds((w - dw) / 2, (h - dh) / 2, (w + dw) / 2, (h + dh) / 2)
}
