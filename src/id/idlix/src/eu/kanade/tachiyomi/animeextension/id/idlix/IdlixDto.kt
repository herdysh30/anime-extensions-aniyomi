package eu.kanade.tachiyomi.animeextension.id.idlix

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// =========================== Playback ===========================

@Serializable
data class PlayInfoResponse(
    @SerialName("gateToken") val gateToken: String? = null,
    @SerialName("serverNow") val serverNow: Long? = null,
    val kind: String? = null,
)

@Serializable
data class ClaimResponse(
    val kind: String? = null,
    val claim: String? = null,
    @SerialName("redeemUrl") val redeemUrl: String? = null,
    @SerialName("videoId") val videoId: String? = null,
    val title: String? = null,
)

@Serializable
data class RedeemResponse(
    val code: String? = null,
    val url: String? = null,
    @SerialName("videoId") val videoId: String? = null,
    val title: String? = null,
    val subtitles: List<Subtitle>? = null,
)

@Serializable
data class Subtitle(
    val lang: String? = null,
    val label: String? = null,
    // API key is "path" (see HAR: subtitles:[{lang,label,path}])
    @SerialName("path") val url: String? = null,
)

@Serializable
data class ClaimRequestBody(
    @SerialName("gateToken") val gateToken: String,
)

@Serializable
data class RedeemRequestBody(
    val claim: String,
    val mode: String = "browser",
)

// =========================== Homepage ===========================

@Serializable
data class HomepageResponse(
    val above: List<HomepageSection>? = null,
    val below: List<HomepageSection>? = null,
)

@Serializable
data class HomepageSection(
    val type: String? = null,
    val title: String? = null,
    val data: List<HomepageItem>? = null,
)

@Serializable
data class HomepageItem(
    val contentType: String? = null,
    val content: HomepageContent? = null,
    val slug: String? = null,
    val title: String? = null,
    val overview: String? = null,
    val posterPath: String? = null,
    val series: HomepageSeriesRef? = null,
)

@Serializable
data class HomepageSeriesRef(
    val slug: String? = null,
    val title: String? = null,
    val posterPath: String? = null,
)

@Serializable
data class HomepageContent(
    val slug: String? = null,
    val title: String? = null,
    val overview: String? = null,
    val posterPath: String? = null,
    val contentType: String? = null,
)

// =========================== Browse / Search ===========================

@Serializable
data class PaginationDto(
    val page: Int? = null,
    val limit: Int? = null,
    val total: Int? = null,
    @SerialName("totalPages") val totalPages: Int? = null,
)

@Serializable
data class BrowseItem(
    val slug: String? = null,
    val title: String? = null,
    val overview: String? = null,
    val posterPath: String? = null,
    val contentType: String? = null,
)

@Serializable
data class BrowseResponse(
    val data: List<BrowseItem> = emptyList(),
    val pagination: PaginationDto? = null,
)

@Serializable
data class SearchResponse(
    val results: List<BrowseItem> = emptyList(),
    val total: Int? = null,
)

// =========================== Detail ===========================

@Serializable
data class GenreDto(
    val name: String? = null,
)

@Serializable
data class MovieDetailDto(
    val id: String? = null,
    val slug: String? = null,
    val title: String? = null,
    val overview: String? = null,
    val posterPath: String? = null,
    val status: String? = null,
    val genres: List<GenreDto>? = null,
)

@Serializable
data class EpisodeDto(
    val id: String? = null,
    @SerialName("episodeNumber") val episodeNumber: Int? = null,
    val name: String? = null,
    @SerialName("hasVideo") val hasVideo: Boolean? = null,
)

@Serializable
data class SeasonDetailDto(
    @SerialName("seasonNumber") val seasonNumber: Int? = null,
    val episodes: List<EpisodeDto>? = null,
)

@Serializable
data class SeasonDto(
    val id: String? = null,
    @SerialName("seasonNumber") val seasonNumber: Int? = null,
    val name: String? = null,
    @SerialName("episodeCount") val episodeCount: Int? = null,
)

@Serializable
data class SeriesDetailDto(
    val id: String? = null,
    val slug: String? = null,
    val title: String? = null,
    val overview: String? = null,
    val posterPath: String? = null,
    @SerialName("numberOfSeasons") val numberOfSeasons: Int? = null,
    val status: String? = null,
    val genres: List<GenreDto>? = null,
    val seasons: List<SeasonDto>? = null,
)

@Serializable
data class EpisodePageResponse(
    val series: SeriesDetailDto? = null,
    val season: SeasonDetailDto? = null,
    val episode: EpisodeDto? = null,
)
