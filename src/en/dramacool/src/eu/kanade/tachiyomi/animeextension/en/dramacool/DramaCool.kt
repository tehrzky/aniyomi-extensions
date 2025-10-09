package eu.kanade.tachiyomi.animeextension.en.dramacool

import android.app.Application
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.text.SimpleDateFormat
import java.util.Locale

class DramaCool : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "DramaCool"

    override val baseUrl by lazy {
        preferences.getString(PREF_DOMAIN_KEY, PREF_DOMAIN_DEFAULT)!!.trimEnd('/')
    }

    override val lang = "en"

    override val supportsLatest = true

    private val preferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int): Request {
        return GET("$baseUrl/most-popular-drama")
    }

    override fun popularAnimeSelector(): String = ".list-popular li a"

    override fun popularAnimeFromElement(element: Element): SAnime {
        return SAnime.create().apply {
            setUrlWithoutDomain(element.attr("href"))
            title = element.attr("title").takeIf { it.isNotBlank() } ?: element.text()
        }
    }

    override fun popularAnimeNextPageSelector(): String? = null

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int): Request {
        return GET("$baseUrl/recently-added")
    }

    override fun latestUpdatesSelector(): String = ".switch-block.list-episode-item li a.img"

    override fun latestUpdatesFromElement(element: Element): SAnime {
        return SAnime.create().apply {
            setUrlWithoutDomain(element.attr("href"))
            thumbnail_url = element.selectFirst("img")?.attr("data-original")
            title = element.selectFirst("h3.title")?.text() ?: "Unknown Title"
        }
    }

    override fun latestUpdatesNextPageSelector(): String? = null

    // =============================== Search ===============================
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        return GET("$baseUrl/search?keyword=${query.encodeURL()}")
    }

    override fun searchAnimeSelector(): String = ".switch-block.list-episode-item li a.img"

    override fun searchAnimeFromElement(element: Element): SAnime {
        return latestUpdatesFromElement(element)
    }

    override fun searchAnimeNextPageSelector(): String? = null

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document): SAnime {
        return SAnime.create().apply {
            // Get title from the page
            title = document.selectFirst("h1, h2.title")?.text() ?: "Unknown Title"

            // Get thumbnail
            thumbnail_url = document.selectFirst("img.poster, .poster img, .thumbnail img")?.attr("src")

            // Get description from meta tag
            description = document.selectFirst("meta[name=description]")?.attr("content")

            // Try to get genre and other details
            val infoElements = document.select("div.info p, .details p")
            infoElements.forEach { p ->
                val text = p.text()
                when {
                    text.contains("Genre:") -> genre = p.select("a").joinToString { it.text() }
                    text.contains("Status:") -> status = parseStatus(p.select("a").text())
                    text.contains("Network:") -> author = p.select("a").text()
                }
            }
        }
    }

    // ============================== Episodes ==============================
    override fun episodeListParse(document: Document): List<SEpisode> {
        return document.select(episodeListSelector()).mapIndexed { index, element ->
            episodeFromElement(element, index)
        }
    }

    override fun episodeListSelector(): String = "ul.all-episode li a, .episode-list li a"

    private fun episodeFromElement(element: Element, index: Int): SEpisode {
        return SEpisode.create().apply {
            setUrlWithoutDomain(element.attr("href"))

            // Extract episode number from the element
            val epSpan = element.selectFirst("span.ep")
            val epText = epSpan?.text() ?: ""

            // Try to get episode number from span text
            val epNum = when {
                epText.contains(Regex("""EP\s*(\d+)""")) -> {
                    Regex("""EP\s*(\d+)""").find(epText)?.groupValues?.get(1)
                }
                epText.contains(Regex("""\d+""")) -> {
                    Regex("""\d+""").find(epText)?.value
                }
                else -> null
            }

            val type = element.selectFirst("span.type")?.text() ?: "RAW"

            // Use extracted episode number or fallback to index + 1
            val finalEpNum = epNum ?: (index + 1).toString()

            name = "$type: Episode $finalEpNum"
            episode_number = finalEpNum.toFloatOrNull() ?: (index + 1).toFloat()
            date_upload = element.selectFirst("span.time")?.text().orEmpty().toDate()
        }
    }

    override fun episodeFromElement(element: Element): SEpisode {
        return episodeFromElement(element, 0)
    }

    // ============================ Video Links =============================
    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()

        // Get all server options
        val servers = document.select("ul.list-server-items li, .server-list li, .server-item")

        if (servers.isNotEmpty()) {
            // Extract video URLs from server options
            return servers.flatMap { server ->
                val serverName = server.text().trim()
                val videoUrl = server.attr("data-video")

                if (videoUrl.isNotBlank()) {
                    // Create video with server name as quality indicator
                    listOf(Video(videoUrl, "$serverName - Direct", videoUrl))
                } else {
                    emptyList()
                }
            }
        }

        // Fallback: try iframe
        val iframeUrl = document.selectFirst("iframe, .video-frame, #player iframe")?.absUrl("src")
        if (iframeUrl != null) {
            return listOf(Video(iframeUrl, "Direct Link", iframeUrl))
        }

        return emptyList()
    }

    override fun videoListSelector(): String = "ul.list-server-items li, .server-list li, .server-item"

    override fun videoFromElement(element: Element): Video {
        throw UnsupportedOperationException()
    }

    override fun videoUrlParse(document: Document): String {
        throw UnsupportedOperationException()
    }

    // ============================== Settings ==============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        EditTextPreference(screen.context).apply {
            key = PREF_DOMAIN_KEY
            title = "Custom Domain"
            summary = "Enter your preferred DramaCool domain (e.g., https://asianctv.net)"
            setDefaultValue(PREF_DOMAIN_DEFAULT)
            dialogTitle = "Domain Settings"
            dialogMessage = "Enter the full URL of your preferred DramaCool clone site"

            setOnPreferenceChangeListener { _, newValue ->
                val newDomain = (newValue as String).trim()
                if (newDomain.isBlank() || !newDomain.startsWith("http")) {
                    Toast.makeText(screen.context, "Please enter a valid URL starting with http/https", Toast.LENGTH_LONG).show()
                    false
                } else {
                    preferences.edit().putString(key, newDomain.trimEnd('/')).commit()
                    true
                }
            }
        }.also(screen::addPreference)
    }

    // ============================= Utilities ==============================
    override fun List<Video>.sort(): List<Video> {
        // Sort by quality preference
        return sortedWith(
            compareByDescending { video ->
                when {
                    video.quality.contains("1080") -> 4
                    video.quality.contains("720") -> 3
                    video.quality.contains("480") -> 2
                    video.quality.contains("360") -> 1
                    else -> 0
                }
            },
        )
    }

    private fun parseStatus(statusString: String?): Int {
        val status = statusString?.lowercase() ?: return SAnime.UNKNOWN
        return when {
            status.contains("ongoing") -> SAnime.ONGOING
            status.contains("completed") -> SAnime.COMPLETED
            else -> SAnime.UNKNOWN
        }
    }

    private fun String.toDate(): Long {
        return runCatching {
            DATE_FORMATTER.parse(trim())?.time
        }.getOrNull() ?: 0L
    }

    private fun String.encodeURL(): String =
        java.net.URLEncoder.encode(this, "UTF-8")

    companion object {
        private val DATE_FORMATTER by lazy {
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ENGLISH)
        }

        private const val PREF_DOMAIN_KEY = "preferred_domain"
        private const val PREF_DOMAIN_DEFAULT = "https://asianctv.net"
    }
}
