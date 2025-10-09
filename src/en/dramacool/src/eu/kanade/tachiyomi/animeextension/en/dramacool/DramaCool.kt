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
        return GET("$baseUrl/drama-list?page=$page")
    }

    override fun popularAnimeSelector(): String = "div.item, div.movie-item, a.video-block"

    override fun popularAnimeFromElement(element: Element): SAnime {
        return SAnime.create().apply {
            val link = element.selectFirst("a") ?: element
            setUrlWithoutDomain(link.attr("href"))
            
            thumbnail_url = element.selectFirst("img")?.let { img ->
                img.attr("data-original").takeIf { it.isNotBlank() } 
                    ?: img.attr("src").takeIf { it.isNotBlank() }
                    ?: img.attr("data-src").takeIf { it.isNotBlank() }
            }?.replace(" ", "%20")
            
            title = element.selectFirst("h3, h2, .title, .name")?.text() 
                ?: element.selectFirst("img")?.attr("alt")
                ?: "Unknown Title"
        }
    }

    override fun popularAnimeNextPageSelector(): String? = "a.next, li.next a, .pagination a:contains(Next)"

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int): Request {
        return GET("$baseUrl/recently-added?page=$page")
    }

    override fun latestUpdatesSelector(): String = popularAnimeSelector()

    override fun latestUpdatesFromElement(element: Element): SAnime {
        return popularAnimeFromElement(element)
    }

    override fun latestUpdatesNextPageSelector(): String? = popularAnimeNextPageSelector()

    // =============================== Search ===============================
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        return GET("$baseUrl/search?keyword=${query.encodeURL()}&page=$page")
    }

    override fun searchAnimeSelector(): String = popularAnimeSelector()

    override fun searchAnimeFromElement(element: Element): SAnime {
        return popularAnimeFromElement(element)
    }

    override fun searchAnimeNextPageSelector(): String? = popularAnimeNextPageSelector()

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document): SAnime {
        return SAnime.create().apply {
            // Try multiple selectors for title and thumbnail
            document.selectFirst("div.img img, img.poster, .poster img")?.run {
                thumbnail_url = absUrl("src").takeIf { it.isNotBlank() } 
                    ?: absUrl("data-src").takeIf { it.isNotBlank() }
                    ?: attr("data-original").takeIf { it.isNotBlank() }
                title = attr("alt").takeIf { it.isNotBlank() }
            }
            
            // Alternative title selectors
            if (title.isNullOrBlank()) {
                title = document.selectFirst("h1, h2.title, .detail h2")?.text() 
                    ?: "Unknown Title"
            }

            // Try multiple info sections
            val infoElement = document.selectFirst("div.info, .details, .movie-detail")
            infoElement?.let { info ->
                description = info.select("p:contains(Description), p:contains(Plot), .desc")
                    .firstOrNull()?.text()?.trim()
                
                author = info.select("p:contains(Network), p:contains(Studio)")
                    .firstOrNull()?.text()?.substringAfter(":")?.trim()
                
                genre = info.select("p:contains(Genre), .genre a, .tags a")
                    .joinToString { it.text().trim() }
                    .takeIf { it.isNotBlank() }
                
                status = parseStatus(info.select("p:contains(Status)").text())
            }
            
            // Fallback description
            if (description.isNullOrBlank()) {
                description = document.selectFirst("meta[name=description]")?.attr("content")
            }
        }
    }

    // ============================== Episodes ==============================
    override fun episodeListParse(document: Document): List<SEpisode> {
        return document.select(episodeListSelector()).mapNotNull { element ->
            try {
                episodeFromElement(element)
            } catch (e: Exception) {
                null
            }
        }
    }

    override fun episodeListSelector(): String = "ul.episodes li, ul.all-episode li, .episode-list li, .episode-item"

    override fun episodeFromElement(element: Element): SEpisode {
        return SEpisode.create().apply {
            val link = element.selectFirst("a") ?: element
            setUrlWithoutDomain(link.attr("href"))
            
            val titleElement = element.selectFirst("h3, .title, .episode-title, span.name")
            val epText = titleElement?.text() ?: ""
            
            // Extract episode number
            val epNum = when {
                epText.contains(Regex("""Episode\s+(\d+)""", RegexOption.IGNORE_CASE)) -> 
                    Regex("""Episode\s+(\d+)""", RegexOption.IGNORE_CASE).find(epText)?.groupValues?.get(1)
                epText.contains(Regex("""EP\s*(\d+)""", RegexOption.IGNORE_CASE)) -> 
                    Regex("""EP\s*(\d+)""", RegexOption.IGNORE_CASE).find(epText)?.groupValues?.get(1)
                else -> null
            }
            
            val type = element.selectFirst("span.type, .quality")?.text() ?: "RAW"
            name = if (epNum != null) "$type: Episode $epNum" else type
            episode_number = epNum?.toFloatOrNull() ?: 1F
            date_upload = element.selectFirst("span.time, .date")?.text().orEmpty().toDate()
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
