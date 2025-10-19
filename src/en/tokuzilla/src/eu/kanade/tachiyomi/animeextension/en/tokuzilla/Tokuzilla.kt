package eu.kanade.tachiyomi.animeextension.en.tokuzilla

import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.lib.chillxextractor.ChillxExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import extensions.utils.getPreferencesLazy
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class Tokuzilla : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "Tokuzilla"

    override val baseUrl = "https://tokuzl.net"

    override val lang = "en"

    override val supportsLatest = true

    private val preferences by getPreferencesLazy()

    // ============================== Popular ===============================
    override fun popularAnimeSelector() = "div.col-sm-3.col-xs-6.item"

    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/page/$page")

    override fun popularAnimeFromElement(element: Element) = SAnime.create().apply {
        element.selectFirst("a")!!.run {
            setUrlWithoutDomain(attr("href"))
            title = attr("title").ifEmpty { selectFirst("h3")?.text() ?: "" }
        }
        thumbnail_url = element.selectFirst("img")!!.attr("src").ifEmpty {
            element.selectFirst("img")!!.attr("data-src")
        }
    }

    override fun popularAnimeNextPageSelector() = "a.next.page-numbers"

    // =============================== Latest ===============================
    override fun latestUpdatesSelector() = popularAnimeSelector()

    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/page/$page")

    override fun latestUpdatesFromElement(element: Element) = popularAnimeFromElement(element)

    override fun latestUpdatesNextPageSelector() = popularAnimeNextPageSelector()

    // =============================== Search ===============================
    override fun searchAnimeFromElement(element: Element) = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector() = popularAnimeNextPageSelector()

    override fun searchAnimeSelector() = popularAnimeSelector()

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val filterList = if (filters.isEmpty()) getFilterList() else filters
        val genreFilter = filterList.find { it is GenreFilter } as GenreFilter?

        return if (query.isNotBlank()) {
            GET("$baseUrl/page/$page?s=$query", headers)
        } else {
            genreFilter?.toUriPart()?.let { genre ->
                GET("$baseUrl$genre/page/$page", headers)
            } ?: GET("$baseUrl/page/$page", headers)
        }
    }

    override fun getFilterList() = AnimeFilterList(
        AnimeFilter.Header("NOTE: Ignore if using text search"),
        GenreFilter(),
    )

    private class GenreFilter : UriPartFilter(
        "Genres",
        arrayOf(
            Pair("All", ""),
            Pair("Kamen Rider", "/kamen-rider"),
            Pair("Super Sentai", "/super-sentai"),
            Pair("Metal Heroes", "/metal-heroes"),
            Pair("Ultraman", "/ultraman"),
            Pair("Armor Hero", "/armor-hero"),
            Pair("Power Ranger", "/power-ranger"),
            Pair("Godzilla", "/godzilla"),
            Pair("Garo", "/garo"),
            Pair("Other", "/other"),
            Pair("Movie", "/movie"),
            Pair("Series", "/series"),
        ),
    )

    private open class UriPartFilter(displayName: String, val vals: Array<Pair<String, String>>) :
        AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document) = SAnime.create().apply {
        val details = document.selectFirst("div.video-details") ?: document
        title = details.selectFirst("h1, h2")?.text() ?: document.selectFirst("title")?.text()?.substringBefore("|")?.trim() ?: ""

        thumbnail_url = document.selectFirst("img[post-id]")?.run {
            attr("src").ifEmpty { attr("data-src") }
        } ?: document.selectFirst("meta[property=og:image]")?.attr("content")

        genre = document.select("p.meta span a, .breadcrumbs a").eachText().joinToString().takeIf(String::isNotBlank)

        description = buildString {
            document.selectFirst("h2#plot + p, .post-entry p")?.text()?.let { append(it) }
            document.select("h3:contains(Story) + p, .post-entry p:not(:first-child)").eachText().forEach {
                if (it.isNotBlank()) {
                    if (isNotEmpty()) append("\n\n")
                    append(it)
                }
            }
        }.takeIf { it.isNotBlank() }

        author = document.selectFirst("p.meta:contains(Year) span, th:contains(Year) + td")?.text()?.let { "Year $it" }

        status = when {
            document.selectFirst("p.meta:contains(Type)")?.text()?.contains("ongoing", true) == true -> SAnime.ONGOING
            document.selectFirst("p.meta:contains(Type)")?.text()?.contains("complete", true) == true -> SAnime.COMPLETED
            else -> SAnime.UNKNOWN
        }
    }

    // ============================== Episodes ==============================
    override fun episodeListSelector() = "ul.pagination.post-tape a"

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val episodes = document.select(episodeListSelector())

        return if (episodes.isNotEmpty()) {
            episodes.mapIndexed { index, element ->
                SEpisode.create().apply {
                    val url = element.attr("href")
                    setUrlWithoutDomain(url)

                    // Extract episode number from URL or text
                    val epNum = when {
                        url.contains("ep=") -> url.substringAfter("ep=").substringBefore("&").toIntOrNull()
                        element.text().toIntOrNull() != null -> element.text().toInt()
                        else -> index + 1
                    }

                    name = "Episode $epNum"
                    episode_number = epNum?.toFloat() ?: (index + 1).toFloat()
                    date_upload = System.currentTimeMillis()
                }
            }
        } else {
            // Single episode/movie
            listOf(SEpisode.create().apply {
                setUrlWithoutDomain(response.request.url.toString())
                name = "Movie"
                episode_number = 1F
                date_upload = System.currentTimeMillis()
            })
        }
    }

    override fun episodeFromElement(element: Element): SEpisode = throw UnsupportedOperationException()

    // ============================ Video Links =============================
    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val frameLink = document.selectFirst("iframe[id=frame]")?.attr("src")

        return if (frameLink != null) {
            ChillxExtractor(client, headers).videoFromUrl(frameLink, baseUrl)
        } else {
            emptyList()
        }
    }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!

        return sortedWith(
            compareByDescending { it.quality.contains(quality) },
        )
    }

    override fun videoListSelector() = throw UnsupportedOperationException()
    override fun videoFromElement(element: Element) = throw UnsupportedOperationException()
    override fun videoUrlParse(document: Document) = throw UnsupportedOperationException()

    // ============================= Preference =============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = PREF_QUALITY_TITLE
            entries = PREF_QUALITY_ENTRIES
            entryValues = PREF_QUALITY_VALUES
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)
    }

    companion object {
        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_TITLE = "Preferred quality"
        private const val PREF_QUALITY_DEFAULT = "1080p"
        private val PREF_QUALITY_ENTRIES = arrayOf("1080p", "720p", "480p", "360p")
        private val PREF_QUALITY_VALUES = PREF_QUALITY_ENTRIES
    }
}
