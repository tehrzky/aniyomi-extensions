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

    // Method 3: Extract from iframe if no servers found
    if (serverLinks.isEmpty()) {
        document.select("iframe[src], iframe[data-src]").forEach { iframe ->
            val iframeSrc = iframe.attr("src").ifBlank { iframe.attr("data-src") }
            if (iframeSrc.isNotBlank()) {
                serverLinks["Standard Server"] = iframeSrc
            }
        }
    }

    // Now process each server link and extract videos using appropriate extractors
    serverLinks.forEach { (serverName, url) ->
        val fullUrl = when {
            url.startsWith("http") -> url
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> "$baseUrl$url"
            else -> url
        }

        try {
            when {
                // StreamWish and its mirrors
                fullUrl.contains("streamwish", ignoreCase = true) ||
                    fullUrl.contains("strwish", ignoreCase = true) ||
                    fullUrl.contains("wishfast", ignoreCase = true) ||
                    fullUrl.contains("awish", ignoreCase = true) ||
                    fullUrl.contains("streamplay", ignoreCase = true) -> {
                    videos.addAll(
                        StreamWishExtractor(client, headers).videosFromUrl(
                            url = fullUrl,
                            prefix = serverName,
                            qualitySelector = { it.quality.contains("1080") || it.quality.contains("720") },
                        ),
                    )
                }

                // VidHide and its mirrors
                fullUrl.contains("vidhide", ignoreCase = true) ||
                    fullUrl.contains("vidhidevip", ignoreCase = true) ||
                    fullUrl.contains("vidspeeds", ignoreCase = true) -> {
                    videos.addAll(
                        VidHideExtractor(client).videosFromUrl(
                            url = fullUrl,
                            prefix = serverName,
                            qualitySelector = { it.quality.contains("1080") || it.quality.contains("720") },
                        ),
                    )
                }

                // StreamTape
                fullUrl.contains("streamtape", ignoreCase = true) ||
                    fullUrl.contains("strtape", ignoreCase = true) ||
                    fullUrl.contains("stape", ignoreCase = true) -> {
                    videos.addAll(
                        StreamTapeExtractor(client).videosFromUrl(
                            url = fullUrl,
                            prefix = serverName,
                            qualitySelector = { it.quality.contains("1080") || it.quality.contains("720") },
                        ),
                    )
                }

                // MixDrop and its mirrors
                fullUrl.contains("mixdrop", ignoreCase = true) ||
                    fullUrl.contains("mixdrp", ignoreCase = true) -> {
                    videos.addAll(
                        MixDropExtractor(client).videosFromUrl(
                            url = fullUrl,
                            prefix = serverName,
                            qualitySelector = { it.quality.contains("1080") || it.quality.contains("720") },
                        ),
                    )
                }

                // Filemoon and its mirrors
                fullUrl.contains("filemoon", ignoreCase = true) ||
                    fullUrl.contains("moonplayer", ignoreCase = true) -> {
                    videos.addAll(
                        FilemoonExtractor(client).videosFromUrl(
                            url = fullUrl,
                            prefix = serverName,
                            qualitySelector = { it.quality.contains("1080") || it.quality.contains("720") },
                        ),
                    )
                }

                // DoodStream and its mirrors
                fullUrl.contains("dood", ignoreCase = true) ||
                    fullUrl.contains("doodstream", ignoreCase = true) ||
                    fullUrl.contains("ds2play", ignoreCase = true) ||
                    fullUrl.contains("ds2video", ignoreCase = true) -> {
                    videos.addAll(
                        DoodExtractor(client).videosFromUrl(
                            url = fullUrl,
                            prefix = serverName,
                            qualitySelector = { it.quality.contains("1080") || it.quality.contains("720") },
                        ),
                    )
                }

                // Mp4Upload
                fullUrl.contains("mp4upload", ignoreCase = true) -> {
                    videos.addAll(
                        Mp4uploadExtractor(client).videosFromUrl(
                            url = fullUrl,
                            prefix = serverName,
                            qualitySelector = { it.quality.contains("1080") || it.quality.contains("720") },
                        ),
                    )
                }

                // Streamlare
                fullUrl.contains("streamlare", ignoreCase = true) ||
                    fullUrl.contains("slwatch", ignoreCase = true) -> {
                    videos.addAll(
                        StreamlareExtractor(client).videosFromUrl(
                            url = fullUrl,
                            prefix = serverName,
                            qualitySelector = { it.quality.contains("1080") || it.quality.contains("720") },
                        ),
                    )
                }

                // For unknown servers, add the embed URL directly as fallback
                else -> {
                    videos.add(Video(fullUrl, serverName, fullUrl))
                }
            }
        } catch (e: Exception) {
            // If extraction fails, add the embed URL as fallback
            videos.add(Video(fullUrl, "$serverName (Embed)", fullUrl))
        }
    }

    return videos.distinctBy { it.url }
}
