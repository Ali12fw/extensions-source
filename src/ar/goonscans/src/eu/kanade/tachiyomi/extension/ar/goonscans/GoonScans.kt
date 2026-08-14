package eu.kanade.tachiyomi.extension.ar.goonscans

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.parseAs
import keiyoushi.utils.tryParse
import kotlinx.serialization.Serializable
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import rx.Observable
import java.text.SimpleDateFormat
import java.util.Locale

class GoonScans : ParsedHttpSource() {

    override val name = "Goon Scans"
    override val baseUrl = "https://goonscans.org"
    override val lang = "ar"
    override val supportsLatest = true

    override val client = network.cloudflareClient

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")

    override fun popularMangaRequest(page: Int): Request = listingRequest(page, "views")

    override fun popularMangaSelector() = ".webtoons-grid > .webtoon-card"

    override fun popularMangaFromElement(element: Element): SManga = SManga.create().apply {
        val link = element.selectFirst("h3.webtoon-title-card a")!!
        title = link.selectFirst(".title-text")?.text()?.trim().orEmpty()
            .ifEmpty { link.text().trim() }
        url = link.attr("abs:href").toHttpUrl().encodedPath
        thumbnail_url = element.selectFirst("img")?.imgAttr()
    }

    override fun popularMangaNextPageSelector() = ".pagination-btn.next-btn"

    override fun latestUpdatesRequest(page: Int): Request = listingRequest(page, "latest_chapter")

    override fun latestUpdatesSelector() = popularMangaSelector()

    override fun latestUpdatesFromElement(element: Element) = popularMangaFromElement(element)

    override fun latestUpdatesNextPageSelector() = popularMangaNextPageSelector()

    private fun listingRequest(page: Int, orderBy: String): Request {
        val url = "$baseUrl/title/".toHttpUrl().newBuilder()
            .addQueryParameter("orderby", orderBy)
            .addQueryParameter("paged", page.toString())
            .build()
        return GET(url, headers)
    }

    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> {
        if (query.isBlank()) return fetchPopularManga(page)
        if (page > 1) return Observable.just(MangasPage(emptyList(), false))

        return Observable.fromCallable {
            val searchPageUrl = "$baseUrl/".toHttpUrl().newBuilder()
                .addQueryParameter("s", query)
                .build()
            val nonce = client.newCall(GET(searchPageUrl, headers)).execute().use { response ->
                if (!response.isSuccessful) throw Exception("HTTP ${response.code}")
                response.asJsoup().select("script")
                    .asSequence()
                    .map(Element::data)
                    .mapNotNull { NONCE_REGEX.find(it)?.groupValues?.get(1) }
                    .firstOrNull()
                    ?: throw Exception("Search nonce not found")
            }

            val body = FormBody.Builder()
                .add("action", "glsa_live_search")
                .add("term", query)
                .add("nonce", nonce)
                .build()
            val requestHeaders = headers.newBuilder()
                .set("Referer", searchPageUrl.toString())
                .set("X-Requested-With", "XMLHttpRequest")
                .build()
            val request = POST("$baseUrl/wp-admin/admin-ajax.php", requestHeaders, body)

            val searchResponse = client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw Exception("HTTP ${response.code}")
                response.parseAs<SearchResponse>()
            }
            if (!searchResponse.success) throw Exception("Search request failed")

            val document = Jsoup.parseBodyFragment(searchResponse.data.html, baseUrl)
            val mangas = document.select(searchMangaSelector()).map(::searchMangaFromElement)
            MangasPage(mangas, false)
        }
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request = throw UnsupportedOperationException()

    override fun searchMangaSelector() = "a.result-item"

    override fun searchMangaFromElement(element: Element): SManga = SManga.create().apply {
        title = element.selectFirst(".result-title")!!.text().trim()
        url = element.attr("abs:href").toHttpUrl().encodedPath
        thumbnail_url = element.selectFirst("img.result-img")?.imgAttr()
    }

    override fun searchMangaNextPageSelector() = null

    override fun mangaDetailsParse(document: Document): SManga = SManga.create().apply {
        title = document.selectFirst("h1.webtoon-title")!!.text().trim()
        thumbnail_url = document.selectFirst("img.webtoon-cover")?.imgAttr()
        description = document.selectFirst(".description-content")?.text()?.trim()
        genre = document.select(".genre-tags a").joinToString { it.text().trim() }
        author = document.select("a[href*=/writer/]").joinToString { it.text().trim() }
        artist = document.select("a[href*=/artist/]").joinToString { it.text().trim() }
        status = when (document.selectFirst(".cover-status-badge")?.text()?.trim()?.lowercase()) {
            "ongoing" -> SManga.ONGOING
            "completed" -> SManga.COMPLETED
            "stopped" -> SManga.CANCELLED
            else -> SManga.UNKNOWN
        }
    }

    override fun chapterListSelector() = "li.chapter-item"

    override fun chapterFromElement(element: Element): SChapter = SChapter.create().apply {
        val link = element.selectFirst("a.chapter-link")!!
        name = link.selectFirst(".chapter-number")?.text()?.trim().orEmpty()
            .ifEmpty { link.text().trim() }
        url = link.attr("abs:href").toHttpUrl().encodedPath
        date_upload = element.selectFirst(".chapter-date")?.text()?.trim()?.let(dateFormat::tryParse) ?: 0L
    }

    override fun pageListParse(document: Document): List<Page> {
        val script = document.select("script")
            .asSequence()
            .map(Element::data)
            .firstOrNull { "imageUrls" in it }
            ?: throw Exception("Chapter image data not found")
        val encodedUrls = IMAGE_URLS_REGEX.find(script)?.groupValues?.get(1)
            ?: throw Exception("Chapter image list not found")
        val imageUrls = encodedUrls.parseAs<List<String>>()

        return imageUrls.mapIndexed { index, imageUrl ->
            val resolvedUrl = when {
                imageUrl.startsWith("//") -> "https:$imageUrl"
                imageUrl.startsWith("/") -> "$baseUrl$imageUrl"
                else -> imageUrl
            }
            Page(index, document.location(), resolvedUrl)
        }
    }

    override fun imageUrlParse(document: Document): String = throw UnsupportedOperationException()

    private fun Element.imgAttr(): String? = attr("abs:data-src").takeIf(String::isNotBlank)
        ?: attr("abs:data-lazy-src").takeIf(String::isNotBlank)
        ?: attr("abs:src").takeIf(String::isNotBlank)

    private val dateFormat = SimpleDateFormat("yyyy.dd.MM", Locale.ENGLISH).apply {
        isLenient = false
    }

    @Serializable
    private data class SearchResponse(
        val success: Boolean,
        val data: SearchData,
    )

    @Serializable
    private data class SearchData(
        val html: String,
    )

    companion object {
        private val NONCE_REGEX = Regex("""formData\.append\('nonce',\s*'([^']+)'\)""")
        private val IMAGE_URLS_REGEX = Regex("""const\s+imageUrls\s*=\s*(\[[\s\S]*?])\s*;""")
    }
}
