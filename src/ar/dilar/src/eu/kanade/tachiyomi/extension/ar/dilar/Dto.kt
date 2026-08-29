package eu.kanade.tachiyomi.extension.ar.dilar

import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

@Serializable
data class SeriesListDto(
    val series: List<SeriesItemDto> = emptyList(),
    val totalPages: Int = 1,
    val currentPage: Int = 1,
    val totalItems: Int = 0,
)

@Serializable
data class SeriesItemDto(
    val id: String,
    val title: String,
    val cover: String? = null,
    val summary: String? = null,
    @SerialName("story_status") val storyStatus: String? = null,
    @SerialName("translation_status") val translationStatus: String? = null,
    val seriesType: SeriesTypeDto? = null,
) {
    fun toSManga(baseUrl: String) = SManga.create().apply {
        url = "/mangas/$id"
        title = this@SeriesItemDto.title
        thumbnail_url = cover?.let { "$baseUrl/uploads/manga/cover/$id/large_$it" }
    }
}

@Serializable
data class SeriesDetailsDto(
    val id: String,
    val title: String,
    val cover: String? = null,
    val summary: String? = null,
    @SerialName("story_status") val storyStatus: String? = null,
    @SerialName("translation_status") val translationStatus: String? = null,
    val seriesType: SeriesTypeDto? = null,
    val staff: List<StaffMemberDto> = emptyList(),
    val categories: List<CategoryItemDto> = emptyList(),
) {
    fun toSManga(baseUrl: String) = SManga.create().apply {
        title = this@SeriesDetailsDto.title
        thumbnail_url = cover?.let { "$baseUrl/uploads/manga/cover/$id/large_$it" }
        val authors = staff.filter { it.staff?.role.equals("Author", ignoreCase = true) }.map { it.name }
        val artists = staff.filter { it.staff?.role.equals("Artist", ignoreCase = true) }.map { it.name }
        author = authors.joinToString().ifEmpty { staff.map { it.name }.joinToString() }
        artist = artists.joinToString().ifEmpty { author }
        status = when (storyStatus?.lowercase()) {
            "ongoing" -> SManga.ONGOING
            "completed" -> SManga.COMPLETED
            else -> SManga.UNKNOWN
        }
        genre = buildList {
            seriesType?.title?.let { add(it) }
            seriesType?.name?.let { add(it) }
            categories.forEach { add(it.name) }
        }.distinct().joinToString()
        description = buildString {
            summary?.trim()?.ifEmpty { null }?.let { append(it) } ?: append("لا يوجد وصف")
            translationStatus?.let {
                append("\n\nحالة الترجمة: ")
                append(
                    when (it.lowercase()) {
                        "ongoing" -> "مستمرة"
                        "completed" -> "منتهية"
                        else -> it
                    },
                )
            }
        }
    }
}

@Serializable
data class StaffMemberDto(
    val id: String? = null,
    val name: String = "",
    val staff: StaffRoleDto? = null,
)

@Serializable
data class StaffRoleDto(
    val role: String? = null,
)

@Serializable
data class CategoryItemDto(
    val id: String = "",
    val name: String = "",
)

@Serializable
data class SeriesTypeDto(
    val id: String? = null,
    val name: String? = null,
    val title: String? = null,
)

@Serializable
data class QuickSearchGroupDto(
    val `class`: String? = null,
    val data: List<QuickSearchItemDto> = emptyList(),
)

@Serializable
data class QuickSearchItemDto(
    val id: String,
    val title: String? = null,
    val name: String? = null,
    val cover: String? = null,
    val summary: String? = null,
) {
    fun toSManga(baseUrl: String) = SManga.create().apply {
        url = "/mangas/$id"
        title = this@QuickSearchItemDto.title ?: this@QuickSearchItemDto.name ?: ""
        thumbnail_url = cover?.let { "$baseUrl/uploads/manga/cover/$id/large_$it" }
    }
}

@Serializable
data class SeriesChaptersResponseDto(
    val chapters: List<ReleaseItemDto> = emptyList(),
    val totalPages: Int = 1,
    val currentPage: Int = 1,
    val totalItems: Int = 0,
)

@Serializable
data class ReleaseItemDto(
    val id: String,
    val chapter: ReleaseChapterDto? = null,
    val teams: List<ReleaseTeamDto> = emptyList(),
    @SerialName("created_at") val createdAt: String? = null,
) {
    fun toSChapter(): SChapter = SChapter.create().apply {
        url = "/r/$id"
        val chNumStr = chapter?.chapter ?: "0"
        chapter_number = chNumStr.toFloatOrNull() ?: 0f
        val chTitle = chapter?.title?.trim().orEmpty()
        name = if (chTitle.isNotEmpty()) "$chNumStr - $chTitle" else "الفصل $chNumStr"
        scanlator = teams.firstOrNull()?.name
        date_upload = parseIsoDate(createdAt)
    }
}

@Serializable
data class ReleaseChapterDto(
    val id: String? = null,
    val chapter: String? = null,
    val title: String? = null,
)

@Serializable
data class ReleaseTeamDto(
    val id: String? = null,
    val name: String? = null,
)

@Serializable
data class ChapterReaderDetailsDto(
    val id: String = "",
    @SerialName("storage_key") val storageKey: String? = null,
    @SerialName("init_team_id") val initTeamId: String? = null,
    @SerialName("media_token") val mediaToken: String? = null,
    @SerialName("webp_pages") val webpPages: List<String> = emptyList(),
    val pages: List<ChapterPageDto> = emptyList(),
    val teams: List<ReleaseTeamDto> = emptyList(),
    @SerialName("free_pass_required") val freePassRequired: Boolean = false,
)

@Serializable
data class ChapterPageDto(
    val url: String = "",
    val order: Int = 0,
)

@Serializable
data class UnlockFreeResponseDto(
    val token: String? = null,
    @SerialName("expires_at") val expiresAt: Long? = null,
)

private val isoDateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
    timeZone = TimeZone.getTimeZone("UTC")
}

private fun parseIsoDate(dateStr: String?): Long {
    if (dateStr == null) return 0L
    return try {
        isoDateFormat.parse(dateStr)?.time ?: 0L
    } catch (e: Exception) {
        0L
    }
}
