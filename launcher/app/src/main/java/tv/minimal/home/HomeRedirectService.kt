package tv.minimal.home

import android.accessibilityservice.AccessibilityService
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Keeps this launcher as the home screen on Fire OS, which always routes Home to Amazon's launcher.
 *
 * - Whenever Amazon's home page comes to the front (Home button, boot, voice, Back), bounce
 *   straight back here. Fire OS doesn't deliver remote keys to third-party accessibility
 *   services, so watching windows is the only hook available.
 * - Android may hold back app launches for up to 5 s after Home (its "app switch" lock), and a
 *   held-back launch can get lost, so after redirecting we check that the launcher actually
 *   came up and retry while Amazon's home is still in front.
 * - Third-party apps can't open the Inputs screen, so the Inputs shortcut opens Amazon's Settings
 *   grid and this service clicks its "Inputs" tile.
 */
class HomeRedirectService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private var lastRedirect = 0L
    private var amazonHomeInFront = false
    private var retries = 0

    private val verify = object : Runnable {
        override fun run() {
            if (MainActivity.visible || !amazonHomeInFront || retries >= MAX_RETRIES) return
            retries++
            goHome(this@HomeRedirectService)
            handler.postDelayed(this, RETRY_MS)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == SYSTEM_UI) return // overlays like the volume bar don't change what's in front
        val cls = event.className?.toString() ?: return
        amazonHomeInFront = pkg == AMAZON_LAUNCHER && cls.startsWith(AMAZON_HOME_PREFIX)
        if (pkg != AMAZON_LAUNCHER) return
        val now = SystemClock.uptimeMillis()

        if (cls == AMAZON_SETTINGS && now < pendingInputsClickUntil) {
            pendingInputsClickUntil = 0
            handler.postDelayed({ clickInputsTile(0) }, 250)
            return
        }
        if (!amazonHomeInFront) return
        if (now - lastRedirect < 700) return
        lastRedirect = now
        retries = 0
        goHome(this)
        handler.removeCallbacks(verify)
        handler.postDelayed(verify, RETRY_MS)
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
        private const val MAX_RETRIES = 4 // covers Android's 5-second app-switch lock
        const val AMAZON_SETTINGS = "com.amazon.tv.launcher.ui.MainSettingsActivity"

        @Volatile
        var pendingInputsClickUntil = 0L

        fun goHome(context: Context) {
            val intent = Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            val options = ActivityOptions.makeCustomAnimation(context, R.anim.home_enter, R.anim.none)
            context.startActivity(intent, options.toBundle())
        }

        fun amazonSettingsIntent(): Intent = Intent("amazon.intent.action.MAINSETTINGS")
            .setClassName(AMAZON_LAUNCHER, AMAZON_SETTINGS)
    }
}
