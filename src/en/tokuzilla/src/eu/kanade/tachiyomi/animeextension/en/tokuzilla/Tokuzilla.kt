package eu.kanade.tachiyomi.animeextension.en.tokuzilla

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.util.Base64

class Tokuzilla : ParsedAnimeHttpSource() {

    override val name = "Tokuzilla"
    override val baseUrl = "https://tokuzl.net"
    override val lang = "en"
    override val supportsLatest = true

    // Add the extractor directly
    private val extractor by lazy { TokuzlExtractor(client) }

    override fun popularAnimeSelector() = "div.col-sm-3.col-xs-6.item"
    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/page/$page", headers)
    override fun popularAnimeFromElement(element: Element) = SAnime.create().apply {
        element.selectFirst("a")!!.run {
            setUrlWithoutDomain(attr("href"))
            title = attr("title")
        }
        thumbnail_url = element.selectFirst("img")!!.attr("src")
    }
    override fun popularAnimeNextPageSelector() = "a.next.page-numbers"

    override fun latestUpdatesSelector() = popularAnimeSelector()
    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/page/$page", headers)
    override fun latestUpdatesFromElement(element: Element) = popularAnimeFromElement(element)
    override fun latestUpdatesNextPageSelector() = popularAnimeNextPageSelector()

    override fun searchAnimeFromElement(element: Element) = popularAnimeFromElement(element)
    override fun searchAnimeNextPageSelector() = popularAnimeNextPageSelector()
    override fun searchAnimeSelector() = popularAnimeSelector()
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = GET("$baseUrl/page/$page?s=$query", headers)

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
        val episodeName = document.selectFirst("h1")?.text() ?: "Episode"

        // Try the extractor first
        val videos = extractor.videosFromUrl(response.request.url.toString(), episodeName, headers)

        // If no videos found, fallback to iframe URL
        return if (videos.isNotEmpty()) {
            videos
        } else {
            val frameLink = document.selectFirst("iframe[id=frame]")?.attr("src")
            if (frameLink != null) {
                listOf(Video(frameLink, "P2PPlay - $episodeName", frameLink))
            } else {
                emptyList()
            }
        }
    }

    override fun videoListSelector() = throw UnsupportedOperationException()
    override fun videoFromElement(element: Element) = throw UnsupportedOperationException()
    override fun videoUrlParse(document: Document) = throw UnsupportedOperationException()
}

// Add the extractor class directly in the same file
class TokuzlExtractor(private val client: OkHttpClient) {

    fun videosFromUrl(url: String, name: String, headers: Headers): List<Video> {
        return try {
            println("DEBUG: Starting extraction from: $url")

            val mainBody = client.newCall(GET(url, headers)).execute().use { it.body.string() }
            val document = Jsoup.parse(mainBody)

            val iframeElement = document.selectFirst("iframe[src*=\"p2pplay\"]")
            if (iframeElement == null) {
                println("DEBUG: No iframe found")
                return emptyList()
            }
            
            val iframeUrl = iframeElement.attr("src")
            println("DEBUG: Found iframe URL: $iframeUrl")

            val videoId = iframeUrl.substringAfterLast("#")
            if (videoId.isBlank()) {
                println("DEBUG: No video ID found in iframe URL")
                return emptyList()
            }

            println("DEBUG: Extracted video ID: $videoId")

            extractP2PPlayStreams(videoId, name, headers)
        } catch (e: Exception) {
            println("DEBUG: Error in main extraction: ${e.message}")
            emptyList()
        }
    }

    private fun extractP2PPlayStreams(videoId: String, name: String, headers: Headers): List<Video> {
        val videoApiUrl = "https://t1.p2pplay.pro/api/v1/video?id=$videoId&w=1920&h=1080&r=tokuzl.net"
        println("DEBUG: Calling API: $videoApiUrl")

        return try {
            val response = client.newCall(GET(videoApiUrl, headers)).execute()
            if (!response.isSuccessful) {
                println("DEBUG: API call failed: ${response.code}")
                return emptyList()
            }

            val encodedData = response.use { it.body.string().trim() }
            if (encodedData.isBlank()) {
                println("DEBUG: Empty response from API")
                return emptyList()
            }

            println("DEBUG: Got encoded data (first 100 chars): ${encodedData.take(100)}")

            decodeAndParseVideoData(encodedData, name)
        } catch (e: Exception) {
            println("DEBUG: Error in API call: ${e.message}")
            emptyList()
        }
    }

    private fun decodeAndParseVideoData(encodedData: String, name: String): List<Video> {
        return try {
            val decodedBytes = Base64.getDecoder().decode(encodedData)
            val decodedData = String(decodedBytes)
            println("DEBUG: Decoded data (first 200 chars): ${decodedData.take(200)}")

            parseVideoUrls(decodedData, name)
        } catch (e: Exception) {
            println("DEBUG: Error decoding base64: ${e.message}")
            emptyList()
        }
    }

    private fun parseVideoUrls(data: String, name: String): List<Video> {
        println("DEBUG: Parsing video URLs from data (length: ${data.length})")

        val videos = mutableListOf<Video>()

        val m3u8Regex = Regex("""(https?://[^\s"']+\.m3u8[^\s"']*)""", RegexOption.IGNORE_CASE)
        val m3u8Matches = m3u8Regex.findAll(data).toList()
        println("DEBUG: Found ${m3u8Matches.size} m3u8 URLs")

        m3u8Matches.forEach { match ->
            val url = match.value
            val quality = extractQualityFromUrl(url)
            println("DEBUG: m3u8 URL: $url")
            videos.add(Video(url, "TOKUZL $quality - $name", url))
        }

        val mp4Regex = Regex("""(https?://[^\s"']+\.mp4[^\s"']*)""", RegexOption.IGNORE_CASE)
        val mp4Matches = mp4Regex.findAll(data).toList()
        println("DEBUG: Found ${mp4Matches.size} mp4 URLs")

        mp4Matches.forEach { match ->
            val url = match.value
            val quality = extractQualityFromUrl(url)
            println("DEBUG: mp4 URL: $url")
            videos.add(Video(url, "TOKUZL $quality - $name", url))
        }

        // Also look for any URLs that might contain video
        if (videos.isEmpty()) {
            val urlRegex = Regex("""(https?://[^\s"']+)""")
            val allUrls = urlRegex.findAll(data).toList()
            println("DEBUG: Found ${allUrls.size} total URLs in data")

            allUrls.take(5).forEach { match ->
                val url = match.value
                println("DEBUG: Potential URL: $url")
            }
        }

        println("DEBUG: Total videos found: ${videos.size}")
        return videos.distinctBy { it.url }
    }

    private fun extractQualityFromUrl(url: String): String {
        return when {
            url.contains("1080") || url.contains("fullhd", true) -> "1080p"
            url.contains("720") || url.contains("hd", true) -> "720p"
            url.contains("480") -> "480p"
            url.contains("360") -> "360p"
            url.contains("240") -> "240p"
            else -> "Auto"
        }
    }
}
