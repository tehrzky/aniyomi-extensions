package eu.kanade.tachiyomi.animeextension.en.tokuzilla

import android.util.Base64
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.injectLazy

class Tokuzilla : ParsedAnimeHttpSource() {

    override val name = "Tokuzilla"
    override val baseUrl = "https://tokuzl.net"
    override val lang = "en"
    override val supportsLatest = true

    private val json: Json by injectLazy()
    private val extractor by lazy { TokuzlExtractor(client, headers, json) }

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
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = 
        GET("$baseUrl/page/$page?s=$query", headers)

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

        return try {
            extractor.videosFromUrl(response.request.url.toString(), episodeName)
        } catch (e: Exception) {
            // Fallback to iframe if extraction fails
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

class TokuzlExtractor(
    private val client: OkHttpClient,
    private val headers: Headers,
    private val json: Json
) {

    @Serializable
    data class VideoResponse(
        val url: String? = null,
        val sources: List<VideoSource>? = null,
        val file: String? = null
    )

    @Serializable
    data class VideoSource(
        val file: String,
        val label: String? = null,
        val type: String? = null
    )

    fun videosFromUrl(url: String, name: String): List<Video> {
        return try {
            val mainBody = client.newCall(GET(url, headers)).execute().use { it.body.string() }
            val document = Jsoup.parse(mainBody)

            // Look for p2pplay iframe
            val iframeElement = document.selectFirst("iframe[src*=\"p2pplay\"]") 
                ?: document.selectFirst("iframe[id=frame]")
            
            if (iframeElement == null) {
                return emptyList()
            }

            val iframeUrl = iframeElement.attr("src")
            
            // Extract video ID from iframe URL (after the # symbol)
            val videoId = when {
                iframeUrl.contains("#") -> iframeUrl.substringAfterLast("#")
                iframeUrl.contains("id=") -> iframeUrl.substringAfter("id=").substringBefore("&")
                else -> return emptyList()
            }

            if (videoId.isBlank()) {
                return emptyList()
            }

            extractP2PPlayStreams(videoId, name)
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun extractP2PPlayStreams(videoId: String, name: String): List<Video> {
        val videos = mutableListOf<Video>()
        
        try {
            // Build API URL with proper parameters
            val apiUrl = "https://t1.p2pplay.pro/api/v1/video?id=$videoId&w=1920&h=1080&r=tokuzl.net"
            
            val apiHeaders = headers.newBuilder()
                .add("Accept", "*/*")
                .add("Referer", "https://tokuzl.net/")
                .add("Origin", "https://tokuzl.net")
                .build()

            val response = client.newCall(GET(apiUrl, apiHeaders)).execute()
            
            if (!response.isSuccessful) {
                return emptyList()
            }

            val encodedData = response.use { it.body.string().trim() }
            
            if (encodedData.isBlank()) {
                return emptyList()
            }

            // Decode base64 response
            val decodedBytes = Base64.decode(encodedData, Base64.DEFAULT)
            val decodedData = String(decodedBytes)

            // Try to parse as JSON first
            videos.addAll(parseJsonResponse(decodedData, name))
            
            // If no videos found via JSON, try regex parsing
            if (videos.isEmpty()) {
                videos.addAll(parseVideoUrlsFromText(decodedData, name))
            }

        } catch (e: Exception) {
            // Silent fail
        }

        return videos.distinctBy { it.url }
    }

    private fun parseJsonResponse(data: String, name: String): List<Video> {
        val videos = mutableListOf<Video>()
        
        try {
            // Try parsing as VideoResponse
            val videoResponse = json.decodeFromString<VideoResponse>(data)
            
            // Check for direct URL
            videoResponse.url?.let { url ->
                if (url.isNotBlank()) {
                    val quality = extractQualityFromUrl(url)
                    videos.add(Video(url, "TOKUZL $quality - $name", url))
                }
            }
            
            // Check for file
            videoResponse.file?.let { url ->
                if (url.isNotBlank()) {
                    val quality = extractQualityFromUrl(url)
                    videos.add(Video(url, "TOKUZL $quality - $name", url))
                }
            }
            
            // Check for sources array
            videoResponse.sources?.forEach { source ->
                val quality = source.label ?: extractQualityFromUrl(source.file)
                videos.add(Video(source.file, "TOKUZL $quality - $name", source.file))
            }
        } catch (e: Exception) {
            // Not valid JSON or different structure, will try regex parsing
        }
        
        return videos
    }

    private fun parseVideoUrlsFromText(data: String, name: String): List<Video> {
        val videos = mutableListOf<Video>()

        // Look for m3u8 URLs
        val m3u8Regex = Regex("""(https?://[^\s"'<>]+\.m3u8[^\s"'<>]*)""", RegexOption.IGNORE_CASE)
        m3u8Regex.findAll(data).forEach { match ->
            val url = match.value
            val quality = extractQualityFromUrl(url)
            videos.add(Video(url, "TOKUZL $quality - $name", url))
        }

        // Look for mp4 URLs
        val mp4Regex = Regex("""(https?://[^\s"'<>]+\.mp4[^\s"'<>]*)""", RegexOption.IGNORE_CASE)
        mp4Regex.findAll(data).forEach { match ->
            val url = match.value
            val quality = extractQualityFromUrl(url)
            videos.add(Video(url, "TOKUZL $quality - $name", url))
        }

        return videos
    }

    private fun extractQualityFromUrl(url: String): String {
        return when {
            url.contains("1080", ignoreCase = true) || url.contains("fullhd", ignoreCase = true) -> "1080p"
            url.contains("720", ignoreCase = true) || url.contains("hd", ignoreCase = true) -> "720p"
            url.contains("480", ignoreCase = true) -> "480p"
            url.contains("360", ignoreCase = true) -> "360p"
            url.contains("240", ignoreCase = true) -> "240p"
            url.contains("master", ignoreCase = true) || url.contains("index", ignoreCase = true) -> "Auto"
            else -> "Default"
        }
    }
}
