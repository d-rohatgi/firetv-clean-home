package tv.minimal.home

import android.app.Activity
import android.app.ActivityOptions
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView
import android.widget.Toast
import java.util.Calendar

/**
 * Home screen: a full-bleed rotating wallpaper with a small clock up top and a frosted dock of
 * streaming apps along the bottom. Pressing Down from the dock glides up to the All Apps grid.
 */
class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var wallpaper: Wallpaper
    private lateinit var dim: View
    private lateinit var page: GlideFrame
    private lateinit var greeting: TextView
    private lateinit var caption: TextView
    private lateinit var dock: FrameLayout
    private lateinit var dockFrame: GlideFrame
    private lateinit var dockRow: LinearLayout
    private lateinit var hint: TextView
    private lateinit var allTitle: TextView
    private lateinit var grid: LinearLayout
    private var firstTile: View? = null
    private var isResumedNow = false
    private var heroVisibility = 1f // 1 on the first screen, 0 once the grid has slid up

    private val pad by lazy { dpi(56) }
    private val dockPad by lazy { dpi(14) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val screenH = resources.displayMetrics.heightPixels

        val root = FrameLayout(this).apply { setBackgroundColor(0xFF07090D.toInt()) }
        wallpaper = Wallpaper(this)
        root.addView(wallpaper.view, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        // shade the top (clock) and bottom (dock) so text reads over any photo
        root.addView(View(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0x80000000.toInt(), 0x14000000, 0x00000000, 0x26000000, 0xA6000000.toInt()),
            )
        }, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        dim = View(this).apply { setBackgroundColor(Color.BLACK); alpha = 0f }
        root.addView(dim, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
            setPadding(0, 0, 0, dpi(64))
        }
        page = GlideFrame(this, horizontal = false)
        page.addView(column, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(page, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        // First screen: header at the top, dock at the bottom, wallpaper in between.
        val hero = FrameLayout(this).apply { clipChildren = false }
        column.addView(hero, LinearLayout.LayoutParams(MATCH_PARENT, screenH))
        hero.addView(buildHeader(), FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.TOP).apply {
            topMargin = dpi(34)
        })
        hero.addView(buildDockArea(), FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM).apply {
            bottomMargin = dpi(14)
        })
        // Photo location + credit, on the same line as the "All Apps" hint.
        caption = TextView(this).apply {
            textSize = 11f
            setTextColor(Color.WHITE)
            alpha = 0f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.MIDDLE
            maxWidth = (resources.displayMetrics.widthPixels * 0.36f).toInt()
            softShadow()
        }
        hero.addView(caption, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.END).apply {
            bottomMargin = dpi(14)
            marginEnd = pad
        })
        wallpaper.onCaption = ::showCaption

        // Below the fold: everything else.
        allTitle = TextView(this).apply {
            text = "All Apps"
            textSize = 15f
            typeface = MEDIUM
            letterSpacing = 0.02f
            setTextColor(0xB3FFFFFF.toInt())
            setPadding(pad, 0, pad, 0)
        }
        column.addView(allTitle, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dpi(8) })
        grid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
            setPadding(pad, dpi(14), pad, 0)
        }
        column.addView(grid, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        // Dim the wallpaper and hide the hint as the grid slides up.
        page.setOnScrollChangeListener { _, _, y, _, _ ->
            val t = (y / (screenH * 0.55f)).coerceIn(0f, 1f)
            dim.alpha = 0.65f * t
            heroVisibility = (1f - t * 3f).coerceAtLeast(0f)
            hint.alpha = HINT_ALPHA * heroVisibility
            if (caption.text.isNotEmpty()) caption.alpha = CAPTION_ALPHA * heroVisibility
        }

        setContentView(root)
        root.viewTreeObserver.addOnGlobalFocusChangeListener { _, now -> onFocusMoved(now) }

        loadApps(focusPkg = null)
        registerReceiver(packageReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        })
    }

    private fun TextView.softShadow() = setShadowLayer(dp(10), 0f, dp(1), 0x73000000)

    private fun buildHeader(): View {
        greeting = TextView(this).apply {
            textSize = 13f
            typeface = MEDIUM
            letterSpacing = 0.16f
            setTextColor(0xD9FFFFFF.toInt())
            softShadow()
        }
        val clock = TextClock(this).apply {
            format12Hour = "h:mm"
            format24Hour = "H:mm"
            textSize = 66f
            typeface = LIGHT
            letterSpacing = -0.01f
            includeFontPadding = false
            setTextColor(Color.WHITE)
            softShadow()
        }
        val date = TextClock(this).apply {
            format12Hour = "EEEE, MMMM d"
            format24Hour = "EEEE, MMMM d"
            isAllCaps = true
            textSize = 12f
            typeface = MEDIUM
            letterSpacing = 0.14f
            setTextColor(0xB3FFFFFF.toInt())
            softShadow()
        }
        val left = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(greeting)
            addView(clock, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dpi(2) })
            addView(date, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dpi(2) })
        }
        val inputs = Pill(this, R.drawable.ic_input, "Inputs").apply { setOnClickListener { openInputs() } }
        val settings = Pill(this, R.drawable.ic_settings, "Settings").apply {
            setOnClickListener { open(HomeRedirectService.amazonSettingsIntent()) }
        }
        val right = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            clipChildren = false
            addView(inputs)
            addView(settings, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dpi(10) })
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            clipChildren = false
            setPadding(pad, 0, pad, 0)
            addView(left, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(right, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dpi(2) })
        }
    }

    /** "Grand Canal, Venice (© Credit)" → place in white, credit dimmer. */
    private fun showCaption(text: String?) {
        val styled = text?.let {
            val split = it.indexOf(" (©")
            if (split < 0) SpannableString(it) else SpannableString(
                it.substring(0, split) + "  ·  " + it.substring(split + 2).removeSuffix(")"),
            ).apply {
                setSpan(ForegroundColorSpan(0x99FFFFFF.toInt()), split, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        caption.animate().cancel()
        caption.animate().alpha(0f).setDuration(400).withEndAction {
            caption.text = styled ?: ""
            if (styled != null) caption.animate().alpha(CAPTION_ALPHA * heroVisibility).setDuration(900).start()
        }.start()
    }

    private fun buildDockArea(): View {
        dockRow =LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            clipChildren = false
            clipToPadding = false
            setPadding(dockPad, dockPad, dockPad, dockPad)
        }
        dockFrame = GlideFrame(this, horizontal = true, edgeDp = 14f)
        dockFrame.addView(dockRow, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        dock = FrameLayout(this).apply {
            // Frosted-glass panel: translucent fill, hairline border, clipped to its rounded shape.
            background = GradientDrawable().apply {
                cornerRadius = dp(22)
                setColor(0x4D0B0F18)
                setStroke(dpi(1), 0x33FFFFFF)
            }
            clipToOutline = true
            addView(dockFrame, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        }
        hint = TextView(this).apply {
            text = "All Apps"
            textSize = 12f
            typeface = MEDIUM
            letterSpacing = 0.06f
            setTextColor(Color.WHITE)
            alpha = HINT_ALPHA
            gravity = Gravity.CENTER
            softShadow()
            val chevron = getDrawable(R.drawable.ic_chevron_down)?.mutate()?.apply {
                setBounds(0, 0, dpi(16), dpi(16))
                setTint(Color.WHITE)
            }
            setCompoundDrawablesRelative(null, null, chevron, null)
            compoundDrawablePadding = dpi(2)
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            addView(dock,LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                marginStart = pad
                marginEnd = pad
            })
            addView(hint, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                topMargin = dpi(10)
                gravity = Gravity.CENTER_HORIZONTAL
            })
        }
    }

    private fun loadApps(focusPkg: String?) {
        val catalog = Apps.load(this)
        val contentW = resources.displayMetrics.widthPixels - 2 * pad

        dockRow.removeAllViews()
        val dockGap = dpi(14)
        val dockW = dpi(146)
        catalog.favorites.forEachIndexed { i, app ->
            val tile = AppTile(this, app, cornerDp = 12f).apply { setOnClickListener { launch(app, this) } }
            dockRow.addView(tile, LinearLayout.LayoutParams(dockW, dockW * 9 / 16).apply {
                if (i > 0) marginStart = dockGap
            })
        }
        dock.visibility = if (catalog.favorites.isEmpty()) View.GONE else View.VISIBLE

        grid.removeAllViews()
        val cols = 5
        val gap = dpi(22)
        val w = (contentW - (cols - 1) * gap) / cols
        catalog.others.chunked(cols).forEachIndexed { r, rowApps ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                clipChildren = false
            }
            rowApps.forEachIndexed { i, app ->
                val tile = AppTile(this, app, cornerDp = 11f).apply { setOnClickListener { launch(app, this) } }
                row.addView(tile, LinearLayout.LayoutParams(w, w * 9 / 16).apply { if (i > 0) marginStart = gap })
            }
            grid.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                if (r > 0) topMargin = gap
            })
        }
        allTitle.visibility = if (catalog.others.isEmpty()) View.GONE else View.VISIBLE
        hint.visibility = allTitle.visibility

        val tiles = allTiles()
        firstTile = tiles.firstOrNull()
        val target = focusPkg?.let { pkg -> tiles.firstOrNull { it.app.pkg == pkg } } ?: firstTile
        page.post { target?.requestFocus() }
    }

    private fun allTiles(): List<AppTile> {
        val out = ArrayList<AppTile>()
        fun walk(v: View) {
            if (v is AppTile) out.add(v)
            else if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(dockRow)
        walk(grid)
        return out
    }

    private fun onFocusMoved(now: View?) {
        now ?: return
        var p: android.view.ViewParent? = now.parent
        while (p != null) {
            if (p is GlideFrame) p.reveal(now)
            p = p.parent
        }
    }

    private fun launch(app: AppEntry, from: View) {
        val opts = ActivityOptions.makeScaleUpAnimation(from, 0, 0, from.width, from.height).toBundle()
        try {
            startActivity(app.intent(), opts)
        } catch (e: Exception) {
            Toast.makeText(this, "Couldn't open ${app.label}", Toast.LENGTH_SHORT).show()
        }
    }

    /** Amazon's Inputs screen is signature-protected; open Settings and let the service click "Inputs". */
    private fun openInputs() {
        HomeRedirectService.pendingInputsClickUntil = SystemClock.uptimeMillis() + 4000
        open(HomeRedirectService.amazonSettingsIntent())
    }

    private fun open(intent: Intent) {
        try {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            Toast.makeText(this, "Not available on this TV", Toast.LENGTH_SHORT).show()
        }
    }

    private fun goToTop() {
        page.glideTo(0)
        dockFrame.glideTo(0)
        firstTile?.requestFocus()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Home pressed while already home → back to the dock, like tvOS.
        // (Coming back from an app arrives here too, but then we keep the launched tile focused.)
        if (isResumedNow) goToTop()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Home has nowhere to go back to; Back just returns to the dock.
        goToTop()
    }

    override fun onStart() {
        super.onStart()
        updateGreeting()
        wallpaper.start()
        registerReceiver(timeReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        })
    }

    override fun onResume() {
        super.onResume()
        isResumedNow = true
        visible = true
    }

    override fun onPause() {
        super.onPause()
        isResumedNow = false
        visible = false
    }

    override fun onStop() {
        super.onStop()
        wallpaper.stop()
        unregisterReceiver(timeReceiver)
    }

    override fun onDestroy() {
        super.onDestroy()
        wallpaper.shutdown()
        unregisterReceiver(packageReceiver)
        handler.removeCallbacksAndMessages(null)
    }

    private fun updateGreeting() {
        greeting.text = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
            in 5..11 -> "GOOD MORNING"
            in 12..16 -> "GOOD AFTERNOON"
            else -> "GOOD EVENING"
        }
    }

    private val timeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = updateGreeting()
    }

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            handler.removeCallbacks(reload)
            handler.postDelayed(reload, 600)
        }
    }

    private val reload = Runnable { loadApps((currentFocus as? AppTile)?.app?.pkg) }

    companion object {
        private const val HINT_ALPHA = 0.55f
        private const val CAPTION_ALPHA = 0.8f

        /** True while the home screen is in front; the redirect service uses it to confirm a redirect landed. */
        @Volatile
        var visible = false
            private set
    }
}
