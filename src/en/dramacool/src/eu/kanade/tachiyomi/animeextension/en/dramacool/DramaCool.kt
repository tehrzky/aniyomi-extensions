package eu.kanade.tachiyomi.animeextension.en.dramacool

import android.app.Application
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import eu.kan.ade.tachiyomi.lib.streamtapeextractor.StreamTapeExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.lib.doodextractor.DoodExtractor
import eu.kanade.tachiyomi.lib.filemoonextractor.FilemoonExtractor
import eu.kanade.tachiyomi.lib.mixdropextractor.MixDropExtractor
import eu.kanade.tachiyomi.lib.mp4uploadextractor.Mp4uploadExtractor
import eu.kanade.tachiyomi.lib.streamlareextractor.StreamlareExtractor
import eu.kanade.tachiyomi.lib.streamwishextractor.StreamWishExtractor
import eu.kanade.tachiyomi.lib.vidhideextractor.VidHideExtractor
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

        // Extract all server links from the page
        val serverLinks = mutableMapOf<String, String>()

        // Method 1: Extract from .muti_link li elements
        document.select(".muti_link li, ul.muti_link li").forEach { server ->
            val serverName = server.ownText().trim().takeIf { it.isNotBlank() }
                ?: server.text().trim()
            val videoUrl = server.attr("data-video")

            if (videoUrl.isNotBlank() && serverName.isNotBlank()) {
                serverLinks[serverName] = videoUrl
            }
        }

        // Method 2: Try alternative server list selectors
        if (serverLinks.isEmpty()) {
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
                    serverLinks[serverName] = videoUrl
                }
            }
        }

        // Method 3: Extract from iframe if no servers found (This is the main player link)
        if (serverLinks.isEmpty()) {
            document.select("iframe[src], iframe[data-src]").forEach { iframe ->
                val iframeSrc = iframe.attr("src").ifBlank { iframe.attr("data-src") }
                if (iframeSrc.isNotBlank()) {
                    serverLinks["Standard Server (Main Player)"] = iframeSrc
                }
            }
        }

        // Now process each server link and extract videos using appropriate extractors
        serverLinks.forEach { (serverName, initialUrl) ->
            // Use the utility function to ensure the URL is absolute
            var currentUrl = initialUrl.toAbsoluteUrl()

            // --- NEW LOGIC: Handle Internal Embed Pages (Method 3 links) ---
            // If the URL is an internal DramaCool embed link, we need to follow it to find the *real* external video host.
            if (currentUrl.startsWith(baseUrl, ignoreCase = true) && currentUrl.contains("/embed/", ignoreCase = true)) {
                try {
                    val embedDocument = client.newCall(GET(currentUrl, headers)).execute().asJsoup()
                    val realEmbedUrl = embedDocument.selectFirst("iframe[src], iframe[data-src]")?.attr("src")?.ifBlank { embedDocument.selectFirst("iframe[data-src]")?.attr("data-src") }

                    if (realEmbedUrl.isNullOrBlank()) {
                        // If we can't find the inner iframe, it's still unhandled
                        videos.add(Video(currentUrl, "$serverName (Internal Embed Page - FAILED TO FIND EXTERNAL IFRAME)", currentUrl))
                        return@forEach
                    }

                    // Update the currentUrl to the real external host link found inside the internal embed page, 
                    // ensuring it is also an absolute URL.
                    currentUrl = realEmbedUrl.toAbsoluteUrl()
                } catch (e: Exception) {
                    videos.add(Video(currentUrl, "$serverName (Internal Embed Failed: ${e.message})", currentUrl))
                    return@forEach
                }
            }
            // --- END NEW LOGIC ---

            try {
                when {
                    // StreamWish and its mirrors
                    currentUrl.contains("streamwish", ignoreCase = true) ||
                        currentUrl.contains("strwish", ignoreCase = true) ||
                        currentUrl.contains("wishfast", ignoreCase = true) ||
                        currentUrl.contains("awish", ignoreCase = true) ||
                        currentUrl.contains("streamplay", ignoreCase = true) -> {
                        videos.addAll(
                            StreamWishExtractor(client, headers).videosFromUrl(
                                currentUrl,
                                videoNameGen = { quality -> "$serverName - $quality" },
                            ),
                        )
                    }

                    // VidHide and its mirrors (VIDBASIC ADDED HERE)
                    currentUrl.contains("vidhide", ignoreCase = true) ||
                        currentUrl.contains("vidhidevip", ignoreCase = true) ||
                        currentUrl.contains("vidspeeds", ignoreCase = true) ||
                        currentUrl.contains("mycloud", ignoreCase = true) ||
                        currentUrl.contains("vcloud", ignoreCase = true) ||
                        currentUrl.contains("vidbasic", ignoreCase = true) -> {
                        videos.addAll(
                            VidHideExtractor(client, headers).videosFromUrl(
                                currentUrl,
                                videoNameGen = { quality -> "$serverName - $quality" },
                            ),
                        )
                    }

                    // StreamTape
                    currentUrl.contains("streamtape", ignoreCase = true) ||
                        currentUrl.contains("strtape", ignoreCase = true) ||
                        currentUrl.contains("stape", ignoreCase = true) -> {
                        videos.addAll(
                            StreamTapeExtractor(client).videosFromUrl(currentUrl, serverName),
                        )
                    }

                    // MixDrop and its mirrors
                    currentUrl.contains("mixdrop", ignoreCase = true) ||
                        currentUrl.contains("mixdrp", ignoreCase = true) ||
                        currentUrl.contains("mdrama", ignoreCase = true) ||
                        currentUrl.contains("mdstrm", ignoreCase = true) -> {
                        videos.addAll(
                            MixDropExtractor(client).videosFromUrl(currentUrl, serverName),
                        )
                    }

                    // Filemoon and its mirrors (VIDBASIC REMOVED)
                    currentUrl.contains("filemoon", ignoreCase = true) ||
                        currentUrl.contains("moonplayer", ignoreCase = true) -> {
                        videos.addAll(
                            FilemoonExtractor(client).videosFromUrl(currentUrl, serverName),
                        )
                    }

                    // DoodStream and its mirrors
                    currentUrl.contains("dood", ignoreCase = true) ||
                        currentUrl.contains("doodstream", ignoreCase = true) ||
                        currentUrl.contains("ds2play", ignoreCase = true) ||
                        currentUrl.contains("ds2video", ignoreCase = true) -> {
                        videos.addAll(
                            DoodExtractor(client).videosFromUrl(currentUrl, serverName),
                        )
                    }

                    // Mp4Upload
                    currentUrl.contains("mp4upload", ignoreCase = true) -> {
                        videos.addAll(
                            Mp4uploadExtractor(client).videosFromUrl(currentUrl, headers, "$serverName - "),
                        )
                    }

                    // Streamlare
                    currentUrl.contains("streamlare", ignoreCase = true) ||
                        currentUrl.contains("slwatch", ignoreCase = true) -> {
                        videos.addAll(
                            StreamlareExtractor(client).videosFromUrl(currentUrl, "$serverName - "),
                        )
                    }
                    // For unknown servers, try to follow redirects
                    else -> {
                        val finalVideo = try {
                            val finalUrl = client.newCall(GET(currentUrl, headers)).execute().request.url.toString()
                            if (finalUrl.contains(".mp4", ignoreCase = true) || finalUrl.contains(".m3u8", ignoreCase = true) || finalUrl.contains(".mkv", ignoreCase = true)) {
                                Video(finalUrl, "$serverName - Direct Stream", finalUrl)
                            } else {
                                // Changed message to show the unhandled URL for user reporting
                                val shortUrl = currentUrl.substringBefore("?")
                                Video(currentUrl, "$serverName (Unhandled Host: $shortUrl)", currentUrl)
                            }
                        } catch (e: Exception) {
                            Video(currentUrl, "$serverName (Connection Error)", currentUrl)
                        }
                        videos.add(finalVideo)
                    }
                }
            } catch (e: Exception) {
                // If extraction fails for a known server, include the exception message for better debugging
                videos.add(Video(currentUrl, "$serverName (Extraction Failed: ${e.message})", currentUrl))
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

    /**
     * Converts a potentially relative URL into an absolute URL using the base URL.
     */
    private fun String.toAbsoluteUrl(): String {
        return when {
            this.startsWith("http", ignoreCase = true) -> this
            this.startsWith("//") -> "https:$this"
            // If it's a relative path (e.g., "/embed/abc" or "embed/abc"), append it to baseUrl
            else -> "$baseUrl/${this.trimStart('/')}"
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
