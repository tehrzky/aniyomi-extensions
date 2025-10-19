package eu.kanade.tachiyomi.lib.tokuzlextractor

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import okhttp3.Headers
import okhttp3.OkHttpClient
import org.jsoup.Jsoup
import java.util.Base64

class TokuzlExtractor(private val client: OkHttpClient) {

    fun videosFromUrl(url: String, name: String, headers: Headers = Headers.headersOf()): List<Video> {
        return try {
            // Step 1: Get the main page
            val mainBody = client.newCall(GET(url, headers)).execute()
                .use { it.body.string() }
            val document = Jsoup.parse(mainBody)
            
            // Step 2: Find the video iframe
            val iframeElement = document.selectFirst("iframe[src*=\"p2pplay\"]")
            val iframeUrl = iframeElement?.attr("src") ?: return emptyList()
            
            // Step 3: Extract video ID from iframe URL like: https://t1.p2pplay.pro/#auj9k
            val videoId = iframeUrl.substringAfterLast("#")
            if (videoId.isBlank()) return emptyList()
            
            // Step 4: Get video streams from p2pplay API
            extractP2PPlayStreams(videoId, name, headers)
        } catch (e: Exception) {
            emptyList()
        }
    }
    
    private fun extractP2PPlayStreams(videoId: String, name: String, headers: Headers): List<Video> {
        // Use the video API endpoint that we found
        val videoApiUrl = "https://t1.p2pplay.pro/api/v1/video?id=$videoId&w=1920&h=1080&r=tokuzl.net"
        
        return try {
            val response = client.newCall(GET(videoApiUrl, headers)).execute()
            if (!response.isSuccessful) return emptyList()
            
            val encodedData = response.use { it.body.string().trim() }
            if (encodedData.isBlank()) return emptyList()
            
            decodeAndParseVideoData(encodedData, name)
        } catch (e: Exception) {
            emptyList()
        }
    }
    
    private fun decodeAndParseVideoData(encodedData: String, name: String): List<Video> {
        return try {
            // Decode the base64 response
            val decodedBytes = Base64.getDecoder().decode(encodedData)
            val decodedData = String(decodedBytes)
            
            // Parse the decoded data to extract video URLs
            parseVideoUrls(decodedData, name)
        } catch (e: Exception) {
            emptyList()
        }
    }
    
    private fun parseVideoUrls(data: String, name: String): List<Video> {
        val videos = mutableListOf<Video>()
        
        // Extract m3u8 URLs (most common streaming format)
        val m3u8Regex = Regex("""(https?://[^\s"']+\.m3u8[^\s"']*)""", RegexOption.IGNORE_CASE)
        m3u8Regex.findAll(data).forEach { match ->
            val url = match.value
            val quality = extractQualityFromUrl(url)
            videos.add(Video(url, "$playerName $quality - $name", url))
        }
        
        // Extract mp4 URLs
        val mp4Regex = Regex("""(https?://[^\s"']+\.mp4[^\s"']*)""", RegexOption.IGNORE_CASE)
        mp4Regex.findAll(data).forEach { match ->
            val url = match.value
            val quality = extractQualityFromUrl(url)
            videos.add(Video(url, "$playerName $quality - $name", url))
        }
        
        // If no direct video URLs found, look for any URLs that might be video sources
        if (videos.isEmpty()) {
            val urlRegex = Regex("""(https?://[^\s"']+)""")
            urlRegex.findAll(data).forEach { match ->
                val url = match.value
                if (isLikelyVideoUrl(url)) {
                    videos.add(Video(url, "$playerName Auto - $name", url))
                }
            }
        }
        
        return videos.distinctBy { it.url } // Remove duplicates
    }
    
    private fun isLikelyVideoUrl(url: String): Boolean {
        return url.contains("video", true) || 
               url.contains("stream", true) || 
               url.contains("cdn", true) || 
               url.contains("cloud", true) ||
               url.contains("storage", true) ||
               url.contains("bucket", true)
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
    
    companion object {
        private const val playerName = "TOKUZL"
    }
}
