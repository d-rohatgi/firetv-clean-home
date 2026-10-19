package tv.minimal.home

import android.accessibilityservice.AccessibilityService
import android.app.ActivityOptions
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.ContextThemeWrapper
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast

/**
 * Keeps this launcher as the home screen on Fire OS, which always routes Home to Amazon's launcher
 * and doesn't deliver remote keys to third-party accessibility services.
 *
 * - When Amazon's home page comes to the front (Home button, boot, voice, Back), an overlay copy
 *   of [HomeView] appears instantly on top of it, fully usable, and the launcher activity is
 *   started. Android holds that start back for up to 5 s after a Home press (its "app switch"
 *   lock, restarted by every press), so we retry until it lands; the activity then mirrors the
 *   overlay's focus and scroll and the overlay is removed — no visible switch.
 * - The overlay steps aside as soon as anything else comes to the front (an app, Settings, the
 *   screensaver), and after 12 s at most.
 * - Third-party apps can't open the Inputs screen, so the Inputs shortcut opens Amazon's Settings
 *   grid and this service clicks its "Inputs" tile.
 */
class HomeRedirectService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private val windowManager by lazy { getSystemService(Context.WINDOW_SERVICE) as WindowManager }
    private var lastRedirect = 0L
    private var amazonHomeInFront = false
    private var retries = 0
    private var overlay: HomeView? = null
    private var overlayShown = false

    private val verify = object : Runnable {
        override fun run() {
            if (MainActivity.visible || !amazonHomeInFront || retries >= MAX_RETRIES) return
            retries++
            goHome(this@HomeRedirectService)
            handler.postDelayed(this, RETRY_MS)
        }
    }
    private val overlayTimeout = Runnable { hideOverlay() }

    /** What the overlay's tiles and pills do: start things directly from the service. */
    private val overlayHost = object : HomeView.Host {
        override fun launch(app: AppEntry, from: View) = start(app.intent())
        override fun open(intent: Intent) = start(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        private fun start(intent: Intent) {
            handler.removeCallbacks(verify) // the user is leaving home; don't pull the launcher up over it
            try {
                startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(this@HomeRedirectService, "Couldn't open that", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = hideOverlay()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Apps.watch(this)
        registerReceiver(screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_DREAMING_STARTED)
        })
        handler.postDelayed({ overlayView() }, 3000) // build ahead of time so the first Home is instant
    }

    override fun onDestroy() {
        hideOverlay()
        unregisterReceiver(screenReceiver)
        handler.removeCallbacksAndMessages(null)
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName || pkg == SYSTEM_UI) return // our own windows, volume bar etc.
        val cls = event.className?.toString() ?: return
        val now = SystemClock.uptimeMillis()
        amazonHomeInFront = pkg == AMAZON_LAUNCHER && cls.startsWith(AMAZON_HOME_PREFIX)

        if (!amazonHomeInFront) {
            hideOverlay()
            if (cls == AMAZON_SETTINGS && now < pendingInputsClickUntil) {
                pendingInputsClickUntil = 0
                handler.postDelayed({ clickInputsTile(0) }, 250)
            }
            return
        }

        showOverlay()
        if (now - lastRedirect < 700) return
        lastRedirect = now
        retries = 0
        goHome(this)
        handler.removeCallbacks(verify)
        handler.postDelayed(verify, RETRY_MS)
    }

    /** Called by the activity once it's in front: take over the overlay's exact state, then drop it. */
    fun handOffTo(home: HomeView) {
        val from = overlay ?: return
        if (!overlayShown) return
        home.mirror(from)
        // let the activity draw underneath before uncovering it
        home.postOnAnimation { home.postDelayed({ hideOverlay() }, 80) }
    }

    private fun overlayView(): HomeView = overlay ?: HomeView(ContextThemeWrapper(this, R.style.Theme_Home), overlayHost).also {
        // lay it out up front so focus and scroll positions are right on the very first frame
        val dm = resources.displayMetrics
        it.measure(
            View.MeasureSpec.makeMeasureSpec(dm.widthPixels, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(dm.heightPixels, View.MeasureSpec.EXACTLY),
        )
        it.layout(0, 0, dm.widthPixels, dm.heightPixels)
        overlay = it
    }

    private fun showOverlay() {
        if (overlayShown || MainActivity.visible) return
        val view = overlayView()
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_FULLSCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.OPAQUE,
        ).apply { windowAnimations = 0 }
        try {
            windowManager.addView(view, params)
        } catch (e: Exception) {
            return
        }
        overlayShown = true
        view.showAt(MainActivity.lastFocusedPkg)
        handler.removeCallbacks(overlayTimeout)
        handler.postDelayed(overlayTimeout, OVERLAY_MAX_MS)
    }

    private fun hideOverlay() {
        if (!overlayShown) return
        overlayShown = false
        handler.removeCallbacks(overlayTimeout)
        try {
            windowManager.removeViewImmediate(overlay)
        } catch (e: Exception) {
            // already gone
        }
    }

    private fun clickInputsTile(attempt: Int) {
        var node: AccessibilityNodeInfo? =
            rootInActiveWindow?.findAccessibilityNodeInfosByText("Inputs")?.firstOrNull()
        while (node != null && !node.isClickable) node = node.parent
        if (node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) return
        if (attempt < 8) handler.postDelayed({ clickInputsTile(attempt + 1) }, 150)
    }

    override fun onInterrupt() {}

    companion object {
        private const val AMAZON_LAUNCHER = "com.amazon.tv.launcher"
        private const val AMAZON_HOME_PREFIX = "com.amazon.tv.launcher.ui.HomeActivity"
        private const val SYSTEM_UI = "com.android.systemui"
        private const val RETRY_MS = 1500L
        private const val MAX_RETRIES = 6 // outlasts Android's 5-second app-switch lock
        private const val OVERLAY_MAX_MS = 12_000L
        const val AMAZON_SETTINGS = "com.amazon.tv.launcher.ui.MainSettingsActivity"

        /** The running service (same process as the activity), for the overlay hand-off. */
        @Volatile
        var instance: HomeRedirectService? = null
            private set

        @Volatile
        var pendingInputsClickUntil = 0L

        fun goHome(context: Context) {
            val intent = Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            val options = ActivityOptions.makeCustomAnimation(context, R.anim.none, R.anim.none)
            context.startActivity(intent, options.toBundle())
        }

        fun amazonSettingsIntent(): Intent = Intent("amazon.intent.action.MAINSETTINGS")
            .setClassName(AMAZON_LAUNCHER, AMAZON_SETTINGS)
    }
}
