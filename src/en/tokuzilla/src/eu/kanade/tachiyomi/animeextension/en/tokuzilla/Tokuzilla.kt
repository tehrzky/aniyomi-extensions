package eu.kanade.tachiyomi.animeextension.en.tokuzilla

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.lib.doodextractor.DoodExtractor
import eu.kanade.tachiyomi.lib.mp4uploadextractor.Mp4UploadExtractor
import eu.kanade.tachiyomi.lib.streamwishextractor.StreamWishExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class Tokuzilla : ParsedAnimeHttpSource() {

    override val name = "Tokuzilla"
    override val baseUrl = "https://tokuzl.net"
    override val lang = "en"
    override val supportsLatest = true

    override fun popularAnimeSelector() = "div.col-sm-3.col-xs-6.item"
    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/page/$page")
    override fun popularAnimeFromElement(element: Element) = SAnime.create().apply {
        element.selectFirst("a")!!.run {
            setUrlWithoutDomain(attr("href"))
            title = attr("title")
        }
        thumbnail_url = element.selectFirst("img")!!.attr("src")
    }
    override fun popularAnimeNextPageSelector() = "a.next.page-numbers"

    override fun latestUpdatesSelector() = popularAnimeSelector()
    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/page/$page")
    override fun latestUpdatesFromElement(element: Element) = popularAnimeFromElement(element)
    override fun latestUpdatesNextPageSelector() = popularAnimeNextPageSelector()

    override fun searchAnimeFromElement(element: Element) = popularAnimeFromElement(element)
    override fun searchAnimeNextPageSelector() = popularAnimeNextPageSelector()
    override fun searchAnimeSelector() = popularAnimeSelector()
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = GET("$baseUrl/page/$page?s=$query")

    override fun animeDetailsParse(document: Document) = SAnime.create().apply {
        title = document.selectFirst("h1")?.text() ?: ""
        thumbnail_url = document.selectFirst("meta[property=og:image]")?.attr("content")
    }

    override fun episodeListSelector() = "ul.pagination.post-tape a"
    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        return document.select(episodeListSelector()).mapIndexed { index, element ->
            SEpisode.create().apply {
                setUrlWithoutDomain(element.attr("href"))
                name = "Episode ${index + 1}"
                episode_number = (index + 1).toFloat()
            }
        }
    }
    override fun episodeFromElement(element: Element) = throw UnsupportedOperationException()

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val frameLink = document.selectFirst("iframe[id=frame]")?.attr("src")

        return if (frameLink != null) {
            // Try multiple extractors for different video hosts
            val videos = mutableListOf<Video>()

            // Extract from the iframe URL directly if it's a known host
            when {
                frameLink.contains("streamwish") -> {
                    StreamWishExtractor(client).videosFromUrl(frameLink, headers)?.let {
                        videos.addAll(it)
                    }
                }
                frameLink.contains("dood") -> {
                    DoodExtractor(client).videosFromUrl(frameLink, headers)?.let {
                        videos.addAll(it)
                    }
                }
                frameLink.contains("mp4upload") -> {
                    Mp4UploadExtractor(client).videosFromUrl(frameLink, headers)?.let {
                        videos.addAll(it)
                    }
                }
                else -> {
                    // For p2pplay and other hosts, try to follow redirects
                    try {
                        val frameResponse = client.newCall(GET(frameLink, headers)).execute()
                        val frameHtml = frameResponse.use { it.body.string() }

                        // Look for direct video links in the frame
                        val videoRegex = """https?://[^"'\s]*\.(mp4|m3u8|mkv|avi)[^"'\s]*""".toRegex()
                        val matches = videoRegex.findAll(frameHtml)

                        matches.forEach { match ->
                            val url = match.value
                            videos.add(Video(url, "Direct Link", url))
                        }
                    } catch (e: Exception) {
                        // If extraction fails, return empty list
                    }
                }
            }

            videos
        } else {
            emptyList()
        }
    }

    override fun videoListSelector() = throw UnsupportedOperationException()
    override fun videoFromElement(element: Element) = throw UnsupportedOperationException()
    override fun videoUrlParse(document: Document) = throw UnsupportedOperationException()
}
