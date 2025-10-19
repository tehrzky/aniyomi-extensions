package eu.kanade.tachiyomi.animeextension.en.kajzu

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
import eu.kanade.tachiyomi.lib.doodextractor.DoodExtractor
import eu.kanade.tachiyomi.lib.filemoonextractor.FilemoonExtractor
import eu.kanade.tachiyomi.lib.mixdropextractor.MixDropExtractor
import eu.kanade.tachiyomi.lib.mp4uploadextractor.Mp4uploadExtractor
import eu.kanade.tachiyomi.lib.streamlareextractor.StreamlareExtractor
import eu.kanade.tachiyomi.lib.streamtapeextractor.StreamTapeExtractor
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

class Kajzu : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "Kajzu"

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
        return GET("$baseUrl/kamen-rider")
    }

    override fun popularAnimeSelector(): String = ".list-popular li a, article.post-item a, .anime-item a"

    override fun popularAnimeFromElement(element: Element): SAnime {
        return SAnime.create().apply {
            setUrlWithoutDomain(element.attr("href"))
            title = element.attr("title").takeIf { it.isNotBlank() }
                ?: element.selectFirst("h2, h3, .title")?.text()
                ?: element.text()
        }
    }

    override fun popularAnimeNextPageSelector(): String? = null

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int): Request {
        return GET(baseUrl)
    }

    override fun latestUpdatesSelector(): String = ".list-episode-item li a, article.post-item a, .anime-item a"

    override fun latestUpdatesFromElement(element: Element): SAnime {
        return SAnime.create().apply {
            setUrlWithoutDomain(element.attr("href"))
            thumbnail_url = element.selectFirst("img")?.attr("src")
                ?: element.selectFirst("img")?.attr("data-src")
            val fullTitle = element.selectFirst("h3.title, h2, .title")?.text() ?: "Unknown Title"
            title = fullTitle.substringBefore("Episode")
                .substringBefore("episode")
                .substringBefore("Ep")
                .trim()
        }
    }

    override fun latestUpdatesNextPageSelector(): String? = null

    // =============================== Search ===============================
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        return GET("$baseUrl/?s=${query.encodeURL()}")
    }

    override fun searchAnimeSelector(): String = ".list-episode-item li a, article.post-item a, .search-result a, .anime-item a"

    override fun searchAnimeFromElement(element: Element): SAnime {
        return latestUpdatesFromElement(element)
    }

    override fun searchAnimeNextPageSelector(): String? = null

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document): SAnime {
        return SAnime.create().apply {
            val fullTitle = document.selectFirst("h1, h2.title, .anime-title")?.text() ?: "Unknown Title"
            title = fullTitle.substringBefore("Episode")
                .substringBefore("episode")
                .trim()

            thumbnail_url = document.selectFirst("img.poster, .poster img, .thumbnail img, img.cover")?.attr("src")
                ?: document.selectFirst("img")?.attr("src")

            val rawDescription = document.selectFirst("meta[name=description]")?.attr("content")
                ?: document.selectFirst(".description, .synopsis, .content")?.text()
                ?: ""
            description = rawDescription.substringAfter(":").trim().ifBlank { rawDescription }

            document.select(".info p, .details p, .meta-info p").forEach { p ->
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
    override fun episodeListSelector(): String = "ul.pagination.post-tape li a, ul.list-episode-item-2.all-episode li a, .episode-list li a"

    override fun episodeFromElement(element: Element): SEpisode {
        return SEpisode.create().apply {
            setUrlWithoutDomain(element.attr("href"))
            val titleText = element.text()

            val episodeNum = Regex("""ep=(\d+)""").find(element.attr("href"))?.groupValues?.get(1)
                ?: Regex("""Episode\s*(\d+)""").find(titleText)?.groupValues?.get(1)
                ?: Regex("""EP?\s*(\d+)""").find(titleText)?.groupValues?.get(1)
                ?: Regex("""\b(\d+)\b""").find(titleText)?.groupValues?.get(1)
                ?: "1"

            name = "Episode $episodeNum"
            episode_number = episodeNum.toFloatOrNull() ?: 1F
            date_upload = element.selectFirst("span.time, .date")?.text().orEmpty().toDate()
        }
    }

    // ============================ Video Links =============================
    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val videos = mutableListOf<Video>()
        val pageUrl = response.request.url.toString()

        // Kajzu uses direct iframe embeds - extract the iframe src
        val iframeSrc = document.selectFirst("iframe#frame")?.attr("src")
            ?: document.selectFirst("div.player iframe")?.attr("src")
            ?: document.selectFirst("iframe[allowfullscreen]")?.attr("src")

        if (!iframeSrc.isNullOrBlank()) {
            var videoUrl = iframeSrc.toAbsoluteUrl()

            // Handle URL shorteners/redirects by following them
            if (videoUrl.contains("short.icu") || videoUrl.contains("bit.ly") || videoUrl.contains("tinyurl")) {
                try {
                    val finalUrl = client.newCall(GET(videoUrl, headers)).execute().request.url.toString()
                    videoUrl = finalUrl
                } catch (e: Exception) {
                    videos.add(Video(videoUrl, "DEBUG: Failed to resolve redirect: ${e.message}", videoUrl))
                }
            }

            // Now handle the actual streaming host
            try {
                when {
                    videoUrl.contains("streamwish", ignoreCase = true) ||
                        videoUrl.contains("strwish", ignoreCase = true) ||
                        videoUrl.contains("wishfast", ignoreCase = true) -> {
                        videos.addAll(
                            StreamWishExtractor(client, headers).videosFromUrl(
                                videoUrl,
                                videoNameGen = { quality -> "StreamWish - $quality" },
                            ),
                        )
                    }

                    videoUrl.contains("vidhide", ignoreCase = true) ||
                        videoUrl.contains("vidhidevip", ignoreCase = true) ||
                        videoUrl.contains("vidspeeds", ignoreCase = true) -> {
                        videos.addAll(
                            VidHideExtractor(client, headers).videosFromUrl(
                                videoUrl,
                                videoNameGen = { quality -> "VidHide - $quality" },
                            ),
                        )
                    }

                    videoUrl.contains("streamtape", ignoreCase = true) ||
                        videoUrl.contains("strtape", ignoreCase = true) -> {
                        videos.addAll(
                            StreamTapeExtractor(client).videosFromUrl(videoUrl, "StreamTape"),
                        )
                    }

                    videoUrl.contains("mixdrop", ignoreCase = true) -> {
                        videos.addAll(
                            MixDropExtractor(client).videosFromUrl(videoUrl, "MixDrop"),
                        )
                    }

                    videoUrl.contains("filemoon", ignoreCase = true) -> {
                        videos.addAll(
                            FilemoonExtractor(client).videosFromUrl(videoUrl, "FileMoon"),
                        )
                    }

                    videoUrl.contains("dood", ignoreCase = true) -> {
                        videos.addAll(
                            DoodExtractor(client).videosFromUrl(videoUrl, "Dood"),
                        )
                    }

                    videoUrl.contains("mp4upload", ignoreCase = true) -> {
                        videos.addAll(
                            Mp4uploadExtractor(client).videosFromUrl(videoUrl, headers, "MP4Upload - "),
                        )
                    }

                    videoUrl.contains("streamlare", ignoreCase = true) -> {
                        videos.addAll(
                            StreamlareExtractor(client).videosFromUrl(videoUrl, "Streamlare - "),
                        )
                    }

                    videoUrl.contains("storage.googleapis.com") -> {
                        // Direct Google Storage link
                        videos.add(Video(videoUrl, "Google Storage - Direct Stream", videoUrl))
                    }

                    else -> {
                        // Unknown host - try direct playback
                        videos.add(Video(videoUrl, "Direct Stream", videoUrl))
                    }
                }
            } catch (e: Exception) {
                videos.add(Video(videoUrl, "Error extracting video: ${e.message}", videoUrl))
            }
        }

        return if (videos.isEmpty()) {
            listOf(
                Video(
                    pageUrl,
                    "ERROR: No video found. Make sure you're on an episode page.",
                    pageUrl,
                ),
            )
        } else {
            videos
        }
    }

    override fun videoListSelector(): String = throw UnsupportedOperationException()

    override fun videoFromElement(element: Element): Video = throw UnsupportedOperationException()

    override fun videoUrlParse(document: Document): String = throw UnsupportedOperationException()

    // ============================== Settings ==============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        EditTextPreference(screen.context).apply {
            key = PREF_DOMAIN_KEY
            title = "Custom Domain"
            summary = "Enter your preferred Kajzu domain (e.g., https://kajzu.com)"
            setDefaultValue(PREF_DOMAIN_DEFAULT)
            dialogTitle = "Domain Settings"
            dialogMessage = "Enter the full URL of your preferred Kajzu site"

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

    private fun String.toAbsoluteUrl(): String {
        return when {
            this.startsWith("http", ignoreCase = true) -> this
            this.startsWith("//") -> "https:$this"
            else -> "$baseUrl/${this.trimStart('/')}"
        }
    }

    private fun String.toDate(): Long {
        return runCatching {
            DATE_FORMATTER.parse(trim())?.time
        }.getOrNull() ?: 0L
    }

    private fun String.encodeURL(): String = java.net.URLEncoder.encode(this, "UTF-8")

    companion object {
        private val DATE_FORMATTER by lazy {
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ENGLISH)
        }

        private const val PREF_DOMAIN_KEY = "preferred_domain"
        private const val PREF_DOMAIN_DEFAULT = "https://kajzu.com"
    }
}
