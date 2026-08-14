package eu.kanade.tachiyomi.extension.ar.waveteamy

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import keiyoushi.utils.extractNextJs
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonString
import keiyoushi.utils.tryParse
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

class WaveTeamy : HttpSource() {
    override val name = "WaveTeamy"
    override val baseUrl = "https://waveteamy.com"
    override val lang = "ar"

    private val cloudUrl = "https://wavefbn.online"
    private val apiUrl = "$baseUrl/wapi/v1"

    private val pageLimit = 40
    override val supportsLatest = true

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ENGLISH)
    private val oldDateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.ENGLISH)
    private val currentDateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSSSS", Locale.ENGLISH)

    override val client =
        network.cloudflareClient
            .newBuilder()
            .rateLimit(10, 1, TimeUnit.SECONDS)
            .build()

    override fun headersBuilder() = super.headersBuilder()
        .set("Referer", "$baseUrl/")

    private val rscHeaders = headersBuilder()
        .set("rsc", "1")
        .build()

    private val jsonMediaType = "application/json".toMediaType()

    // Popular
    override fun popularMangaRequest(page: Int) = POST(
        "$apiUrl/series/filter",
        headers,
        WSeriesFilter(
            page = page,
            limit = pageLimit,
            orderBy = "VIEWS",
        ).toJsonString().toRequestBody(jsonMediaType),
    )

    override fun popularMangaParse(response: Response): MangasPage {
        val dto = response.parseAs<WApiResponse<WSeriesList>>().data
        val mangas = dto.series.map { it.toSManga() }
        return MangasPage(mangas, !dto.isLastPage)
    }

    // Latest
    override fun latestUpdatesRequest(page: Int) = POST(
        "$apiUrl/series/filter",
        headers,
        WSeriesFilter(
            page = page,
            limit = pageLimit,
        ).toJsonString().toRequestBody(jsonMediaType),
    )

    override fun latestUpdatesParse(response: Response) = popularMangaParse(response)

    // Search
    override fun searchMangaRequest(
        page: Int,
        query: String,
        filters: FilterList,
    ) = POST(
        "$apiUrl/series/filter",
        headers,
        WSeriesFilter(
            value = query,
            page = page,
            limit = pageLimit,
        ).toJsonString().toRequestBody(jsonMediaType),
    )

    override fun searchMangaParse(response: Response) = popularMangaParse(response)

    // Manga Details
    override fun mangaDetailsRequest(manga: SManga): Request = GET(baseUrl + manga.url, rscHeaders)

    override fun mangaDetailsParse(response: Response): SManga = SManga.create().apply {
        val mangaData = response.extractNextJs<WSeriesPage>()!!.mangaData
        title = mangaData.name
        thumbnail_url = mangaData.cover.toImage()
        description = mangaData.story?.replace("\\n", "\n")
        genre = (mangaData.genre + mangaData.type)
            .filterNot { it.isNullOrBlank() }
            .joinToString(", ")
        status = mangaData.status.toStatus()
        artist = mangaData.artist.takeIf { it != "Updating" }
        author = mangaData.author.takeIf { it != "Updating" }
    }

    // Chapters
    override fun chapterListRequest(manga: SManga) = GET(baseUrl + manga.url, rscHeaders)

    override fun chapterListParse(response: Response): List<SChapter> {
        val seriesPage = response.extractNextJs<WSeriesPage>() ?: return emptyList()
        val chapters = seriesPage.chaptersData.toMutableList()
        val workId = seriesPage.mangaData.postId
        var page = 2
        var isLastPage = chapters.size >= seriesPage.mangaData.chapters

        while (!isLastPage) {
            val request = POST(
                "$apiUrl/series/chapters/get",
                headers,
                WChaptersRequest(
                    postId = workId,
                    limit = 100,
                    page = page++,
                ).toJsonString().toRequestBody(jsonMediaType),
            )
            val nextChapters = client.newCall(request).execute().use { res ->
                if (!res.isSuccessful) throw Exception("HTTP ${res.code}")
                res.parseAs<WApiResponse<WChapters>>().data
            }

            chapters.addAll(nextChapters.chapters)
            isLastPage = nextChapters.isLastPage || nextChapters.chapters.isEmpty()
        }

        return chapters.distinctBy { it.id }.map { chapter ->
            SChapter.create().apply {
                url = "/series/$workId/${chapter.chapter}"
                name = buildString {
                    append("الفصل ${chapter.chapter.toString().removeSuffix(".0")}")
                    chapter.title?.let {
                        append(" - $it")
                    }
                }
                date_upload = currentDateFormat.tryParse(chapter.postTime)
                    .takeIf { it != 0L }
                    ?: dateFormat.tryParse(chapter.postTime)
                        .takeIf { it != 0L }
                    ?: oldDateFormat.tryParse(chapter.postTime)
            }
        }
    }

    // Pages
    override fun pageListRequest(chapter: SChapter): Request = GET(baseUrl + chapter.url, rscHeaders)

    override fun pageListParse(response: Response): List<Page> {
        val chapter = response.extractNextJs<WReaderPage>()!!.currentChapter
        if (!chapter.hasAccess && chapter.images.isEmpty()) {
            throw Exception("الفصل مقفل")
        }

        return chapter.images.mapIndexed { index, image ->
            Page(index, "", image.toImage())
        }
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    private fun String?.toStatus() = when (this) {
        "مستمر" -> SManga.ONGOING
        "منتهي", "مكتمل" -> SManga.COMPLETED
        "متوقف" -> SManga.ON_HIATUS
        else -> SManga.UNKNOWN
    }

    fun String.toImage(): String {
        val t = this.replace(" ", "%20")
        return when {
            this.startsWith("http") -> t
            this.startsWith("projects") ||
                this.startsWith("series") ||
                this.startsWith("users") -> "$cloudUrl/$t"
            else ->
                "$baseUrl/$t"
        }
    }

    private fun WManga.toSManga(): SManga = SManga.create().apply {
        url = "/series/$postId"
        title = this@toSManga.title
        thumbnail_url = imageUrl.toImage()
    }

    @Serializable
    class WApiResponse<T>(
        val data: T,
    )

    @Serializable
    class WSeriesFilter(
        val value: String = "",
        val page: Int,
        val limit: Int,
        @SerialName("order_by")
        val orderBy: String? = null,
    )

    @Serializable
    class WSeriesList(
        val series: List<WManga>,
        val isLastPage: Boolean,
    )

    @Serializable
    class WManga(
        val postId: Long,
        val title: String,
        val imageUrl: String,
    )

    @Serializable
    class WSeriesPage(
        val mangaData: WMangaDetails,
        val chaptersData: List<WChapterList>,
    )

    @Serializable
    class WMangaDetails(
        val name: String,
        val cover: String,
        val story: String?,
        val status: String?,
        val type: String?,
        val genre: List<String>,
        val artist: String?,
        val author: String?,
        val postId: Long,
        val chapters: Int,
    )

    @Serializable
    class WChapters(
        val chapters: List<WChapterList>,
        val isLastPage: Boolean,
    )

    @Serializable
    class WChaptersRequest(
        val postId: Long,
        val limit: Int,
        val page: Int,
    )

    @Serializable
    class WChapterList(
        val id: Long,
        val title: String?,
        val chapter: Double,
        val postTime: String?,
    )

    @Serializable
    class WReaderPage(
        val currentChapter: WCurrentChapter,
    )

    @Serializable
    class WCurrentChapter(
        val images: List<String>,
        val hasAccess: Boolean,
    )
}
