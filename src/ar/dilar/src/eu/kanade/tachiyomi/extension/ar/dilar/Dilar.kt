package eu.kanade.tachiyomi.extension.ar.dilar

import android.content.SharedPreferences
import android.widget.Toast
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.multisrc.gmanga.Gmanga
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import keiyoushi.utils.getPreferencesLazy
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

private const val MIRROR_PREF_KEY = "MIRROR"
private const val MIRROR_PREF_TITLE = "Dilar : Mirror Urls"
private val MIRROR_PREF_ENTRY_VALUES = arrayOf("https://dilar.tube", "https://golden.rest")
private val MIRROR_PREF_DEFAULT_VALUE = MIRROR_PREF_ENTRY_VALUES[0]
private const val RESTART_TACHIYOMI = ".لتطبيق الإعدادات الجديدة Tachiyomi أعد تشغيل"
private val MEDIA_TYPE_JSON = "application/json; charset=utf-8".toMediaType()

class Dilar :
    Gmanga(
        "Dilar",
        MIRROR_PREF_DEFAULT_VALUE,
        "ar",
    ),
    ConfigurableSource {

    private val crypto by lazy { DilarCrypto(json) }

    private fun dilarHeaders(): Headers = headersBuilder()
        .set("Referer", "$baseUrl/")
        .set("X-DH-Pub", crypto.clientPublicB64)
        .build()

    private fun decryptBody(response: Response): String {
        val bodyStr = response.body.string()
        return if (crypto.isEncrypted(bodyStr)) {
            crypto.decryptResponse(bodyStr)
        } else {
            bodyStr
        }
    }

    override fun popularMangaRequest(page: Int): Request = GET("$baseUrl/api/series?page=$page", dilarHeaders())

    override fun popularMangaParse(response: Response): MangasPage {
        val body = decryptBody(response)
        val data = json.decodeFromString<SeriesListDto>(body)
        val mangas = data.series.map { it.toSManga(baseUrl) }
        return MangasPage(mangas, hasNextPage = data.currentPage < data.totalPages)
    }

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/api/series?page=$page", dilarHeaders())

    override fun latestUpdatesParse(response: Response): MangasPage {
        val body = decryptBody(response)
        val data = json.decodeFromString<SeriesListDto>(body)
        val mangas = data.series.map { it.toSManga(baseUrl) }
        return MangasPage(mangas, hasNextPage = data.currentPage < data.totalPages)
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request = if (query.isNotBlank()) {
        val payload = """{"query":${json.encodeToString(query)},"includes":["Manga"]}"""
        POST("$baseUrl/api/search/quick_search", dilarHeaders(), payload.toRequestBody(MEDIA_TYPE_JSON))
    } else {
        GET("$baseUrl/api/series?page=$page", dilarHeaders())
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val body = decryptBody(response)
        return if (body.trimStart().startsWith("[")) {
            val groups = json.decodeFromString<List<QuickSearchGroupDto>>(body)
            val mangaGroup = groups.firstOrNull { it.`class`.equals("Manga", ignoreCase = true) }
            val mangas = (mangaGroup?.data ?: emptyList()).map { it.toSManga(baseUrl) }
            MangasPage(mangas, hasNextPage = false)
        } else {
            val data = json.decodeFromString<SeriesListDto>(body)
            val mangas = data.series.map { it.toSManga(baseUrl) }
            MangasPage(mangas, hasNextPage = data.currentPage < data.totalPages)
        }
    }

    override fun mangaDetailsRequest(manga: SManga): Request {
        val mangaId = manga.url.substringAfterLast("/")
        return GET("$baseUrl/api/series/$mangaId", dilarHeaders())
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val body = decryptBody(response)
        val details = json.decodeFromString<SeriesDetailsDto>(body)
        return details.toSManga(baseUrl)
    }

    override fun chaptersRequest(manga: SManga): Request {
        val mangaId = manga.url.substringAfterLast("/")
        return GET("$baseUrl/api/chapters/series/$mangaId?page=1", dilarHeaders())
    }

    override fun chaptersParse(response: Response): List<SChapter> {
        val body = decryptBody(response)
        val firstPage = json.decodeFromString<SeriesChaptersResponseDto>(body)
        val chapters = firstPage.chapters.toMutableList()
        val mangaId = response.request.url.pathSegments.lastOrNull() ?: ""

        if (firstPage.totalPages > 1 && mangaId.isNotEmpty()) {
            for (p in 2..firstPage.totalPages) {
                try {
                    val req = GET("$baseUrl/api/chapters/series/$mangaId?page=$p", dilarHeaders())
                    val res = client.newCall(req).execute()
                    if (res.isSuccessful) {
                        val nextBody = decryptBody(res)
                        val nextData = json.decodeFromString<SeriesChaptersResponseDto>(nextBody)
                        chapters.addAll(nextData.chapters)
                    }
                } catch (e: Exception) {
                    // Continue with collected chapters on network glitch
                    break
                }
            }
        }

        return chapters.map { it.toSChapter() }.sortedWith(compareByDescending { it.chapter_number })
    }

    override fun pageListRequest(chapter: SChapter): Request {
        val releaseId = chapter.url.substringAfterLast("/")
        return GET("$baseUrl/api/chapters/$releaseId", dilarHeaders())
    }

    override fun pageListParse(response: Response): List<Page> {
        val releaseId = response.request.url.pathSegments.last()

        var unlockToken: String? = null
        try {
            val unlockReq = POST(
                "$baseUrl/api/chapters/$releaseId/unlock/free",
                dilarHeaders(),
                "{}".toRequestBody(MEDIA_TYPE_JSON),
            )
            val unlockRes = client.newCall(unlockReq).execute()
            if (unlockRes.isSuccessful) {
                val unlockBody = decryptBody(unlockRes)
                val unlockDto = json.decodeFromString<UnlockFreeResponseDto>(unlockBody)
                unlockToken = unlockDto.token
            }
        } catch (e: Exception) {
            // Free pass unlock error fallback
        }

        val readerHeaders = headersBuilder().apply {
            set("Referer", "$baseUrl/")
            set("X-DH-Pub", crypto.clientPublicB64)
            unlockToken?.let { set("X-Unlock-Free-Chapter", it) }
        }.build()

        val readerRes = client.newCall(GET("$baseUrl/api/chapters/$releaseId", readerHeaders)).execute()
        val readerBody = decryptBody(readerRes)
        val readerDto = json.decodeFromString<ChapterReaderDetailsDto>(readerBody)

        val mediaToken = readerDto.mediaToken.orEmpty()
        val defaultTeamId = readerDto.initTeamId ?: readerDto.teams.firstOrNull()?.id ?: ""
        var storageKey = readerDto.storageKey.orEmpty()
        var actualTeamId = defaultTeamId

        if (storageKey.contains("/")) {
            val parts = storageKey.split("/")
            actualTeamId = parts[0]
            storageKey = parts.drop(1).joinToString("/")
        }

        val hasWebP = readerDto.webpPages.isNotEmpty()
        val directory = if (hasWebP) "hq_webp" else "hq"
        val pageFilenames = if (hasWebP) {
            readerDto.webpPages
        } else {
            readerDto.pages.sortedBy { it.order }.map { it.url }
        }

        val tokenParam = if (mediaToken.isNotEmpty()) "?t=$mediaToken" else ""

        return pageFilenames.mapIndexed { index, pageFile ->
            val pageUrl = if (pageFile.startsWith("http")) {
                pageFile
            } else {
                "$baseUrl/uploads/releases/$actualTeamId/$storageKey/$directory/$pageFile$tokenParam"
            }
            Page(index = index, imageUrl = pageUrl)
        }
    }

    override fun imageRequest(page: Page): Request = GET(page.imageUrl!!, headersBuilder().set("Referer", "$baseUrl/").build())

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val mirrorPref = ListPreference(screen.context).apply {
            key = MIRROR_PREF_KEY
            title = MIRROR_PREF_TITLE
            entries = MIRROR_PREF_ENTRY_VALUES
            entryValues = MIRROR_PREF_ENTRY_VALUES
            setDefaultValue(MIRROR_PREF_DEFAULT_VALUE)
            summary = "%s"

            setOnPreferenceChangeListener { _, _ ->
                Toast.makeText(screen.context, RESTART_TACHIYOMI, Toast.LENGTH_LONG).show()
                true
            }
        }
        screen.addPreference(mirrorPref)
    }

    private fun mirrorPref() = when {
        System.getenv("CI") == "true" -> MIRROR_PREF_ENTRY_VALUES.joinToString("#, ")
        else -> preferences.getString(MIRROR_PREF_KEY, MIRROR_PREF_DEFAULT_VALUE)!!
    }

    override val baseUrl by lazy { mirrorPref() }

    override val cdnUrl by lazy { baseUrl }

    private val preferences: SharedPreferences by getPreferencesLazy()
}
