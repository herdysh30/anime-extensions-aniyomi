package eu.kanade.tachiyomi.animeextension.id.idlix

import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import aniyomi.lib.m3u8server.M3u8Integration
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.AnimeHttpLegacySource
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import keiyoushi.utils.post
import keiyoushi.utils.toJsonRequestBody
import kotlinx.coroutines.delay
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import java.util.Base64

class Idlix :
    AnimeHttpLegacySource(),
    ConfigurableAnimeSource {

    override fun animeDetailsParse(response: Response): SAnime = throw UnsupportedOperationException()

    override val name = "IDLIX"
    override val baseUrl = "https://z2.idlixku.com"
    override val lang = "id"
    override val supportsLatest = true

    private val preferences by getPreferencesLazy()
    private val m3u8 = M3u8Integration(client)
    private val episodeUuidCache = mutableMapOf<String, String>()

    private companion object {
        const val PREROLL_WAIT_MS = 15_000L
        const val REDEEM_CACHE_MARGIN_MS = 5 * 60_000L
        const val IMG_BASE = "https://image.tmdb.org/t/p/w500"
        const val PREF_QUALITY_KEY = "pref_quality"
        const val PREF_QUALITY_DEFAULT = "auto"
        val QUALITY_ENTRIES = arrayOf("Auto (Terbaik)", "1080p", "720p", "480p", "360p")
        val QUALITY_VALUES = arrayOf("auto", "1080", "720", "480", "360")

        val SORT_NAMES = arrayOf("Latest", "Popularity", "Rating", "Release", "Views", "Created At")
        val SORT_TO_API = mapOf(
            "Latest" to "latest",
            "Popularity" to "popularity",
            "Rating" to "rating",
            "Release" to "release",
            "Views" to "views",
            "Created At" to "createdAt",
        )

        // slug -> display name
        val GENRE_ENTRIES = arrayOf(
            "action" to "Action",
            "adventure" to "Adventure",
            "animation" to "Animation",
            "comedy" to "Comedy",
            "crime" to "Crime",
            "documentary" to "Documentary",
            "drama" to "Drama",
            "family" to "Family",
            "fantasy" to "Fantasy",
            "history" to "History",
            "horror" to "Horror",
            "music" to "Music",
            "mystery" to "Mystery",
            "romance" to "Romance",
            "science-fiction" to "Science Fiction",
            "tv-movie" to "TV Movie",
            "thriller" to "Thriller",
            "war" to "War",
            "western" to "Western",
        )

        val COUNTRY_ENTRIES = arrayOf(
            "AR" to "Argentina", "AU" to "Australia", "AT" to "Austria",
            "BE" to "Belgium", "BR" to "Brazil", "CA" to "Canada",
            "CN" to "China", "CZ" to "Czech Republic", "DK" to "Denmark",
            "FI" to "Finland", "FR" to "France", "DE" to "Germany",
            "HK" to "Hong Kong", "IN" to "India", "ID" to "Indonesia",
            "IE" to "Ireland", "IL" to "Israel", "IT" to "Italy",
            "JP" to "Japan", "MX" to "Mexico", "NL" to "Netherlands",
            "NZ" to "New Zealand", "NO" to "Norway", "PH" to "Philippines",
            "PL" to "Poland", "RU" to "Russia", "ZA" to "South Africa",
            "KR" to "South Korea", "ES" to "Spain", "SE" to "Sweden",
            "CH" to "Switzerland", "TW" to "Taiwan", "TH" to "Thailand",
            "TR" to "Turkey", "GB" to "United Kingdom", "US" to "United States",
        )

        val YEARS = (1920..2026).map { it.toString() }.toTypedArray()

        val NETWORK_ENTRIES = arrayOf(
            "netflix" to "Netflix",
            "hbo" to "HBO",
            "prime-video" to "Prime Video",
            "disney-plus" to "Disney+",
            "apple-tv-plus" to "Apple TV+",
        )

        // name -> raw query params (from the site's drama rails)
        val DRAMA_ENTRIES = arrayOf(
            "Anime" to "genre=animation&country=JP&language=ja",
            "Korea" to "genre=drama&country=KR&language=ko",
            "China" to "genre=drama&country=CN&language=zh",
            "Thailand" to "genre=drama&country=TH&language=th",
            "Japan (J-Drama)" to "genre=drama&excludeGenre=animation&country=JP&language=ja",
        )
    }

    // =========================== Filters ===========================

    private class TypeFilter : AnimeFilter.Select<String>("Type", arrayOf("All", "Movie", "Series"))
    private class SortFilter : AnimeFilter.Select<String>("Sort", SORT_NAMES)
    private class GenreFilter : AnimeFilter.Select<String>("Genre", arrayOf("All") + GENRE_ENTRIES.map { it.second })
    private class CountryFilter : AnimeFilter.Select<String>("Country", arrayOf("All") + COUNTRY_ENTRIES.map { it.second })
    private class YearFilter : AnimeFilter.Select<String>("Year", arrayOf("All") + YEARS)
    private class NetworkFilter : AnimeFilter.Select<String>("Network", arrayOf("All") + NETWORK_ENTRIES.map { it.second })
    private class DramaFilter : AnimeFilter.Select<String>("Drama", arrayOf("All") + DRAMA_ENTRIES.map { it.first })

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("Use text search for global search"),
        AnimeFilter.Header("Filters apply when text search is empty"),
        TypeFilter(),
        SortFilter(),
        GenreFilter(),
        CountryFilter(),
        YearFilter(),
        NetworkFilter(),
        DramaFilter(),
    )

    override fun headersBuilder() = super.headersBuilder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36")
        .add("Referer", "$baseUrl/")

    // =========================== Popular ============================

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/api/homepage", headers)

    override fun popularAnimeParse(response: Response): AnimesPage {
        val hp = runCatching { response.parseAs<HomepageResponse>() }.getOrNull()
        val items = (hp?.above.orEmpty() + hp?.below.orEmpty())
            .flatMap { it.data.orEmpty() }
            .mapNotNull { item ->
                val c = item.content
                when {
                    c != null -> {
                        // featured items wrap content in nested object
                        itemToSAnime(c.contentType ?: item.contentType, c.slug, c.title, c.overview, c.posterPath)
                    }
                    item.contentType == "episode" -> {
                        // recently-added-episodes items carry series info nested
                        val s = item.series
                        itemToSAnime("series", s?.slug, s?.title, null, s?.posterPath)
                    }
                    else -> {
                        // flat items: trending, network, collection, latest
                        itemToSAnime(item.contentType, item.slug, item.title, item.overview, item.posterPath)
                    }
                }
            }
            .distinctBy { it.url }
        return AnimesPage(items, false)
    }

    // =========================== Latest =============================

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/api/browse?page=$page&limit=36&sort=createdAt", headers)

    override fun latestUpdatesParse(response: Response): AnimesPage = parseBrowseResponse(response)

    // =========================== Search =============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        if (query.isNotBlank()) {
            return GET("$baseUrl/api/search?q=$query&page=$page&limit=36", headers)
        }
        return buildBrowseRequest(page, filters)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val url = response.request.url.toString()
        return if ("/api/search" in url) {
            val sr = runCatching { response.parseAs<SearchResponse>() }.getOrNull()
            val items = sr?.results.orEmpty().mapNotNull { itemToSAnime(it.contentType, it.slug, it.title, it.overview, it.posterPath) }
            AnimesPage(items, false)
        } else {
            parseBrowseResponse(response)
        }
    }

    // =========================== Detail =============================

    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val slug = anime.url.removePrefix("/movie/").removePrefix("/series/").removeSuffix("/")
        val isMovie = anime.url.startsWith("/movie/")
        val apiPath = if (isMovie) "/api/movies/$slug" else "/api/series/$slug"
        val response = client.newCall(GET("$baseUrl$apiPath", headers)).awaitSuccess()

        return if (isMovie) {
            parseMovieDetail(response, slug, anime.url)
        } else {
            parseSeriesDetail(response, slug, anime.url)
        }
    }

    private fun parseMovieDetail(response: Response, slug: String, url: String): SAnime {
        val detail = runCatching { response.parseAs<MovieDetailDto>() }.getOrNull()
        return SAnime.create().apply {
            this.url = url
            title = detail?.title ?: slug
            description = detail?.overview
            thumbnail_url = detail?.posterPath?.let { "$IMG_BASE$it" }
            genre = detail?.genres?.mapNotNull { it.name }?.joinToString(", ")
            status = if (detail?.status == "Released") SAnime.COMPLETED else SAnime.ONGOING
            initialized = true
        }
    }

    private fun parseSeriesDetail(response: Response, slug: String, url: String): SAnime {
        val detail = runCatching { response.parseAs<SeriesDetailDto>() }.getOrNull()
        return SAnime.create().apply {
            this.url = url
            title = detail?.title ?: slug
            description = detail?.overview
            thumbnail_url = detail?.posterPath?.let { "$IMG_BASE$it" }
            genre = detail?.genres?.mapNotNull { it.name }?.joinToString(", ")
            status = if (detail?.status == "Ended") SAnime.COMPLETED else SAnime.ONGOING
            initialized = true
        }
    }

    // =========================== Episodes ===========================

    override fun episodeListParse(response: Response): List<SEpisode> = throw UnsupportedOperationException()

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        if (anime.url.startsWith("/movie/")) {
            val slug = anime.url.removePrefix("/movie/").removeSuffix("/")
            return listOf(
                SEpisode.create().apply {
                    name = slug.replace("-", " ").replaceFirstChar { it.uppercase() }
                    episode_number = 1F
                    this.url = "${anime.url}?play=1"
                    scanlator = "IDLIX"
                },
            )
        }

        val slug = anime.url.removePrefix("/series/").removeSuffix("/")
        val detail = runCatching {
            client.newCall(GET("$baseUrl/api/series/$slug", headers)).awaitSuccess()
                .parseAs<SeriesDetailDto>()
        }.getOrNull()
        val numSeasons = detail?.numberOfSeasons ?: 1
        val episodes = mutableListOf<SEpisode>()
        var epCounter = 1F

        for (season in 1..numSeasons) {
            val seasonUrl = "/api/series/$slug/season/$season"
            val seasonResp = try {
                client.newCall(GET("$baseUrl$seasonUrl", headers)).awaitSuccess()
                    .parseAs<EpisodePageResponse>()
            } catch (_: Exception) {
                continue
            }
            val seasonEpisodes = seasonResp.season?.episodes.orEmpty()
                .map { ep ->
                    val epKey = "$slug/s$season/e${ep.episodeNumber}"
                    ep.id?.let { episodeUuidCache[epKey] = it }
                    SEpisode.create().apply {
                        // API episode titles are often not season/episode info;
                        // use S01E01-style naming from season + episode numbers
                        name = "S%02dE%02d".format(season, ep.episodeNumber ?: 0)
                        episode_number = epCounter++
                        this.url = "/series/$slug/season/$season/episode/${ep.episodeNumber}?play=1"
                        scanlator = "IDLIX"
                    }
                }
            episodes.addAll(seasonEpisodes)
        }

        return episodes.sortedBy { it.episode_number }
    }

    // =========================== Video ==============================

    // playUrl -> (validUntilEpochMs, resolved videos). The claim/redeem flow
    // costs ~15 s of preroll wait per open; the resolved proxied URLs embed a
    // JWT whose `exp` marks exactly when they stop working, so caching them
    // (with a safety margin) makes re-opening the same episode instant.
    private val videoCache = mutableMapOf<String, Pair<Long, List<Video>>>()

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val playUrl = episode.url.removeSuffix("?play=1")

        videoCache[playUrl]?.let { (validUntil, videos) ->
            if (System.currentTimeMillis() < validUntil && videos.isNotEmpty()) {
                return videos
            }
            videoCache.remove(playUrl)
        }

        val videos = resolveVideoList(playUrl) ?: return emptyList()
        // m3u8Url's ?t= JWT carries the expiry; fall back to 5 minutes.
        val token = videos.firstOrNull()?.videoUrl?.toHttpUrlOrNull()?.queryParameter("t")
        val validUntil = token?.let { decodeJwtClaim(it, "e") }
            ?.minus(REDEEM_CACHE_MARGIN_MS)
            ?: (System.currentTimeMillis() + REDEEM_CACHE_MARGIN_MS)
        videoCache[playUrl] = validUntil to videos
        return videos
    }

    private suspend fun resolveVideoList(playUrl: String): List<Video>? {
        val uuid = resolveEpisodeUuid(playUrl) ?: return null

        // 1. play-info -> gateToken (endpoint differs for movie vs episode)
        val playInfoPath = if (playUrl.startsWith("/movie/")) "/api/watch/play-info/movie/$uuid" else "/api/watch/play-info/episode/$uuid"
        val playInfo = runCatching {
            client.newCall(GET("$baseUrl$playInfoPath", headers))
                .awaitSuccess()
                .parseAs<PlayInfoResponse>()
        }.getOrNull() ?: return null
        val gateToken = playInfo.gateToken ?: return null

        // 2. wait out preroll
        val unlockAt = decodeUnlockAt(gateToken) ?: playInfo.serverNow?.plus(PREROLL_WAIT_MS)
        if (unlockAt != null) {
            val waitMs = unlockAt - System.currentTimeMillis()
            if (waitMs > 0) delay(waitMs + 500)
        }

        // 3. claim session
        val claim = runCatching {
            client.post(
                "$baseUrl/api/watch/session/claim",
                watchHeaders(playUrl),
                ClaimRequestBody(gateToken).toJsonRequestBody(),
            ).parseAs<ClaimResponse>()
        }.getOrNull() ?: return null
        val claimToken = claim.claim ?: return null
        val redeemUrl = claim.redeemUrl ?: return null

        // 4. redeem -> HLS master
        val redeem = runCatching {
            client.post(
                redeemUrl,
                watchHeaders(playUrl),
                RedeemRequestBody(claimToken).toJsonRequestBody(),
            ).parseAs<RedeemResponse>()
        }.getOrNull() ?: return null
        val m3u8Url = redeem.url ?: return null

        // 5. proxy — one Video per master-playlist variant so the player's
        // quality picker offers each rendition (1080p / 720p …); falls back
        // to the master playlist itself when parsing fails.
        val token = m3u8Url.toHttpUrlOrNull()?.queryParameter("t")
        val videoHeaders = watchHeaders(playUrl)
        val subtitleTracks = redeem.subtitles.orEmpty().mapNotNull { sub ->
            sub.url?.let { url ->
                val subUrl = if (token != null && "?t=" !in url) "$url?t=$token&pm=browser" else url
                Track(subUrl, sub.label ?: sub.lang ?: "Subtitle")
            }
        }

        val masterContent = runCatching {
            client.newCall(GET(m3u8Url, videoHeaders))
                .awaitSuccess()
                .body.string()
        }.getOrNull()

        val audioGroupUrl = masterContent?.let { parseHlsAudioGroup(it, m3u8Url) }
        val prefQuality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT) ?: PREF_QUALITY_DEFAULT

        if (audioGroupUrl != null) {
            val videoUrl = if (prefQuality == "auto") m3u8Url else "$m3u8Url#pv=$prefQuality"
            val qualityLabel = if (prefQuality == "auto") "IDLIX" else "IDLIX ($prefQuality)"
            val video = Video(
                videoUrl = videoUrl,
                url = videoUrl,
                quality = qualityLabel,
                subtitleTracks = subtitleTracks,
                audioTracks = emptyList(),
                headers = videoHeaders,
            )
            return m3u8.processVideoList(listOf(video), token)
        }

        // Muxed master (movie): variants carry their own audio, splitting
        // per-variant gives the player's quality picker one entry each.
        val rawVariants = masterContent
            ?.let { content -> parseHlsVariants(content, m3u8Url) }
            .orEmpty()

        if (rawVariants.isEmpty()) {
            val video = Video(
                videoUrl = m3u8Url,
                url = m3u8Url,
                quality = "IDLIX",
                subtitleTracks = subtitleTracks,
                audioTracks = emptyList(),
                headers = videoHeaders,
            )
            return m3u8.processVideoList(listOf(video), token)
        }

        val formattedVariants = rawVariants.map { (url, height, bandwidth) ->
            val quality = formatQuality(height, bandwidth)
            val heightVal = if (height > 0) height else quality.removeSuffix("p").toIntOrNull() ?: 0
            Triple(url, quality, heightVal)
        }

        val targetHeight = prefQuality.toIntOrNull()
        val sortedVariants = if (targetHeight != null && targetHeight > 0) {
            // Put the user's preferred quality first, then sort the rest descending
            formattedVariants.sortedWith(
                compareByDescending<Triple<String, String, Int>> { it.third == targetHeight }
                    .thenByDescending { it.third },
            )
        } else {
            formattedVariants.sortedByDescending { it.third }
        }

        // Lead with the master playlist marked preferred: backends that pick
        // the "best video" (Aniyomi's ExoPlayer route via HosterLoader)
        // then get the FULL master. Each entry below it carries a `#pv=`
        // hint, so the proxy collapses the master to that exact rendition —
        // the ExoPlayer quality picker then lists EVERY quality (one player
        // track per hint), same as the MPV quality sheet.
        val qualityVideos = sortedVariants.map { (url, quality, height) ->
            val hintedUrl = "$m3u8Url#pv=${if (height > 0) height else quality.removeSuffix("p")}"
            Video(
                videoUrl = hintedUrl,
                url = hintedUrl,
                quality = quality,
                subtitleTracks = subtitleTracks,
                audioTracks = emptyList(),
                headers = videoHeaders,
            )
        }

        val autoVideo = Video(
            videoUrl = m3u8Url,
            url = m3u8Url,
            quality = "IDLIX",
            subtitleTracks = subtitleTracks,
            audioTracks = emptyList(),
            headers = videoHeaders,
        ).copy(preferred = true)

        val videoList: List<Video> = ArrayList<Video>().apply {
            add(autoVideo)
            addAll(qualityVideos)
        }

        return m3u8.processVideoList(
            videoList,
            token,
        )
    }

    private fun formatQuality(height: Int, bandwidth: Int): String {
        if (height > 0) return "${height}p"
        return when {
            bandwidth >= 2_800_000 -> "1080p"
            bandwidth >= 1_400_000 -> "720p"
            bandwidth >= 700_000 -> "480p"
            bandwidth > 0 -> "360p"
            else -> "Video"
        }
    }

    /**
     * Extracts the audio group URI from an `#EXT-X-MEDIA:TYPE=AUDIO,URI="…"`
     * line, resolved against the master URL. Non-null means the stream is
     * demuxed (video variants + separate audio playlist) and the master must
     * not be split into per-variant Videos or audio is lost.
     */
    private fun parseHlsAudioGroup(content: String, masterUrl: String): String? {
        val base = masterUrl.toHttpUrlOrNull() ?: return null
        val line = content.lineSequence().firstOrNull {
            it.startsWith("#EXT-X-MEDIA:") && "TYPE=AUDIO" in it
        } ?: return null
        val uri = Regex("""URI="([^"]*)""").find(line)?.groupValues?.get(1) ?: return null
        return base.resolve(uri.trim())?.toString()
    }

    /**
     * Extracts video variants (renditions) from a master playlist:
     * `#EXT-X-STREAM-INF:…RESOLUTION=WxH…` followed by the child playlist
     * URL line. Returns (absoluteUrl, height, bandwidth) triples; height is
     * 0 when the playlist omits RESOLUTION (label then falls back to kbps).
     */
    private fun parseHlsVariants(content: String, masterUrl: String): List<Triple<String, Int, Int>> {
        val base = masterUrl.toHttpUrlOrNull() ?: return emptyList()
        val variants = mutableListOf<Triple<String, Int, Int>>()
        var height: Int? = null
        var bandwidth: Int? = null
        content.lines().forEach { line ->
            when {
                line.startsWith("#EXT-X-STREAM-INF:") -> {
                    height = Regex("""RESOLUTION=\d+x(\d+)""").find(line)?.groupValues?.get(1)?.toIntOrNull()
                    bandwidth = Regex("""BANDWIDTH=(\d+)""").find(line)?.groupValues?.get(1)?.toIntOrNull()
                }
                line.startsWith("#") || line.isBlank() -> Unit
                height != null || bandwidth != null -> {
                    base.resolve(line.trim())?.toString()?.let { url ->
                        variants += Triple(url, height ?: 0, bandwidth ?: 0)
                    }
                    height = null
                    bandwidth = null
                }
            }
        }
        return variants
    }

    private fun watchHeaders(refererPath: String): Headers = headersBuilder()
        .add("Origin", baseUrl)
        .add("Referer", "$baseUrl$refererPath")
        .add("Sec-Fetch-Dest", "empty")
        .add("Sec-Fetch-Mode", "cors")
        .add("Sec-Fetch-Site", "same-origin")
        .build()

    private suspend fun resolveEpisodeUuid(playUrl: String): String? {
        if (playUrl.startsWith("/movie/")) {
            val slug = playUrl.removePrefix("/movie/").removeSuffix("/")
            return runCatching {
                client.newCall(GET("$baseUrl/api/movies/$slug", headers)).awaitSuccess()
                    .parseAs<MovieDetailDto>().id
            }.getOrNull()
        }
        val m = Regex("/series/([^/]+)/season/(\\d+)/episode/(\\d+)").find(playUrl) ?: return null
        val slug = m.groupValues[1]
        val season = m.groupValues[2]
        val episode = m.groupValues[3]
        val cacheKey = "$slug/s$season/e$episode"
        episodeUuidCache[cacheKey]?.let { return it }
        return runCatching {
            client.newCall(GET("$baseUrl/api/series/$slug/season/$season/episode/$episode", headers))
                .awaitSuccess()
                .parseAs<EpisodePageResponse>().episode?.id
        }.getOrNull()
    }

    private fun decodeUnlockAt(gateToken: String): Long? = runCatching {
        val clean = gateToken.substringBefore(".").replace('-', '+').replace('_', '/')
        val pad = (4 - clean.length % 4) % 4
        val raw = Base64.getDecoder().decode(clean + "=".repeat(pad))
        val text = String(raw, Charsets.UTF_8)
        Regex("\"unlockAt\":(\\d+)").find(text)?.groupValues?.get(1)?.toLongOrNull()
    }.getOrNull()

    /**
     * Numeric claim from the payload of a `header.payload.signature` JWT.
     * Returns null for missing/unparseable claims.
     */
    private fun decodeJwtClaim(token: String, claim: String): Long? = runCatching {
        val clean = token.substringBefore(".").replace('-', '+').replace('_', '/')
        val pad = (4 - clean.length % 4) % 4
        val raw = Base64.getDecoder().decode(clean + "=".repeat(pad))
        val text = String(raw, Charsets.UTF_8)
        Regex("\"$claim\":(\\d+)").find(text)?.groupValues?.get(1)?.toLongOrNull()
    }.getOrNull()

    // =========================== Helpers ============================

    private fun itemToSAnime(contentType: String?, slug: String?, title: String?, overview: String?, posterPath: String?, defaultType: String? = null): SAnime? {
        val s = slug ?: return null
        val type = when (contentType) {
            "movie" -> "movie"
            "tv_series", "series" -> "series"
            else -> defaultType ?: "series"
        }
        return SAnime.create().apply {
            url = "/$type/$s"
            this.title = title ?: s
            thumbnail_url = posterPath?.let { "$IMG_BASE$it" }
            this.description = overview
        }
    }

    private fun parseBrowseResponse(response: Response): AnimesPage {
        val br = runCatching { response.parseAs<BrowseResponse>() }.getOrNull()
        val url = response.request.url.toString()
        val defaultType = when {
            "/api/series" in url -> "series"
            else -> null
        }
        val items = br?.data.orEmpty().mapNotNull { itemToSAnime(it.contentType, it.slug, it.title, it.overview, it.posterPath, defaultType) }
        val hasNext = (br?.pagination?.page ?: 0) < (br?.pagination?.totalPages ?: 0)
        return AnimesPage(items, hasNext)
    }

    private fun buildBrowseRequest(page: Int, filters: AnimeFilterList): Request {
        @Suppress("UNCHECKED_CAST")
        val f = filters as List<AnimeFilter<*>>
        val typeIdx = f.getOrNull(2)?.state as? Int ?: 0
        val sortIdx = f.getOrNull(3)?.state as? Int ?: 0
        val genreIdx = f.getOrNull(4)?.state as? Int ?: 0
        val countryIdx = f.getOrNull(5)?.state as? Int ?: 0
        val yearIdx = f.getOrNull(6)?.state as? Int ?: 0
        val networkIdx = f.getOrNull(7)?.state as? Int ?: 0
        val dramaIdx = f.getOrNull(8)?.state as? Int ?: 0

        val sort = SORT_TO_API[SORT_NAMES.getOrElse(sortIdx) { "Latest" }] ?: "latest"
        val params = mutableListOf("page=$page", "limit=36", "sort=$sort")

        if (genreIdx > 0) {
            GENRE_ENTRIES.getOrNull(genreIdx - 1)?.let { params.add("genre=${it.first}") }
        }
        if (countryIdx > 0) {
            COUNTRY_ENTRIES.getOrNull(countryIdx - 1)?.let { params.add("country=${it.first}") }
        }
        if (yearIdx > 0) {
            YEARS.getOrNull(yearIdx - 1)?.let { params.add("year=$it") }
        }
        if (networkIdx > 0) {
            NETWORK_ENTRIES.getOrNull(networkIdx - 1)?.let { params.add("network=${it.first}") }
        }
        if (dramaIdx > 0) {
            // drama presets carry their own genre/country/language params
            DRAMA_ENTRIES.getOrNull(dramaIdx - 1)?.let { params.addAll(it.second.split("&")) }
        }

        val endpoint = when (typeIdx) {
            1 -> "/api/movies"
            2 -> "/api/series"
            else -> "/api/browse"
        }
        return GET("$baseUrl$endpoint?${params.joinToString("&")}", headers)
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = "Kualitas Video Preferensi"
            entries = QUALITY_ENTRIES
            entryValues = QUALITY_VALUES
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putString(key, newValue as String).commit()
            }
        }.also(screen::addPreference)
    }
}
