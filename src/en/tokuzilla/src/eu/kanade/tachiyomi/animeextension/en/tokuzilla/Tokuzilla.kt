package eu.kanade.tachiyomi.animeextension.en.tokuzilla

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.util.Base64

class Tokuzilla : ParsedAnimeHttpSource() {

    override val name = "Tokuzilla"
    override val baseUrl = "https://tokuzl.net"
    override val lang = "en"
    override val supportsLatest = true

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
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList) = GET("$baseUrl/page/$page?s=$query", headers)

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

        // Extract video ID from iframe
        val iframeElement = document.selectFirst("iframe[id=frame]")
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

        // Get the actual HLS stream from p2pplay API
        val videos = extractP2PPlayStreams(videoId, episodeName)
        println("DEBUG: Total videos extracted: ${videos.size}")
        
        return videos
    }

    private fun extractP2PPlayStreams(videoId: String, episodeName: String): List<Video> {
        return try {
            val videoApiUrl = "https://t1.p2pplay.pro/api/v1/video?id=$videoId&w=1920&h=1080&r=tokuzl.net"
            println("DEBUG: Calling API: $videoApiUrl")

            val response = client.newCall(GET(videoApiUrl, headers)).execute()
            println("DEBUG: API response code: ${response.code}")

            if (response.isSuccessful) {
                val encodedData = response.use { it.body.string().trim() }
                println("DEBUG: Encoded data length: ${encodedData.length}")
                println("DEBUG: First 100 chars of encoded data: ${encodedData.take(100)}")

                if (encodedData.isNotBlank()) {
                    // Decode the base64 response
                    try {
                        val decodedBytes = Base64.getDecoder().decode(encodedData)
                        val decodedData = String(decodedBytes)
                        println("DEBUG: Decoded data length: ${decodedData.length}")
                        println("DEBUG: First 200 chars of decoded data: ${decodedData.take(200)}")

                        // Parse the HLS stream URL from the decoded data
                        parseHlsStreams(decodedData, episodeName)
                    } catch (e: Exception) {
                        println("DEBUG: Base64 decoding failed: ${e.message}")
                        emptyList()
                    }
                } else {
                    println("DEBUG: Empty encoded data")
                    emptyList()
                }
            } else {
                println("DEBUG: API call failed with code: ${response.code}")
                emptyList()
            }
        } catch (e: Exception) {
            println("DEBUG: API call exception: ${e.message}")
            emptyList()
        }
    }

    private fun parseHlsStreams(data: String, episodeName: String): List<Video> {
        val videos = mutableListOf<Video>()

        // Look for HLS stream patterns like:
        // https://t1.p2pplay.pro/hls/.../index-f1-v1-a1.m3u8?v=...
        val hlsRegex = Regex("""(https?://t1\.p2pplay\.pro/hls/[^\s"']+\.m3u8[^\s"']*)""")
        val hlsMatches = hlsRegex.findAll(data).toList()
        println("DEBUG: Found ${hlsMatches.size} HLS URLs with specific pattern")

        hlsMatches.forEach { match ->
            val url = match.value
            println("DEBUG: HLS URL found: $url")
            // Extract quality from the URL if possible
            val quality = when {
                url.contains("1080") -> "1080p"
                url.contains("720") -> "720p"
                url.contains("480") -> "480p"
                url.contains("360") -> "360p"
                else -> "Auto"
            }
            videos.add(Video(url, "P2PPlay $quality - $episodeName", url))
        }

        // If no HLS streams found, try to find any m3u8 URL
        if (videos.isEmpty()) {
            val m3u8Regex = Regex("""(https?://[^\s"']+\.m3u8[^\s"']*)""")
            val m3u8Matches = m3u8Regex.findAll(data).toList()
            println("DEBUG: Found ${m3u8Matches.size} generic m3u8 URLs")
            
            m3u8Matches.forEach { match ->
                val url = match.value
                println("DEBUG: Generic m3u8 URL: $url")
                videos.add(Video(url, "HLS Stream - $episodeName", url))
            }
        }

        // If still no videos, try to find any URL
        if (videos.isEmpty()) {
            val urlRegex = Regex("""(https?://[^\s"']+)""")
            val allUrls = urlRegex.findAll(data).take(10).toList()
            println("DEBUG: First 10 URLs found in data:")
            allUrls.forEachIndexed { index, match ->
                println("DEBUG: URL $index: ${match.value}")
            }
        }

        return videos.distinctBy { it.url }
    }

    override fun videoListSelector() = throw UnsupportedOperationException()
    override fun videoFromElement(element: Element) = throw UnsupportedOperationException()
    override fun videoUrlParse(document: Document) = throw UnsupportedOperationException()
}
