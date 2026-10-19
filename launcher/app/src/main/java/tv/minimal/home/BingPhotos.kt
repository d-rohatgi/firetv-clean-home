package tv.minimal.home

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * Bing's "photo of the day" archive as a wallpaper source. Fetches at most every 12 hours,
 * keeps the newest [KEEP] photos (1920x1080) in app-private storage with a caption sidecar.
 */
object BingPhotos {
    private const val API = "https://www.bing.com/HPImageArchive.aspx?format=js&n=8&mkt=%s&idx=%d"
    private const val REFRESH_MS = 12 * 3_600_000L
    private const val RETRY_MS = 3_600_000L
    private const val KEEP = 16

    class Photo(val file: File, val caption: String?)

    private fun dir(context: Context) = File(context.filesDir, "bing").apply { mkdirs() }

    /** Cached photos, newest first. */
    fun cached(context: Context): List<Photo> =
        dir(context).listFiles { f -> f.extension == "jpg" }.orEmpty()
            .sortedByDescending { it.name }
            .map { Photo(it, captionFile(it).takeIf(File::exists)?.readText()) }

    /** Downloads new photos if the cache is stale. Blocking; returns true if new photos arrived. */
    fun refreshIfStale(context: Context): Boolean {
        val prefs = context.getSharedPreferences("bing", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong("last_ok", 0) < REFRESH_MS) return false
        if (now - prefs.getLong("last_try", 0) < RETRY_MS) return false
        prefs.edit().putLong("last_try", now).apply()

        val entries = LinkedHashMap<String, Pair<String, String>>() // file name -> (image url, caption)
        try {
            val market = Locale.getDefault().toLanguageTag()
            for (idx in listOf(0, 8)) {
                val images = JSONObject(get(API.format(market, idx))).getJSONArray("images")
                for (i in 0 until images.length()) {
                    val o = images.getJSONObject(i)
                    val base = o.getString("urlbase")
                    val slug = base.substringAfter("OHR.").substringBefore("_").filter(Char::isLetterOrDigit).take(40)
                    entries.putIfAbsent("${o.getString("startdate")}_$slug",
                        "https://www.bing.com${base}_1920x1080.jpg" to o.optString("copyright"))
                }
            }
        } catch (e: Exception) {
            return false // offline or API hiccup; retry in an hour
        }

        val dir = dir(context)
        var added = false
        for ((name, entry) in entries) {
            val file = File(dir, "$name.jpg")
            if (file.exists()) continue
            try {
                download(entry.first, file)
                captionFile(file).writeText(entry.second)
                added = true
            } catch (e: Exception) {
                file.delete()
            }
        }
        cached(context).drop(KEEP).forEach { it.file.delete(); captionFile(it.file).delete() }
        prefs.edit().putLong("last_ok", now).apply()
        return added
    }

    private fun captionFile(image: File) = File(image.parentFile, image.nameWithoutExtension + ".txt")

    private fun open(url: String) = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 10_000
        readTimeout = 20_000
    }

    private fun get(url: String): String = open(url).run {
        try { inputStream.bufferedReader().readText() } finally { disconnect() }
    }

    private fun download(url: String, to: File) {
        val part = File(to.path + ".part")
        open(url).run {
            try {
                if (responseCode != 200) throw IllegalStateException("HTTP $responseCode")
                inputStream.use { input -> part.outputStream().use { input.copyTo(it) } }
            } finally {
                disconnect()
            }
        }
        if (!part.renameTo(to)) throw IllegalStateException("rename failed")
    }
}
