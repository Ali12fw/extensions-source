package eu.kanade.tachiyomi.extension.ar.mangapro

import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.Log
import android.webkit.CookieManager
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import keiyoushi.utils.extractNextJs
import keiyoushi.utils.extractNextJsRsc
import keiyoushi.utils.firstInstance
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonString
import keiyoushi.utils.tryParse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody
import okhttp3.internal.closeQuietly
import okio.Buffer
import rx.Observable
import tachiyomi.decoder.ImageDecoder
import java.io.IOException
import java.lang.UnsupportedOperationException
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.io.encoding.Base64

class ProChan : HttpSource() {
    override val name = "ProComic"
    override val lang = "ar"
    private val domain = "procomic.pro"
    override val baseUrl = "https://$domain"
    override val supportsLatest = true
    override val versionId = 5

    private val cookieManager by lazy { CookieManager.getInstance() }

    private fun webViewCookieInterceptor(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url
        val urlHost = url.host
        val urlString = url.toString()

        val primaryCookies = try {
            cookieManager.getCookie(urlString)
        } catch (_: Exception) {
            null
        }
        val proCookies = try {
            cookieManager.getCookie("https://procomic.pro/")
        } catch (_: Exception) {
            null
        }
        val chanCookies = try {
            cookieManager.getCookie("https://prochan.pro/")
        } catch (_: Exception) {
            null
        }
        val netCookies = try {
            cookieManager.getCookie("https://procomic.net/")
        } catch (_: Exception) {
            null
        }
        val appCookies = try {
            cookieManager.getCookie("https://app.procomic.pro/")
        } catch (_: Exception) {
            null
        }

        // Also fetch cookies for cdn subdomains if this is a CDN request
        val cdnCookies = if (urlHost.startsWith("cdn")) {
            try {
                cookieManager.getCookie("https://$urlHost/")
            } catch (_: Exception) {
                null
            }
        } else {
            null
        }

        val requestCookieHeader = request.header("Cookie")
        val cookieMap = LinkedHashMap<String, String>()

        requestCookieHeader?.split("; ")?.forEach { pair ->
            val parts = pair.split("=", limit = 2)
            if (parts.size == 2) cookieMap[parts[0].trim()] = parts[1].trim()
        }

        sequenceOf(proCookies, chanCookies, netCookies, appCookies, cdnCookies, primaryCookies).filterNotNull().forEach { cookieStr ->
            cookieStr.split("; ").forEach { pair ->
                val parts = pair.split("=", limit = 2)
                if (parts.size == 2) {
                    val key = parts[0].trim()
                    val value = parts[1].trim()
                    if (key.isNotEmpty() && value.isNotEmpty()) {
                        cookieMap[key] = value
                    }
                }
            }
        }

        if (!cookieMap.containsKey("safe_browsing")) {
            cookieMap["safe_browsing"] = "off"
        }
        if (!cookieMap.containsKey("language")) {
            cookieMap["language"] = "ar"
        }

        val mergedCookieHeader = cookieMap.entries.joinToString("; ") { "${it.key}=${it.value}" }
        val newRequest = request.newBuilder()
            .header("Cookie", mergedCookieHeader)
            .build()

        val response = chain.proceed(newRequest)

        val setCookies = response.headers("Set-Cookie")
        if (setCookies.isNotEmpty()) {
            for (cookie in setCookies) {
                try {
                    cookieManager.setCookie(urlString, cookie)
                } catch (_: Exception) { }
            }
        }

        return response
    }

    override val client = network.cloudflareClient.newBuilder()
        .addInterceptor(::scrambledImageInterceptor)
        .addInterceptor(::webViewCookieInterceptor)
        .build()

    override fun headersBuilder() = super.headersBuilder()
        .set("Referer", "$baseUrl/")
        .set("Origin", baseUrl)

    private val rscHeaders = headersBuilder()
        .set("rsc", "1")
        .build()

    override fun popularMangaRequest(page: Int): Request = GET("$baseUrl/api/public/manga?page=$page&limit=20", headers)

    override fun popularMangaParse(response: Response): MangasPage {
        val data = response.parseAs<MetaData<BrowseManga>>()
        val mangas = data.data.asSequence()
            .filter { it.type in SUPPORTED_TYPES }
            .map { it.toSManga() }
            .toList()

        return MangasPage(mangas, data.meta.hasNextPage())
    }

    override fun fetchPopularManga(page: Int): Observable<MangasPage> = client.newCall(popularMangaRequest(page))
        .asObservableSuccess()
        .map(::popularMangaParse)

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/api/public/content/latest-updates?page=$page", headers)

    override fun latestUpdatesParse(response: Response): MangasPage {
        val data = response.parseAs<Data<List<LatestUpdate>>>().data
        val mangas = data.asSequence()
            .filter { it.type in SUPPORTED_TYPES }
            .distinctBy { "${it.type}/${it.mangaId}" }
            .map { update ->
                SManga.create().apply {
                    url = "/ar/series/${update.type}/${update.mangaId}/${update.mangaSlug}"
                    title = update.mangaTitle
                    thumbnail_url = update.coverImage.toImageUrl(update.cdn)
                }
            }
            .toList()

        val hasNextPage = data.size >= 18
        return MangasPage(mangas, hasNextPage)
    }

    override fun fetchLatestUpdates(page: Int): Observable<MangasPage> = client.newCall(latestUpdatesRequest(page))
        .asObservableSuccess()
        .map(::latestUpdatesParse)

    private val pageNumber = ConcurrentHashMap<String, Int>()

    private fun searchKey(query: String, filters: FilterList): String {
        val filterPart = filters.filterIsInstance<Filter<*>>()
            .joinToString("|") { it.state.toString() }
        return "$query::$filterPart"
    }

    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> {
        if (query.startsWith("https://")) {
            val url = query.toHttpUrl()
            val path = url.pathSegments
            val seriesIndex = path.indexOf("series")
            if (url.host == domain && seriesIndex >= 0 && path.size > seriesIndex + 3) {
                val type = path[seriesIndex + 1]
                if (type !in SUPPORTED_TYPES) {
                    throw Exception("Ù†ÙˆØ¹ ØºÙŠØ± Ù…Ø¯Ø¹ÙˆÙ…")
                }
                val mangaId = path[seriesIndex + 2]
                val slug = path[seriesIndex + 3]

                val manga = SManga.create().apply {
                    this@apply.url = "/ar/series/$type/$mangaId/$slug"
                }

                return fetchMangaDetails(manga).map {
                    MangasPage(listOf(it), false)
                }
            } else {
                throw Exception("Ø±Ø§Ø¨Ø· ØºÙŠØ± Ù…Ø¯Ø¹ÙˆÙ…")
            }
        }

        val key = searchKey(query, filters)
        if (page == 1) {
            pageNumber[key] = 1
        }

        return client.newCall(searchMangaRequest(pageNumber[key]!!, query, filters))
            .asObservableSuccess()
            .map { response ->
                val statusFilter = filters.firstInstance<StatusFilter>().selected
                val genreFilter = filters.firstInstance<GenreFilter>()
                val tagFilter = filters.firstInstance<TagFilter>()

                val data = response.parseAs<MetaData<BrowseManga>>()
                val mangas = data.data.asSequence()
                    .filter { manga ->
                        manga.type in SUPPORTED_TYPES
                    }
                    .filter { manga ->
                        statusFilter == null || manga.progress == statusFilter
                    }
                    .filter { manga ->
                        genreFilter.included.isEmpty() ||
                            manga.metadata.genres.containsAll(genreFilter.included)
                    }
                    .filter { manga ->
                        genreFilter.excluded.none { it in manga.metadata.genres }
                    }
                    .filter { manga ->
                        tagFilter.included.isEmpty() ||
                            manga.metadata.tags.containsAll(tagFilter.included)
                    }
                    .filter { manga ->
                        tagFilter.excluded.none { it in manga.metadata.tags }
                    }
                    .map { it.toSManga() }
                    .toList()

                MangasPage(mangas, data.meta.hasNextPage())
            }
            .flatMap {
                if (it.mangas.isEmpty() && it.hasNextPage) {
                    pageNumber[key] = pageNumber[key]!! + 1
                    fetchSearchManga(pageNumber[key]!!, query, filters)
                } else {
                    if (!it.hasNextPage) pageNumber.remove(key)
                    Observable.just(it)
                }
            }
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val selectedType = filters.firstInstance<TypeFilter>().selected ?: "manga"
        val url = "$baseUrl/api/public/$selectedType".toHttpUrl().newBuilder().apply {
            addQueryParameter("limit", "20")
            addQueryParameter("page", page.toString())
            query.takeIf(String::isNotBlank)?.also { q ->
                addQueryParameter("search", q)
            }
        }.build()

        return GET(url, headers)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val data = response.parseAs<MetaData<BrowseManga>>()
        val mangas = data.data.asSequence()
            .filter { it.type in SUPPORTED_TYPES }
            .map { it.toSManga() }
            .toList()

        return MangasPage(mangas, data.meta.hasNextPage())
    }

    override fun getFilterList() = FilterList(
        TypeFilter(),
        SortFilter(),
        YearFilter(),
        StatusFilter(),
        GenreFilter(),
        TagFilter(),
    )

    override fun mangaDetailsRequest(manga: SManga): Request = GET(getMangaUrl(manga), rscHeaders)

    override fun getMangaUrl(manga: SManga): String = "$baseUrl${manga.url.localizedPath()}"

    private data class NextJsResult<T>(
        val data: T,
        val effectiveUrl: okhttp3.HttpUrl,
    )

    private inline fun <reified T> extractNextJsFollowingMove(
        response: Response,
    ): NextJsResult<T> {
        val reqUrl = response.request.url
        val contentType = response.header("Content-Type") ?: ""
        val locationHeader = response.header("Location") ?: ""

        val bodyString = try {
            response.peekBody(1024 * 1024).string()
        } catch (_: Exception) {
            ""
        }

        val firstData: T? = when {
            "text/x-component" in contentType -> bodyString.extractNextJsRsc<T>()
            "text/html" in contentType -> org.jsoup.Jsoup.parse(bodyString, reqUrl.toString()).extractNextJs<T>()
            else -> null
        }

        if (firstData != null) {
            return NextJsResult(firstData, response.request.url)
        }

        val rawTargetUrl = if (locationHeader.isNotBlank()) {
            locationHeader
        } else {
            parseMovedBounceTargetUrl(bodyString)
                ?: throw IOException("ProComic: expected ${T::class.simpleName}; no moved-series target")
        }

        val parsedUrl = rawTargetUrl.toHttpUrlOrNull()
            ?: throw IOException("ProComic: invalid moved target URL: $rawTargetUrl")

        if (parsedUrl.scheme != "http" && parsedUrl.scheme != "https") {
            throw IOException("ProComic: unsupported scheme in moved target URL: ${parsedUrl.scheme}")
        }

        if (parsedUrl.host !in ALLOWED_DOMAINS) {
            throw IOException("ProComic: untrusted host in moved target URL: ${parsedUrl.host}")
        }

        val movedRequest = response.request.newBuilder()
            .url(parsedUrl)
            .build()

        val (firstDataMoved, firstUrl) = client.newCall(movedRequest).execute().use { movedResponse ->
            val movedBodyString = try {
                movedResponse.peekBody(1024 * 1024).string()
            } catch (_: Exception) {
                ""
            }
            val movedContentType = movedResponse.header("Content-Type") ?: ""
            val movedExtracted: T? = if (movedResponse.isSuccessful) {
                when {
                    "text/x-component" in movedContentType -> movedBodyString.extractNextJsRsc<T>()
                    "text/html" in movedContentType -> org.jsoup.Jsoup.parse(movedBodyString, movedResponse.request.url.toString()).extractNextJs<T>()
                    else -> null
                }
            } else {
                null
            }

            if (movedExtracted != null) {
                movedExtracted to movedResponse.request.url
            } else {
                null
            }
        } ?: (null to null)

        if (firstDataMoved != null && firstUrl != null) {
            return NextJsResult(firstDataMoved, firstUrl)
        }

        val pathSegments = parsedUrl.pathSegments
        val seriesIdx = pathSegments.indexOf("series")
        if (seriesIdx >= 0 && pathSegments.size > seriesIdx + 3) {
            val currentType = pathSegments[seriesIdx + 1]

            for (fallbackType in SUPPORTED_TYPES) {
                if (fallbackType == currentType) continue
                val fallbackUrl = parsedUrl.newBuilder()
                    .setPathSegment(seriesIdx + 1, fallbackType)
                    .build()
                val fallbackRequest = response.request.newBuilder()
                    .url(fallbackUrl)
                    .build()

                val (fbData, fbUrl) = client.newCall(fallbackRequest).execute().use { fbResp ->
                    val fbBodyString = try {
                        fbResp.peekBody(1024 * 1024).string()
                    } catch (_: Exception) {
                        ""
                    }
                    val fbContentType = fbResp.header("Content-Type") ?: ""
                    val fbExtracted: T? = if (fbResp.isSuccessful) {
                        when {
                            "text/x-component" in fbContentType -> fbBodyString.extractNextJsRsc<T>()
                            "text/html" in fbContentType -> org.jsoup.Jsoup.parse(fbBodyString, fbResp.request.url.toString()).extractNextJs<T>()
                            else -> null
                        }
                    } else {
                        null
                    }

                    if (fbExtracted != null) {
                        fbExtracted to fbResp.request.url
                    } else {
                        null
                    }
                } ?: (null to null)

                if (fbData != null && fbUrl != null) {
                    return NextJsResult(fbData, fbUrl)
                }
            }
        }

        throw IOException("ProComic moved-series response missing ${T::class.simpleName}")
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val manga = extractNextJsFollowingMove<Series>(response).data.series

        return SManga.create().apply {
            url = "/ar/series/${manga.type}/${manga.id}/${manga.slug}"
            title = manga.title
            artist = manga.metadata.artist.joinToString()
            author = manga.metadata.author.joinToString()
            description = buildString {
                manga.description?.also { append(it.trim(), "\n\n") }
                buildList {
                    addAll(manga.metadata.altTitles)
                    manga.metadata.originalTitle?.also { add(it) }
                }.also {
                    if (it.isNotEmpty()) {
                        append("Ø¹Ù†Ø§ÙˆÙŠÙ† Ø¨Ø¯ÙŠÙ„Ø©\n")
                        it.forEach { title ->
                            append("- ", title, "\n")
                        }
                        append("\n")
                    }
                }
            }.trim()
            genre = buildList {
                add(manga.type.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() })
                manga.metadata.year?.also { add(it) }
                manga.metadata.origin?.also { origin ->
                    add(origin.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() })
                }
                when (manga.type) {
                    "manga" -> add("Ù…Ø§Ù†Ø¬Ø§")
                    "manhwa" -> add("Ù…Ø§Ù†Ù‡Ø§")
                    "manhua" -> add("Ù…Ø§Ù†Ù‡ÙˆØ§")
                }
                if (manga.metadata.genres.isNotEmpty()) {
                    val genreMap = genres.associate { it.second to it.first }
                    manga.metadata.genres.mapTo(this) { genreMap[it] ?: it }
                }
                if (manga.metadata.tags.isNotEmpty()) {
                    val tagsMap = tags.associate { it.second to it.first }
                    manga.metadata.tags.mapTo(this) { tagsMap[it] ?: it }
                }
            }.joinToString()
            status = when (manga.progress?.trim()) {
                "Ù…Ø³ØªÙ…Ø±" -> SManga.ONGOING
                "Ù…ÙƒØªÙ…Ù„" -> SManga.COMPLETED
                "Ù…ØªÙˆÙ‚Ù" -> SManga.ON_HIATUS
                else -> SManga.UNKNOWN
            }
            thumbnail_url = (manga.coverImageApp?.desktop ?: manga.metadata.coverImage)?.let {
                if (it.startsWith("/")) {
                    manga.cdn?.let { cdn ->
                        "https://$cdn.$domain$it"
                    }
                } else {
                    it
                }
            }
            initialized = true
        }
    }

    override fun chapterListRequest(manga: SManga) = GET(getMangaUrl(manga), rscHeaders)

    override fun chapterListParse(response: Response): List<SChapter> {
        val result = extractNextJsFollowingMove<InitialChapters>(response)
        val data = result.data
        val chapters = data.initialChapters.toMutableList()
        val size = chapters.size
        var page = 2
        val sourceRouteUrl = generateSequence(response) { it.priorResponse }
            .map { it.request.url }
            .firstOrNull { url ->
                val sIdx = url.pathSegments.indexOf("series")
                sIdx >= 0 && url.pathSegments.size > sIdx + 3
            } ?: result.effectiveUrl
        val path = sourceRouteUrl.pathSegments
        val seriesIndex = path.indexOf("series")
        val type = if (seriesIndex >= 0 && path.size > seriesIndex + 1) path[seriesIndex + 1] else "manga"
        val id = if (seriesIndex >= 0 && path.size > seriesIndex + 2) path[seriesIndex + 2] else ""
        val slug = if (seriesIndex >= 0 && path.size > seriesIndex + 3) path[seriesIndex + 3] else ""
        val reqBaseUrl = "https://${result.effectiveUrl.host}"

        if (id.isNotBlank()) {
            while (data.totalChapters > chapters.size) {
                val request = GET("$reqBaseUrl/api/public/$type/$id/chapters?page=${page++}&limit=$size&order=desc", headers)
                val nextChapters = client.newCall(request).execute()
                    .also {
                        if (!it.isSuccessful) {
                            it.close()
                            throw Exception("HTTP ${it.code}")
                        }
                    }
                    .parseAs<Data<List<Chapter>>>()

                chapters.addAll(nextChapters.data)
            }

            countViews(id)
        }

        return chapters
            .filter { it.language == "AR" }
            .map { chapter ->
                SChapter.create().apply {
                    url = "/ar/series/$type/$id/$slug/${chapter.id}/${chapter.number}"
                    name = buildString {
                        append("\u200F") // rtl marker

                        if (chapter.isLocked) {
                            append("🔒 ")
                        }

                        append("الفصل ")
                        append(
                            chapter.number.toFloatOrNull()?.toString()?.substringBefore(".0") ?: chapter.number,
                        )

                        chapter.title?.trim()?.takeIf { it.isNotBlank() }?.let { trimmedTitle ->
                            if (trimmedTitle != chapter.number.trim() && trimmedTitle != chapter.number) {
                                append(" \u200F- ")
                                append(trimmedTitle)
                            }
                        }
                    }
                    scanlator = chapter.uploader ?: "\u200B"
                    chapter_number = chapter.number.toFloatOrNull() ?: 0f
                    date_upload = dateFormat.tryParse(chapter.createdAt)
                }
            }
            .sortedByDescending { it.chapter_number }
    }

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT)

    override fun pageListRequest(chapter: SChapter): Request = GET(getChapterUrl(chapter), rscHeaders)

    override fun getChapterUrl(chapter: SChapter): String {
        val url = if (chapter.url.startsWith("{")) {
            chapter.url.parseAs<ChapterUrl>().url
        } else {
            chapter.url
        }

        return "$baseUrl${url.localizedPath()}"
    }

    override fun pageListParse(response: Response): List<Page> {
        val peekedString = try {
            response.peekBody(1024 * 1024).string()
        } catch (_: Exception) {
            ""
        }

        val contentType = response.header("Content-Type") ?: ""
        var imageData = if ("text/x-component" in contentType) {
            peekedString.extractNextJsRsc<Images>()
        } else {
            org.jsoup.Jsoup.parse(peekedString, response.request.url.toString()).extractNextJs<Images>()
        }

        var reqUrl = response.request.url

        if (imageData == null) {
            val movedTarget = parseMovedBounceTargetUrl(peekedString)
            if (!movedTarget.isNullOrBlank()) {
                val parsedUrl = movedTarget.toHttpUrlOrNull()
                if (parsedUrl != null && parsedUrl.host in ALLOWED_DOMAINS) {
                    val movedReq = response.request.newBuilder().url(parsedUrl).build()
                    client.newCall(movedReq).execute().use { movedResp ->
                        if (movedResp.isSuccessful) {
                            reqUrl = movedResp.request.url
                            val movedBody = try {
                                movedResp.peekBody(1024 * 1024).string()
                            } catch (_: Exception) {
                                ""
                            }
                            val movedContentType = movedResp.header("Content-Type") ?: ""
                            imageData = if ("text/x-component" in movedContentType) {
                                movedBody.extractNextJsRsc<Images>()
                            } else {
                                org.jsoup.Jsoup.parse(movedBody, reqUrl.toString()).extractNextJs<Images>()
                            }
                        }
                    }
                }
            }
        }

        if (imageData == null) {
            val coins = peekedString.extractNextJsRsc<Coins>()?.coins
            val lowerPeeked = peekedString.lowercase()
            when {
                coins != null && coins > 0 -> throw Exception("ÙØµÙ„ Ù…Ø¯ÙÙˆØ¹ ÙŠØªØ·Ù„Ø¨ Ù†Ù‚Ø§Ø· (Coins)")
                "lockedbycoins\":true" in lowerPeeked -> throw Exception("ÙØµÙ„ ÙŠØªØ·Ù„Ø¨ Ù†Ù‚Ø§Ø· (Coins)")
                "lockedbyshortlink\":true" in lowerPeeked || "shortlink" in lowerPeeked || "hasshortlink\":true" in lowerPeeked -> {
                    throw Exception("[KOMIKKU_AUTO_WEBVIEW] ÙØµÙ„ ÙŠØªØ·Ù„Ø¨ ØªØ®Ø·ÙŠ Ø±Ø§Ø¨Ø· Ù…Ø®ØªØµØ± (Shortlink). Ø§Ø¶ØºØ· Ø¹Ù„Ù‰ 'ÙØªØ­ ÙÙŠ WebView' Ø£Ø¯Ù†Ø§Ù‡ Ù„ØªØ®Ø·ÙŠÙ‡")
                }
                "lockedbyexclusive\":true" in lowerPeeked -> throw Exception("ÙØµÙ„ Ø­ØµØ±ÙŠ (Exclusive)")
                "lockedforever\":true" in lowerPeeked -> throw Exception("ÙØµÙ„ Ù…ØºÙ„Ù‚ ØªÙ…Ø§Ù…Ø§Ù‹")
                "blocked\":true" in lowerPeeked || "safebrowsingblocked\":true" in lowerPeeked || "Ø§Ù„ØªØµÙØ­ Ø§Ù„Ø¢Ù…Ù†" in peekedString -> {
                    throw Exception("ÙŠØ±Ø¬Ù‰ Ø§Ø¶ØºØ· Ø¹Ù„Ù‰ 'ÙØªØ­ ÙÙŠ WebView' ÙˆØªØ³Ø¬ÙŠÙ„ Ø§Ù„Ø¯Ø®ÙˆÙ„ ÙÙŠ Ø­Ø³Ø§Ø¨ ProComic Ù„ÙØªØ­ Ù‡Ø°Ø§ Ø§Ù„ÙØµÙ„")
                }
                "locked" in lowerPeeked || "lock" in lowerPeeked -> {
                    throw Exception("ÙØµÙ„ ÙŠØªØ·Ù„Ø¨ ØªØ®Ø·ÙŠ Ø±Ø§Ø¨Ø· Ù…Ø®ØªØµØ± (Shortlink) Ø£Ùˆ ØªØ³Ø¬ÙŠÙ„ Ø§Ù„Ø¯Ø®ÙˆÙ„. Ø§Ø¶ØºØ· Ø¹Ù„Ù‰ 'ÙØªØ­ ÙÙŠ WebView' Ù„ØªØ®Ø·ÙŠÙ‡")
                }
                else -> throw Exception("ØªØ¹Ø°Ø± Ø§Ø³ØªØ®Ø±Ø§Ø¬ ØµÙØ­Ø§Øª Ø§Ù„ÙØµÙ„ (Parser Error)")
            }
        }

        val seriesRouteUrl = generateSequence(response) { it.priorResponse }
            .map { it.request.url }
            .firstOrNull { url ->
                val seriesIndex = url.pathSegments.indexOf("series")
                seriesIndex >= 0 && url.pathSegments.size > seriesIndex + 4
            }

        val (seriesId, chapterId) = if (seriesRouteUrl != null) {
            val path = seriesRouteUrl.pathSegments
            val seriesIndex = path.indexOf("series")
            val sId = path.getOrNull(seriesIndex + 2).orEmpty()
            val cId = path.getOrNull(seriesIndex + 4).orEmpty()
            sId to cId
        } else {
            val path = reqUrl.pathSegments
            val chapterIndex = path.indexOf("chapter")
            val segment = if (chapterIndex >= 0 && path.size > chapterIndex + 1) {
                path[chapterIndex + 1]
            } else {
                path.lastOrNull().orEmpty()
            }
            val cId = segment.substringAfterLast("-").takeIf { it.isNotEmpty() && it.all(Char::isDigit) }.orEmpty()
            "" to cId
        }

        if (chapterId.isBlank() && imageData.deferredMedia != null) {
            throw IOException("ProComic chapter identifier not found for deferred media")
        }

        val images = imageData.appImages
            .mapNotNull { it.desktop ?: it.mobile }
            .ifEmpty { imageData.images }
            .toMutableList()
        val maps = mutableListOf<ScrambledData>()

        imageData.deferredMedia?.let { deferredMedia ->
            if (deferredMedia.requireTurnstile || deferredMedia.turnstileMode in TURNSTILE_MODES) {
                throw IOException(
                    "[KOMIKKU_AUTO_WEBVIEW] ProComic requires browser verification for this chapter. " +
                        "Open it in WebView, complete the challenge, then retry.",
                )
            }

            val effectiveOrigin = getEffectiveOriginUrl(reqUrl)
            val deferredUrl = effectiveOrigin.newBuilder()
                .addPathSegment("chapter-deferred-media")
                .addPathSegment(chapterId)
                .addQueryParameter("token", deferredMedia.token)
                .addQueryParameter("split", deferredMedia.splitIndex.toString())
                .build()

            val deferredHeaders = headers.newBuilder()
                .removeAll("Origin")
                .set("Accept", "application/json")
                .set("Referer", reqUrl.toString())
                .build()
            val deferredImages = client.newCall(GET(deferredUrl, deferredHeaders)).execute().use { deferredResponse ->
                val body = deferredResponse.body.string()
                if (!deferredResponse.isSuccessful) {
                    throw deferredMediaHttpException(deferredResponse.code, body)
                }

                val parsed = try {
                    body.parseAs<DeferredImagesResponse>()
                } catch (error: Exception) {
                    throw IOException("ProComic deferred media returned an invalid HTTP 200 response", error)
                }
                val payload = parsed.data ?: DeferredImages(parsed.images, parsed.maps)
                if (payload.images.isEmpty() && payload.maps.isEmpty()) {
                    throw IOException("ProComic deferred media returned no images or maps")
                }
                payload
            }

            images.addAll(deferredImages.images)
            maps.addAll(deferredImages.maps)
        }

        countViews(seriesId, chapterId)

        val chapterUrl = (seriesRouteUrl ?: reqUrl).toString()
        val pages = mutableListOf<Page>()

        images.mapIndexedTo(pages) { index, imageUrl ->
            Page(index, chapterUrl, imageUrl)
        }
        val firstScrambledPageIndex = pages.size
        maps.mapIndexedTo(pages) { index, scrambledData ->
            val pageIndex = firstScrambledPageIndex + index
            Page(
                pageIndex,
                chapterUrl,
                "http://$SCRAMBLED_IMAGE_HOST/?pageIndex=$pageIndex#${scrambledData.toJsonString()}",
            )
        }

        return pages
    }

    private fun deferredMediaHttpException(status: Int, body: String): IOException {
        val error = try {
            body.parseAs<DeferredApiError>()
        } catch (_: Exception) {
            null
        }
        val nested = error?.data
        val errorCode = (error?.errorCode ?: nested?.errorCode).sanitizeApiText()
        val message = (error?.message ?: nested?.message ?: error?.error ?: nested?.error).sanitizeApiText()
        val minimumVersion = error?.minimumVersionCode ?: error?.minVersionCode ?: nested?.minimumVersionCode ?: nested?.minVersionCode
        val turnstileRequired = error?.requireTurnstile == true ||
            nested?.requireTurnstile == true ||
            error?.turnstileMode in TURNSTILE_MODES ||
            nested?.turnstileMode in TURNSTILE_MODES ||
            errorCode?.contains("turnstile", ignoreCase = true) == true ||
            message?.contains("turnstile", ignoreCase = true) == true

        if (turnstileRequired) {
            return IOException(
                "[KOMIKKU_AUTO_WEBVIEW] ProComic requires browser verification for this chapter. " +
                    "Open it in WebView, complete the challenge, then retry.",
            )
        }

        val details = listOfNotNull(
            errorCode?.let { "code=$it" },
            message,
            minimumVersion?.let { "minimumVersion=$it" },
        ).joinToString(", ")
        val suffix = details.takeIf(String::isNotBlank)?.let { ": $it" }.orEmpty()

        return when (status) {
            401, 403 -> IOException(
                "ProComic deferred media HTTP $status$suffix. " +
                    "Open the chapter in WebView and confirm that you are signed in, then retry.",
            )
            426 -> IOException("ProComic deferred media HTTP 426$suffix")
            else -> IOException("ProComic deferred media HTTP $status$suffix")
        }
    }

    private fun String?.sanitizeApiText(): String? = this
        ?.replace(Regex("\\s+"), " ")
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?.take(MAX_API_ERROR_LENGTH)

    private fun isProtectedCdnHost(host: String): Boolean = host.startsWith("cdn") && (
        host.endsWith(".prochan.pro") ||
            host.endsWith(".procomic.pro") ||
            host.endsWith(".procomic.net")
        )

    private fun signCdnImageUrlIfNeeded(imgUrl: HttpUrl, chapterUrl: String): HttpUrl {
        val host = imgUrl.host
        val isProtected = isProtectedCdnHost(host)
        if (!isProtected) {
            return imgUrl
        }
        if (imgUrl.queryParameter("token") != null && imgUrl.queryParameter("expires") != null) {
            return imgUrl
        }

        val effectiveOrigin = getEffectiveOriginUrl(chapterUrl)
        val effectiveChapterUrl = chapterUrl.toHttpUrlOrNull()?.newBuilder()
            ?.host(effectiveOrigin.host)
            ?.build()?.toString() ?: chapterUrl

        val payload = Url(url = imgUrl.toString()).toJsonString().toRequestBody(JSON_MEDIA_TYPE)
        val signHeaders = headersBuilder()
            .set("Origin", effectiveOrigin.toString().removeSuffix("/"))
            .set("Sec-Fetch-Site", "same-origin")
            .set("Referer", effectiveChapterUrl)
            .build()
        val signUrl = effectiveOrigin.newBuilder()
            .addPathSegment("api")
            .addPathSegment("cdn-image")
            .addPathSegment("sign")
            .build()

        val signRequest = POST(signUrl.toString(), signHeaders, payload)
        val response = client.newCall(signRequest).execute()

        val code = response.code
        if (!response.isSuccessful) {
            response.closeQuietly()
            throw IOException("HTTP $code signing CDN image")
        }

        val token = response.parseAs<Token>()
        response.closeQuietly()

        return imgUrl.newBuilder()
            .setQueryParameter("token", token.token)
            .setQueryParameter("expires", token.expires.toString())
            .build()
    }

    private fun proxyCdnImageUrlIfNeeded(imgUrl: HttpUrl, chapterUrl: String): HttpUrl {
        if (!isProtectedCdnHost(imgUrl.host)) {
            return imgUrl
        }

        val signedUrl = signCdnImageUrlIfNeeded(imgUrl, chapterUrl)
        val token = signedUrl.queryParameter("token")
            ?: throw IOException("ProComic CDN signature did not include a token")
        val expires = signedUrl.queryParameter("expires")
            ?: throw IOException("ProComic CDN signature did not include an expiry")
        val effectiveOrigin = getEffectiveOriginUrl(chapterUrl)

        return effectiveOrigin.newBuilder()
            .addPathSegment("api")
            .addPathSegment("cdn-image")
            .addQueryParameter("url", imgUrl.toString())
            .addQueryParameter("token", token)
            .addQueryParameter("expires", expires)
            .build()
    }

    override fun imageRequest(page: Page): Request {
        val rawUrl = page.imageUrl!!.toHttpUrl()
        val signedUrl = proxyCdnImageUrlIfNeeded(rawUrl, page.url)

        val headers = headersBuilder()
            .set("Referer", page.url)
            .build()

        return GET(signedUrl, headers)
    }

    private fun scrambledImageInterceptor(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url
        if (url.host != SCRAMBLED_IMAGE_HOST) {
            return chain.proceed(request)
        }

        val chapterUrl = request.header("Referer") ?: return chain.proceed(request)
        val chapterPath = chapterUrl.toHttpUrl().pathSegments
        val seriesIndex = chapterPath.indexOf("series")
        val cdn = when {
            seriesIndex >= 0 && chapterPath.size > seriesIndex + 1 -> when (chapterPath[seriesIndex + 1]) {
                "manga" -> "cdn1"
                "manhua" -> "cdn2"
                else -> "cdn3"
            }
            "manga" in chapterUrl -> "cdn1"
            "manhua" in chapterUrl -> "cdn2"
            else -> "cdn3"
        }

        val scrambledData = url.fragment?.parseAs<ScrambledData>() ?: return chain.proceed(request)
        val scrambledImage = when (scrambledData) {
            is ScrambledImage -> scrambledData
            is ScrambledImageToken -> decodeScrambledImageToken(
                scrambledData,
                chapterUrl,
                url.queryParameter("pageIndex")?.toIntOrNull() ?: 0,
                cdn,
            )
        }

        val (puzzleMode, layout) = scrambledImage.mode.split("_", limit = 2)

        require(scrambledImage.dim.size >= 2) { "Invalid dim: ${scrambledImage.dim}" }

        val width = scrambledImage.dim[0]
        val height = scrambledImage.dim[1]

        val orderedPieces = scrambledImage.order.map { scrambledImage.pieces[it] }
        val pieceBitmaps = runBlocking {
            orderedPieces.map { pieceUrl ->
                async(Dispatchers.IO.limitedParallelism(2)) {
                    var imgUrl = if (pieceUrl.startsWith("/")) {
                        "https://$cdn.$domain$pieceUrl"
                    } else {
                        pieceUrl
                    }.toHttpUrl()
                    imgUrl = signCdnImageUrlIfNeeded(imgUrl, chapterUrl)
                    val pieceRequest = request.newBuilder().url(imgUrl).build()
                    val response = client.newCall(pieceRequest).await()
                    if (!response.isSuccessful) {
                        val code = response.code
                        response.close()
                        throw IOException("HTTP $code downloading scrambled piece $imgUrl")
                    }
                    response.body.use { body ->
                        // use Tachiyomi ImageDecoder because android.graphics.BitmapFactory doesn't handle avif
                        val decoder = ImageDecoder.newInstance(body.byteStream())
                            ?: throw Exception("Failed to create decoder")
                        try {
                            decoder.decode() ?: throw Exception("Failed to decode piece")
                        } finally {
                            decoder.recycle()
                        }
                    }
                }
            }.awaitAll()
        }

        val resultBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(resultBitmap)

        try {
            when (puzzleMode) {
                "vertical" -> {
                    var x = 0f
                    for (bitmap in pieceBitmaps) {
                        canvas.drawBitmap(bitmap, x, 0f, null)
                        x += bitmap.width
                    }
                }
                "grid" -> {
                    val (cols, rows) = layout.split('x', limit = 2).map { it.toInt() }
                    var y = 0f
                    for (r in 0 until rows) {
                        var x = 0f
                        var maxHeightInRow = 0f
                        for (c in 0 until cols) {
                            val index = r * cols + c
                            if (index < pieceBitmaps.size) {
                                val bitmap = pieceBitmaps[index]
                                canvas.drawBitmap(bitmap, x, y, null)
                                x += bitmap.width
                                maxHeightInRow = maxOf(maxHeightInRow, bitmap.height.toFloat())
                            }
                        }
                        y += maxHeightInRow
                    }
                }
                else -> throw IOException("Unknown puzzle mode: $puzzleMode")
            }

            val buffer = Buffer().apply {
                resultBitmap.compress(Bitmap.CompressFormat.JPEG, 90, outputStream())
            }
            return Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(buffer.asResponseBody("image/jpg".toMediaType(), buffer.size))
                .build()
        } finally {
            pieceBitmaps.forEach { it.recycle() }
            resultBitmap.recycle()
        }
    }

    private val sessionKey = ConcurrentHashMap<Int, Pair<String, Long>>()
    private val sessionKeyLock = Any()

    private fun decodeScrambledImageToken(
        data: ScrambledImageToken,
        chapterUrl: String,
        pageIndex: Int,
        cdn: String,
    ): ScrambledImage {
        val value = String(urlSafeBase64(data.token), Charsets.UTF_8)
            .parseAs<ScrambledImageTokenValue>()

        if (value.m == "browser_session" && value.v == 3) {
            val payload = ProxyPlanRequest(
                token = data.token,
                method = data.method,
                cdnPath = cdn,
                pageIndex = pageIndex,
            ).toJsonString().toRequestBody(JSON_MEDIA_TYPE)
            val effectiveOrigin = getEffectiveOriginUrl(chapterUrl)
            val proxyPlanUrl = effectiveOrigin.newBuilder()
                .addPathSegment("chapter-map-proxy-plan")
                .addPathSegment(value.cid.toString())
                .build()
            val request = POST(
                proxyPlanUrl.toString(),
                headersBuilder().set("Referer", chapterUrl).build(),
                payload,
            )

            return client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("Failed to prepare protected page, HTTP ${response.code}")
                }
                response.parseAs<Data<ProxyPlan>>().data.map
            }
        }

        val iv = urlSafeBase64(value.iv)
        val tag = urlSafeBase64(value.tag)
        val encryptedData = urlSafeBase64(value.data)

        val key = when (value.m) {
            "browser" if value.v == 2 -> {
                val hash = MessageDigest.getInstance("SHA-256")
                    .digest(
                        "prochan-browser-map:2e6f9a1c4d8b7e3f0a5c9d2b6e1f4a8c7d3b0e6a9f2c5d8b1e4a7c0d3f6b9e2:${value.cid}"
                            .toByteArray(Charsets.UTF_8),
                    )
                SecretKeySpec(hash, "AES")
            }
            // Untested, couldn't find a chapter which uses this, possibly for paid chapters?
            "browser_session" if value.v == 3 -> synchronized(sessionKeyLock) {
                val time = System.currentTimeMillis()
                val key = sessionKey[value.cid]?.takeIf { it.second > time }?.first ?: run {
                    val effectiveOrigin = getEffectiveOriginUrl(chapterUrl)
                    val sessionKeyUrl = effectiveOrigin.newBuilder()
                        .addPathSegment("chapter-map-session-key")
                        .addPathSegment(value.cid.toString())
                        .build()
                    val request = GET(sessionKeyUrl.toString(), headers)
                    val response = client.newCall(request).execute().parseAs<Data<Key>>()

                    sessionKey[value.cid] = response.data.key to (time + 120000)

                    response.data.key
                }

                SecretKeySpec(urlSafeBase64(key), "AES")
            }
            else -> throw Exception("Unknown method")
        }

        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            val spec = GCMParameterSpec(128, iv)

            init(Cipher.DECRYPT_MODE, key, spec)
        }

        val decryptedBytes = cipher.doFinal(encryptedData + tag)
        return String(decryptedBytes, Charsets.UTF_8).parseAs()
    }

    private fun urlSafeBase64(data: String) = Base64.UrlSafe
        .withPadding(Base64.PaddingOption.PRESENT_OPTIONAL)
        .decode(data)

    private fun getEffectiveOriginUrl(url: Any): HttpUrl {
        val httpUrl = when (url) {
            is HttpUrl -> url
            is String -> url.toHttpUrlOrNull()
            else -> null
        } ?: baseUrl.toHttpUrl()
        return "https://${httpUrl.host}".toHttpUrl()
    }

    private fun countViews(seriesId: String, chapterId: String? = null) {
        val seriesIdInt = seriesId.toIntOrNull() ?: return
        val userAgent = headers["User-Agent"] ?: "Mozilla/5.0"
        val payload = ViewsDto(
            chapterId = chapterId?.toIntOrNull(),
            contentId = seriesIdInt,
            deviceType = when {
                MOBILE_REGEX.containsMatchIn(userAgent) -> "mobile"
                TABLES_REGEX.containsMatchIn(userAgent) -> "tablet"
                else -> "desktop"
            },
            surface = when {
                chapterId == null -> "series"
                else -> "chapter"
            },
        ).toJsonString().toRequestBody(JSON_MEDIA_TYPE)

        client.newCall(POST("$baseUrl/api/views", headers, payload))
            .enqueue(
                object : Callback {
                    override fun onResponse(call: Call, response: Response) {
                        if (!response.isSuccessful) {
                            Log.e(name, "Failed to count views, HTTP ${response.code}")
                        }
                        response.closeQuietly()
                    }
                    override fun onFailure(call: Call, e: okio.IOException) {
                        Log.e(name, "Failed to count views", e)
                    }
                },
            )
    }

    private fun BrowseManga.toSManga() = SManga.create().apply {
        url = "/ar/series/${this@toSManga.type}/${this@toSManga.id}/${this@toSManga.slug}"
        title = this@toSManga.title
        thumbnail_url = (
            this@toSManga.coverImageApp?.desktop
                ?: this@toSManga.coverImage
                ?: this@toSManga.thumbnail
            ).toImageUrl(this@toSManga.cdn)
    }

    private fun String?.toImageUrl(cdn: String?): String? = this?.let { imageUrl ->
        if (imageUrl.startsWith("/")) {
            cdn?.let { "https://$it.$domain$imageUrl" }
        } else {
            imageUrl
        }
    }

    private fun String.localizedPath(): String = when {
        startsWith("/ar/series/") -> this
        startsWith("/series/") -> "/ar$this"
        else -> this
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()
}

private val SUPPORTED_TYPES = setOf("manga", "manhwa", "manhua")
private val ALLOWED_DOMAINS = setOf("procomic.net", "procomic.pro", "www.procomic.net", "www.procomic.pro", "prochan.pro", "www.prochan.pro")
private const val SCRAMBLED_IMAGE_HOST = "127.0.0.1"
private const val MAX_API_ERROR_LENGTH = 240
private val JSON_MEDIA_TYPE = "application/json".toMediaType()
private val TURNSTILE_MODES = setOf("hidden", "visible")
private val MOBILE_REGEX = Regex("mobile|android|iphone|ipad|ipod", RegexOption.IGNORE_CASE)
private val TABLES_REGEX = Regex("tablet", RegexOption.IGNORE_CASE)
private val MOVED_RSC_TARGET_URL_REGEX = Regex(""""targetUrl"\s*:\s*"(https?:\/\/[^"]+)"""")
private val MOVED_HOST_REGEX = Regex("""var\s+h\s*=\s*\[(.*?)\]\.join\(['"]['"]\)""")
private val MOVED_PATH_REGEX = Regex("""var\s+u\s*=\s*['"]https:\/\/['"]\s*\+\s*h\s*\+\s*['"]([^'"]+)['"]""")

private fun parseMovedBounceTargetUrl(bodyString: String): String? {
    MOVED_RSC_TARGET_URL_REGEX.find(bodyString)?.groupValues?.get(1)?.let { return it }

    val hostMatch = MOVED_HOST_REGEX.find(bodyString) ?: return null
    val pathMatch = MOVED_PATH_REGEX.find(bodyString) ?: return null

    val rawHostItems = hostMatch.groupValues[1]
    val host = rawHostItems.split(",")
        .map { it.trim().removeSurrounding("'").removeSurrounding("\"") }
        .joinToString("")

    val path = pathMatch.groupValues[1]
    if (host.isBlank() || path.isBlank()) return null

    return "https://$host$path"
}
