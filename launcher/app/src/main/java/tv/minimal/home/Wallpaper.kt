package tv.minimal.home

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/**
 * Full-screen rotating wallpaper. Sources, in priority order:
 *  1. your own photos in /sdcard/Android/data/tv.minimal.home/files/wallpapers (`adb push`),
 *  2. Bing's photos of the day (fetched by [BingPhotos] at most twice a day),
 *  3. soft generated landscapes (offline fallback).
 * Images are decoded off the UI thread and crossfaded so focus animations never stutter.
 */
class Wallpaper(private val context: Context) {
    /** Called with the photo's caption ("Place (© Credit)") whenever a new image fades in. */
    var onCaption: ((String?) -> Unit)? = null

    val view = FrameLayout(context)
    private val layers = List(2) {
        ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            alpha = 0f
            view.addView(this, FrameLayout.LayoutParams(-1, -1))
        }
    }
    private class Source(val caption: String?, val load: () -> Bitmap?)

    private val worker = Executors.newSingleThreadExecutor()
    private val network = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var front = -1
    private var sources: List<Source> = emptyList()
    private var signature = ""
    private var index = 0
    private val rotate = object : Runnable {
        override fun run() {
            next(fade = 2000)
            refreshBing()
            main.postDelayed(this, INTERVAL_MS)
        }
    }

    val folder: File? get() = context.getExternalFilesDir("wallpapers")

    fun start() {
        reloadSources()
        refreshBing()
        main.removeCallbacks(rotate)
        main.postDelayed(rotate, INTERVAL_MS)
    }

    fun stop() = main.removeCallbacks(rotate)

    fun shutdown() {
        stop()
        worker.shutdownNow()
        network.shutdownNow()
    }

    /** Rebuilds the playlist if the available photos changed, and shows its first image. */
    private fun reloadSources() {
        val dm = context.resources.displayMetrics
        val decode = { f: File -> decodeScaled(f, dm.widthPixels, dm.heightPixels) }
        val own = folder?.listFiles { f -> f.extension.lowercase() in EXTENSIONS }.orEmpty().sortedBy { it.name }
        val bing = if (own.isEmpty()) BingPhotos.cached(context) else emptyList()
        val sig = own.joinToString { it.name + it.length() } + "|" + bing.joinToString { it.file.name }
        if (sig == signature && sources.isNotEmpty()) return
        signature = sig
        sources = when {
            own.isNotEmpty() -> own.shuffled().map { f -> Source(null) { decode(f) } }
            // today's photo first, then the rest in random order
            bing.isNotEmpty() -> (listOf(bing.first()) + bing.drop(1).shuffled())
                .map { p -> Source(p.caption) { decode(p.file) } }
            else -> PALETTES.shuffled().map { p -> Source(null) { paint(p) } }
        }
        index = 0
        next(fade = if (front < 0) 700 else 2000)
    }

    private fun refreshBing() {
        network.execute {
            if (BingPhotos.refreshIfStale(context)) main.post { reloadSources() }
        }
    }

    private fun next(fade: Long) {
        if (sources.isEmpty()) return
        val source = sources[index++ % sources.size]
        worker.execute {
            val bmp = runCatching { source.load() }.getOrNull() ?: return@execute
            main.post { show(bmp, fade); onCaption?.invoke(source.caption) }
        }
    }

    private fun show(bmp: Bitmap, fade: Long) {
        val out = layers.getOrNull(front)
        front = if (front == 0) 1 else 0
        val into = layers[front]
        into.animate().cancel()
        into.setImageBitmap(bmp)
        into.alpha = 0f
        into.bringToFront() // fade the new image in on top, so there's no dark dip mid-crossfade
        into.animate().alpha(1f).setDuration(fade).setInterpolator(DecelerateInterpolator())
            .withEndAction { out?.apply { alpha = 0f; setImageDrawable(null) } }
            .start()
    }

    private class Palette(
        val skyTop: Int, val skyBottom: Int, val glow: Int, val sunX: Float,
        val hills: IntArray, val seed: Int,
    )

    /** A layered, misty mountain landscape in the given palette. */
    private fun paint(p: Palette): Bitmap {
        val w = 960
        val h = 540
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = LinearGradient(0f, 0f, 0f, h * 0.8f, p.skyTop, p.skyBottom, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
        paint.shader = RadialGradient(w * p.sunX, h * 0.55f, h * 0.85f,
            intArrayOf(p.glow and 0x00FFFFFF or 0x99000000.toInt(), p.glow and 0x00FFFFFF), null, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)

        val rnd = Random(p.seed)
        p.hills.forEachIndexed { i, color ->
            val base = h * (0.55f + i * 0.13f)
            val amp = h * (0.13f - i * 0.025f)
            val f1 = 0.8f + rnd.nextFloat() * 1.2f
            val f2 = 2.5f + rnd.nextFloat() * 3f
            val ph1 = rnd.nextFloat() * 6.28f
            val ph2 = rnd.nextFloat() * 6.28f
            val path = Path().apply {
                moveTo(0f, h.toFloat())
                var x = 0f
                while (x <= w) {
                    val t = x / w * 2 * PI.toFloat()
                    lineTo(x, base - amp * (0.65f * sin(t * f1 + ph1) + 0.35f * sin(t * f2 + ph2)))
                    x += 6f
                }
                lineTo(w.toFloat(), h.toFloat())
                close()
            }
            paint.shader = LinearGradient(0f, base - amp, 0f, h.toFloat(),
                color, blend(color, 0xFF000000.toInt(), 0.35f), Shader.TileMode.CLAMP)
            c.drawPath(path, paint)
        }
        return bmp
    }

    companion object {
        private const val INTERVAL_MS = 3 * 60 * 1000L
        private val EXTENSIONS = setOf("jpg", "jpeg", "png", "webp")

        private val PALETTES = listOf(
            // alpine lake
            Palette(0xFF16324F.toInt(), 0xFF7FB2C9.toInt(), 0xFFE9F3F5.toInt(), 0.7f,
                intArrayOf(0xFF4F7489.toInt(), 0xFF2F5161.toInt(), 0xFF1B3340.toInt(), 0xFF0E1C24.toInt()), 7),
            // desert dusk
            Palette(0xFF2B1C3A.toInt(), 0xFFD9886A.toInt(), 0xFFFFD39A.toInt(), 0.3f,
                intArrayOf(0xFF8A4F4A.toInt(), 0xFF5E3540.toInt(), 0xFF3A2233.toInt(), 0xFF1C1220.toInt()), 11),
            // misty morning
            Palette(0xFF51677A.toInt(), 0xFFC9D3D9.toInt(), 0xFFFFFFFF.toInt(), 0.55f,
                intArrayOf(0xFF8A9AA6.toInt(), 0xFF64747F.toInt(), 0xFF434F58.toInt(), 0xFF262E34.toInt()), 3),
            // night ridge
            Palette(0xFF060B1C.toInt(), 0xFF1E3A5C.toInt(), 0xFF7FB7FF.toInt(), 0.6f,
                intArrayOf(0xFF1B2B45.toInt(), 0xFF132036.toInt(), 0xFF0C1526.toInt(), 0xFF060B14.toInt()), 19),
            // forest valley
            Palette(0xFF1F3A3A.toInt(), 0xFF9CC5A1.toInt(), 0xFFF3F7D9.toInt(), 0.4f,
                intArrayOf(0xFF4E7A5A.toInt(), 0xFF335A43.toInt(), 0xFF1F3D2E.toInt(), 0xFF10221A.toInt()), 23),
        )

        fun decodeScaled(file: File, w: Int, h: Int): Bitmap? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= w && bounds.outHeight / (sample * 2) >= h) sample *= 2
            return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
        }
    }
}
