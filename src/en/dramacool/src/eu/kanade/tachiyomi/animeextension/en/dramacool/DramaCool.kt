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

    override fun latestUpdatesSelector(): String = ".list-episode-item li a"

    override fun latestUpdatesFromElement(element: Element): SAnime {
        return SAnime.create().apply {
            setUrlWithoutDomain(element.attr("href"))
            thumbnail_url = element.selectFirst("img")?.attr("data-original")
            val fullTitle = element.selectFirst("h3.title")?.text() ?: "Unknown Title"
            title = fullTitle.substringBefore("Episode").trim()
        }
    }

    override fun latestUpdatesNextPageSelector(): String? = null

    // =============================== Search ===============================
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        return GET("$baseUrl/search?keyword=${query.encodeURL()}")
    }

    override fun searchAnimeSelector(): String = ".list-episode-item li a"

    override fun searchAnimeFromElement(element: Element): SAnime {
        return latestUpdatesFromElement(element)
    }

    override fun searchAnimeNextPageSelector(): String? = null

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document): SAnime {
        return SAnime.create().apply {
            val fullTitle = document.selectFirst("h1, h2.title")?.text() ?: "Unknown Title"
            title = fullTitle.substringBefore("Episode").substringBefore("episode").trim()
            thumbnail_url = document.selectFirst("img.poster, .poster img, .thumbnail img")?.attr("src")

            // Clean up description - remove the "Asianc - Dramacool:" prefix
            val rawDescription = document.selectFirst("meta[name=description]")?.attr("content") ?: ""
            description = rawDescription.substringAfter(":").trim()

            // Get details from info section
            document.select(".info p, .details p").forEach { p ->
                val text = p.text()
                when {
                    text.contains("Genre:", ignoreCase = true) -> genre = p.select("a").joinToString { it.text() }
                    text.contains("Status:", ignoreCase = true) -> status = parseStatus(p.select("a, span").last()?.text())
                    text.contains("Network:", ignoreCase = true) -> author = p.select("a").text()
                }
            }
        }
    }

    // ============================== Episodes ==============================
    override fun episodeListSelector(): String = "ul.list-episode-item-2.all-episode li a"

    override fun episodeFromElement(element: Element): SEpisode {
        return SEpisode.create().apply {
            setUrlWithoutDomain(element.attr("href"))
            val titleElement = element.selectFirst("h3.title")
            val titleText = titleElement?.text() ?: ""

            // Extract episode number from title
            val episodeNum = Regex("""Episode\s*(\d+)""").find(titleText)?.groupValues?.get(1)
                ?: Regex("""EP?\s*(\d+)""").find(titleText)?.groupValues?.get(1)
                ?: Regex("""\b(\d+)\b""").find(titleText)?.groupValues?.get(1)
                ?: "1"

            val type = element.selectFirst("span.type")?.text() ?: "SUB"
            name = "$type: Episode $episodeNum"
            episode_number = episodeNum.toFloatOrNull() ?: 1F
            date_upload = element.selectFirst("span.time")?.text().orEmpty().toDate()
        }
    }

    // ============================ Video Links =============================
    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val videos = mutableListOf<Video>()

        // Method 1: Extract from .muti_link li elements
        document.select(".muti_link li, ul.muti_link li").forEach { server ->
            val serverName = server.ownText().trim().takeIf { it.isNotBlank() }
                ?: server.text().trim()
            val videoUrl = server.attr("data-video")

            if (videoUrl.isNotBlank() && serverName.isNotBlank()) {
                val fullUrl = when {
                    videoUrl.startsWith("http") -> videoUrl
                    videoUrl.startsWith("//") -> "https:$videoUrl"
                    videoUrl.startsWith("/") -> "$baseUrl$videoUrl"
                    else -> videoUrl
                }
                videos.add(Video(fullUrl, serverName, fullUrl))
            }
        }

        // Method 2: Try alternative server list selectors
        if (videos.isEmpty()) {
            document.select(".server-list li, ul.list-server-items li, .anime_muti_link li").forEach { server ->
                val serverName = server.selectFirst("a")?.text()?.trim()
                    ?: server.ownText().trim()
                    ?: "Server"

                var videoUrl = server.attr("data-video")
                if (videoUrl.isBlank()) {
                    videoUrl = server.attr("data-link")
                }
                if (videoUrl.isBlank()) {
                    videoUrl = server.selectFirst("a")?.attr("data-video") ?: ""
                }
                if (videoUrl.isBlank()) {
                    videoUrl = server.selectFirst("a")?.attr("data-link") ?: ""
                }

                if (videoUrl.isNotBlank()) {
                    val fullUrl = when {
                        videoUrl.startsWith("http") -> videoUrl
                        videoUrl.startsWith("//") -> "https:$videoUrl"
                        videoUrl.startsWith("/") -> "$baseUrl$videoUrl"
                        else -> videoUrl
                    }
                    videos.add(Video(fullUrl, serverName, fullUrl))
                }
            }
        }

        // Method 3: Extract from iframe
        if (videos.isEmpty()) {
            document.select("iframe[src], iframe[data-src]").forEach { iframe ->
                val iframeSrc = iframe.attr("src").ifBlank { iframe.attr("data-src") }
                if (iframeSrc.isNotBlank()) {
                    val fullUrl = when {
                        iframeSrc.startsWith("http") -> iframeSrc
                        iframeSrc.startsWith("//") -> "https:$iframeSrc"
                        iframeSrc.startsWith("/") -> "$baseUrl$iframeSrc"
                        else -> iframeSrc
                    }
                    videos.add(Video(fullUrl, "Standard Server", fullUrl))
                }
            }
        }

        // Method 4: Look in scripts for embed URLs
        if (videos.isEmpty()) {
            document.select("script:not([src])").forEach { script ->
                val scriptContent = script.html()

                // Look for server data in JavaScript
                Regex("""data-video\s*[=:]\s*["']([^"']+)["']""").findAll(scriptContent).forEach { match ->
                    val url = match.groupValues[1]
                    if (url.isNotBlank()) {
                        val fullUrl = when {
                            url.startsWith("http") -> url
                            url.startsWith("//") -> "https:$url"
                            url.startsWith("/") -> "$baseUrl$url"
                            else -> url
                        }
                        videos.add(Video(fullUrl, "Embed Server", fullUrl))
                    }
                }

                // Look for common video hosting patterns
                listOf(
                    Regex("""(https?://[^"'\s]*streamwish[^"'\s]*)"""),
                    Regex("""(https?://[^"'\s]*vidhide[^"'\s]*)"""),
                    Regex("""(https?://[^"'\s]*streamtape[^"'\s]*)"""),
                    Regex("""(https?://[^"'\s]*mixdrop[^"'\s]*)"""),
                    Regex("""(https?://[^"'\s]*\.m3u8[^"'\s]*)"""),
                    Regex("""(https?://[^"'\s]*\.mp4[^"'\s]*)"""),
                ).forEach { regex ->
                    regex.findAll(scriptContent).forEach { match ->
                        val url = match.groupValues[1].trim('"', '\'', ' ')
                        if (url.isNotBlank()) {
                            val serverName = when {
                                url.contains("streamwish") -> "StreamWish"
                                url.contains("vidhide") -> "VidHide"
                                url.contains("streamtape") -> "StreamTape"
                                url.contains("mixdrop") -> "MixDrop"
                                url.contains(".m3u8") -> "HLS"
                                url.contains(".mp4") -> "MP4"
                                else -> "Server"
                            }
                            videos.add(Video(url, serverName, url))
                        }
                    }
                }
            }
        }

        return videos.distinctBy { it.url }
    }

    override fun videoListSelector(): String = throw UnsupportedOperationException()

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
        return sortedWith(
            compareByDescending { video ->
                when {
                    video.quality.contains("1080") -> 5
                    video.quality.contains("720") -> 4
                    video.quality.contains("480") -> 3
                    video.quality.contains("360") -> 2
                    video.quality.contains("Standard") -> 1
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
