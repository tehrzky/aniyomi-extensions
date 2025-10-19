package eu.kanade.tachiyomi.animeextension.en.kajzu

import android.app.Application
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
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class Kajzu : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "Kajzu"
    override val baseUrl = "https://kajzu.com"
    override val lang = "en"
    override val supportsLatest = true

    private val preferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int): Request {
        // Use Kamen Rider section for popular
        return GET("$baseUrl/kamen-rider")
    }

    override fun popularAnimeSelector(): String = "div.item.post"

    override fun popularAnimeFromElement(element: Element): SAnime {
        return SAnime.create().apply {
            val link = element.selectFirst("a") ?: return@apply
            setUrlWithoutDomain(link.attr("href"))

            // Get title from h3 or from the link title attribute
            title = element.selectFirst("h3")?.text()?.trim()
                ?: link.attr("title").takeIf { it.isNotBlank() }
                ?: link.text().trim()
                ?: "Unknown Title"

            thumbnail_url = element.selectFirst("img")?.let { img ->
                img.attr("src").takeIf { src -> src.isNotBlank() }
                    ?: img.attr("data-src")
            }
        }
    }

    override fun popularAnimeNextPageSelector(): String? = null

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int): Request {
        return GET(baseUrl)
    }

    override fun latestUpdatesSelector(): String = "div.item.post"

    override fun latestUpdatesFromElement(element: Element): SAnime {
        return popularAnimeFromElement(element)
    }

    override fun latestUpdatesNextPageSelector(): String? = null

    // =============================== Search ===============================
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        return GET("$baseUrl/?s=${query.encodeURL()}")
    }

    override fun searchAnimeSelector(): String = "div.item.post"

    override fun searchAnimeFromElement(element: Element): SAnime {
        return popularAnimeFromElement(element)
    }

    override fun searchAnimeNextPageSelector(): String? = null

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document): SAnime {
        return SAnime.create().apply {
            title = document.selectFirst("h1")?.text() ?: "Unknown"

            // Try multiple image selectors
            thumbnail_url = document.selectFirst("img[fifulocal-featured]")?.attr("src")
                ?: document.selectFirst("img.wp-post-image")?.attr("src")
                ?: document.selectFirst("div.item-img img")?.attr("src")
                ?: document.selectFirst("img")?.attr("src")

            val descMeta = document.selectFirst("meta[name=description]")?.attr("content")
            if (!descMeta.isNullOrBlank()) {
                description = descMeta
            }

            // Try to get genre and status from various locations
            document.select("table.table tbody tr").forEach { row ->
                val th = row.selectFirst("th")?.text() ?: return@forEach
                val td = row.selectFirst("td")?.text() ?: return@forEach

                when {
                    th.contains("Category", ignoreCase = true) -> handleCategory(row)
                    th.contains("Genre", ignoreCase = true) -> handleCategory(row)
                    th.contains("Status", ignoreCase = true) -> {
                        status = parseStatus(td)
                    }
                }
            }
        }
    }

    private fun SAnime.handleCategory(row: Element) {
        val genreLinks = row.select("a")
        if (genreLinks.isNotEmpty()) {
            genre = genreLinks.joinToString(", ") { it.text() }
        }
    }

    // ============================== Episodes ==============================
    override fun episodeListSelector(): String = "ul.pagination.post-tape li a, ul.list-episode li a, div.episode-list a"

    override fun episodeFromElement(element: Element): SEpisode {
        return SEpisode.create().apply {
            val href = element.attr("href")
            setUrlWithoutDomain(href)

            // Extract episode number from various patterns
            val episodeNum = Regex("""ep=(\d+)""").find(href)?.groupValues?.get(1)
                ?: Regex("""episode[_-]?(\d+)""", RegexOption.IGNORE_CASE).find(href)?.groupValues?.get(1)
                ?: Regex("""/(\d+)/?$""").find(href)?.groupValues?.get(1)
                ?: element.text().let { text ->
                    Regex("""Episode\s*(\d+)""", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)
                        ?: Regex("""(\d+)""").find(text)?.groupValues?.get(1)
                }
                ?: "1"

            name = "Episode $episodeNum"
            episode_number = episodeNum.toFloatOrNull() ?: 1F
        }
    }

    // ============================ Video Links =============================
    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val videos = mutableListOf<Video>()

        // Look for iframe with video - try multiple selectors
        val iframeSrc = document.selectFirst("iframe#frame")?.attr("src")
            ?: document.selectFirst("div.player iframe")?.attr("src")
            ?: document.selectFirst("iframe[src*='stream']")?.attr("src")
            ?: document.selectFirst("iframe[allowfullscreen]")?.attr("src")
            ?: document.selectFirst("iframe")?.attr("src")

        if (iframeSrc.isNullOrBlank()) {
            return listOf(
                Video(
                    "",
                    "ERROR: No video player found on this page",
                    "",
                ),
            )
        }

        var videoUrl = iframeSrc.toAbsoluteUrl()

        try {
            when {
                videoUrl.contains("streamtape", ignoreCase = true) -> {
                    videos.addAll(StreamTapeExtractor(client).videosFromUrl(videoUrl, "StreamTape"))
                }
                videoUrl.contains("mixdrop", ignoreCase = true) -> {
                    videos.addAll(MixDropExtractor(client).videosFromUrl(videoUrl, "MixDrop"))
                }
                videoUrl.contains("filemoon", ignoreCase = true) -> {
                    videos.addAll(FilemoonExtractor(client).videosFromUrl(videoUrl, "FileMoon"))
                }
                videoUrl.contains("dood", ignoreCase = true) -> {
                    videos.addAll(DoodExtractor(client).videosFromUrl(videoUrl, "Dood"))
                }
                videoUrl.contains("mp4upload", ignoreCase = true) -> {
                    videos.addAll(Mp4uploadExtractor(client).videosFromUrl(videoUrl, headers, "MP4Upload - "))
                }
                videoUrl.contains("streamlare", ignoreCase = true) -> {
                    videos.addAll(StreamlareExtractor(client).videosFromUrl(videoUrl, "Streamlare - "))
                }
                else -> {
                    videos.add(Video(videoUrl, "Direct Stream", videoUrl))
                }
            }
        } catch (e: Exception) {
            return listOf(Video(videoUrl, "Error: ${e.message}", videoUrl))
        }

        return if (videos.isEmpty()) {
            listOf(Video(iframeSrc, "No streams found", iframeSrc))
        } else {
            videos
        }
    }

    override fun videoListSelector(): String = throw UnsupportedOperationException()
    override fun videoFromElement(element: Element): Video = throw UnsupportedOperationException()
    override fun videoUrlParse(document: Document): String = throw UnsupportedOperationException()

    // ============================== Settings ==============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
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
                    else -> 0
                }
            },
        )
    }

    private fun parseStatus(status: String?): Int {
        return when {
            status?.contains("Ongoing", ignoreCase = true) == true -> SAnime.ONGOING
            status?.contains("Completed", ignoreCase = true) == true -> SAnime.COMPLETED
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

    private fun String.encodeURL(): String = java.net.URLEncoder.encode(this, "UTF-8")
}
