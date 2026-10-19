package tv.minimal.home

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper

class AppEntry(
    val pkg: String,
    val label: String,
    val banner: Drawable?,
    val icon: Drawable,
    private val launchIntent: Intent,
) {
    fun intent(): Intent = Intent(launchIntent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}

object Apps {
    /** Top row, in order. Each entry lists package alternatives; the first one installed wins. */
    private val FAVORITES = listOf(
        listOf("com.netflix.ninja"),                       // Netflix
        listOf("com.amazon.firetv.youtube"),               // YouTube
        listOf("com.amazon.firebat", "com.amazon.avod"),   // Prime Video
        listOf("com.disney.disneyplus"),                   // Disney+
        listOf("com.hbo.hbonow"),                          // Max
        listOf("com.hulu.plus"),                           // Hulu
        listOf("com.apple.atve.amazon.appletv"),           // Apple TV
        listOf("com.peacock.peacockfiretv"),               // Peacock
        listOf("com.cbs.ott"),                             // Paramount+
        listOf("com.amazon.firetv.youtube.tv"),            // YouTube TV
    )

    /** System entries that make no sense as tiles. */
    private val HIDDEN = setOf(
        "com.amazon.tv.launcher",
        "com.amazon.ftv.profilepicker",
        "com.amazon.ftv.screensaver",
        "com.amazon.ssm",
        "com.amazon.tv.settings.v2",
        "com.android.settings",
        "com.android.documentsui",
        "com.android.providers.downloads.ui",
    )

    class Catalog(val favorites: List<AppEntry>, val others: List<AppEntry>)

    @Volatile
    private var cached: Catalog? = null
    private val listeners = LinkedHashSet<() -> Unit>()
    private var watching = false

    /** Bumped whenever installed apps change; views compare it to know when to rebuild. */
    var version = 1
        private set

    /** The installed apps, loaded once and shared by every home screen in this process. */
    fun catalog(context: Context): Catalog = cached ?: load(context.applicationContext).also { cached = it }

    fun addListener(l: () -> Unit) = listeners.add(l)
    fun removeListener(l: () -> Unit) = listeners.remove(l)

    /** Starts listening for installs/uninstalls for the life of the process (idempotent). */
    fun watch(context: Context) {
        if (watching) return
        watching = true
        val handler = Handler(Looper.getMainLooper())
        val invalidate = Runnable {
            cached = null
            version++
            listeners.toList().forEach { it() }
        }
        context.applicationContext.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                handler.removeCallbacks(invalidate)
                handler.postDelayed(invalidate, 600)
            }
        }, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        })
    }

    private fun load(context: Context): Catalog {
        val pm = context.packageManager
        val found = LinkedHashMap<String, AppEntry>()

        for (category in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            val query = Intent(Intent.ACTION_MAIN).addCategory(category)
            for (ri in pm.queryIntentActivities(query, 0)) {
                val pkg = ri.activityInfo.packageName
                if (pkg in found || pkg in HIDDEN || pkg == context.packageName) continue
                val launch = pm.getLeanbackLaunchIntentForPackage(pkg)
                    ?: pm.getLaunchIntentForPackage(pkg)
                    ?: continue
                val info = launch.component?.let { runCatching { pm.getActivityInfo(it, 0) }.getOrNull() }
                    ?: ri.activityInfo
                found[pkg] = AppEntry(
                    pkg = pkg,
                    label = info.loadLabel(pm).toString().trim(),
                    banner = info.loadBanner(pm),
                    icon = info.loadIcon(pm),
                    launchIntent = launch,
                )
            }
        }

        val favorites = FAVORITES.mapNotNull { group ->
            val hit = group.firstNotNullOfOrNull { found[it] }
            group.forEach { found.remove(it) } // also hides duplicate alternatives
            hit
        }
        val others = found.values.sortedBy { it.label.lowercase() }
        return Catalog(favorites, others)
    }
}
