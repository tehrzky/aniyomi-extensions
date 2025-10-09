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
        return GET("$baseUrl/most-popular-drama?page=$page")
    }

    override fun popularAnimeSelector(): String = "ul.list-popular li a, .list-popular li a"

    override fun popularAnimeFromElement(element: Element): SAnime {
        return SAnime.create().apply {
            setUrlWithoutDomain(element.attr("href"))
            title = element.text()
        }
    }

    override fun popularAnimeNextPageSelector(): String? = "a.next, .pagination a:contains(Next)"

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int): Request {
        return GET("$baseUrl/recently-added?page=$page")
    }

    override fun latestUpdatesSelector(): String = "ul.switch-block.list-episode-item li a"

    override fun latestUpdatesFromElement(element: Element): SAnime {
        return SAnime.create().apply {
            setUrlWithoutDomain(element.attr("href"))
            thumbnail_url = element.selectFirst("img")?.attr("data-original")
            title = element.selectFirst("h3.title")?.text() ?: "Unknown Title"
        }
    }

    override fun latestUpdatesNextPageSelector(): String? = "a.next, .pagination a:contains(Next)"

    // =============================== Search ===============================
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        return GET("$baseUrl/search?keyword=${query.encodeURL()}&page=$page")
    }

    override fun searchAnimeSelector(): String = "ul.switch-block.list-episode-item li a"

    override fun searchAnimeFromElement(element: Element): SAnime {
        return latestUpdatesFromElement(element)
    }

    override fun searchAnimeNextPageSelector(): String? = latestUpdatesNextPageSelector()

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
    override fun episodeListSelector(): String = "ul.all-episode li a, .episode-list li a"

    override fun episodeFromElement(element: Element): SEpisode {
        return SEpisode.create().apply {
            setUrlWithoutDomain(element.attr("href"))
            
            val epText = element.selectFirst("h3, .title")?.text() ?: ""
            val epNum = element.selectFirst("span.ep")?.text()?.substringAfter("EP ") ?: "1"
            
            val type = element.selectFirst("span.type")?.text() ?: "RAW"
            name = "$type: Episode $epNum"
            episode_number = epNum.toFloatOrNull() ?: 1F
            date_upload = element.selectFirst("span.time")?.text().orEmpty().toDate()
        }
    }

    // ============================ Video Links =============================
    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        
        // Try multiple iframe selectors
        val iframeUrl = document.selectFirst("iframe, .video-frame, #player iframe")?.absUrl("src")
            ?: return emptyList()

        // For now, return a simple video - you'll need to enhance this
        return listOf(Video(iframeUrl, "Direct Link", iframeUrl))
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
        return this // Simple sorting for now
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
