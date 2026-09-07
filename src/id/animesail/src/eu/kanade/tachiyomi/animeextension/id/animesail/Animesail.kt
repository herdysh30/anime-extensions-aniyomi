package eu.kanade.tachiyomi.animeextension.id.animesail

import android.app.Application
import android.util.Base64
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebSettings
import aniyomi.lib.cloudflareinterceptor.CloudflareInterceptor
import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.lib.jsunpacker.JsUnpacker
import keiyoushi.utils.ParsedAnimeHttpLegacySource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.injectLazy
import java.util.concurrent.ConcurrentHashMap

class Animesail : ParsedAnimeHttpLegacySource() {

    override val name = "Animesail"

    override val baseUrl = "https://v1.animesail.xyz"

    override val lang = "id"

    override val supportsLatest = true

    private val context: Application by injectLazy()

    // cf_clearance dari WebView terikat ke UA; pakai UA WebView nyata untuk semua request.
    private val webViewUA: String by lazy { WebSettings.getDefaultUserAgent(context) }

    override val client: OkHttpClient = network.client.newBuilder()
        .addInterceptor { chain ->
            val request = chain.request()
            // Suntik cookie (termasuk cf_clearance) yang diisi lewat WebView situs.
            val withCookies = CookieManager.getInstance().getCookie(request.url.toString())
                ?.takeIf { it.isNotEmpty() }
                ?.let { request.newBuilder().header("Cookie", it).build() }
                ?: request
            chain.proceed(withCookies.newBuilder().header("User-Agent", webViewUA).build())
        }
        .addInterceptor(CloudflareInterceptor(network.client, webViewUA))
        .build()

    private val resolver by lazy { LokalWebViewResolver(headers) }
    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    // 4 (bukan 8): burst WebView paralel memicu CF 403#3 rate-limit;
    // kecepatan buka-ulang sudah ditutup oleh videoCache.
    private val resolveSemaphore = Semaphore(4)

    /** Cache hasil resolve per episode (key: URL episode, TTL 10 menit). */
    private val videoCache = ConcurrentHashMap<String, Pair<Long, List<Video>>>()

    // ============================== Popular ===============================

    private val popularHost = "https://animesail.154999000.xyz"

    override fun popularAnimeRequest(page: Int) = GET("$popularHost/wp-content/cache/popular.txt", headers)

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animes = document.select("li").map { element ->
            SAnime.create().apply {
                element.selectFirst("h2 a.series")?.also { link ->
                    title = link.text()
                    setUrlWithoutDomain(link.absUrl("href"))
                }
                thumbnail_url = element.selectFirst("img")?.absUrl("src")
            }
        }
        return AnimesPage(animes, false)
    }

    override fun popularAnimeSelector() = throw UnsupportedOperationException()
    override fun popularAnimeFromElement(element: Element) = throw UnsupportedOperationException()
    override fun popularAnimeNextPageSelector() = throw UnsupportedOperationException()

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int) = GET(if (page == 1) "$baseUrl/rilisan-anime-terbaru/" else "$baseUrl/rilisan-anime-terbaru/page/$page/", headers)

    override fun latestUpdatesSelector() = "article.bs"

    override fun latestUpdatesFromElement(element: Element) = SAnime.create().apply {
        val link = element.selectFirst("a")!!
        setUrlWithoutDomain(link.absUrl("href"))
        title = element.selectFirst("h2")?.text() ?: link.attr("title").ifEmpty { link.text() }
        thumbnail_url = element.selectFirst("img")?.absUrl("src")
    }

    // Halaman rilisan memakai pager div.hpage (a.r = Next), bukan a.next.page-numbers
    override fun latestUpdatesNextPageSelector() = "a.next.page-numbers, div.hpage a.r"

    // =============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val genreFilter = filters.filterIsInstance<GenreFilter>().firstOrNull()
        val typeFilter = filters.filterIsInstance<TypeFilter>().firstOrNull()

        // Genre arsip: /genres/<slug>/page/N/
        genreFilter?.takeIf { it.state != 0 }?.let {
            val slug = GENRES[it.state - 1].second
            val path = if (page > 1) "/genres/$slug/page/$page/" else "/genres/$slug/"
            return GET(baseUrl + path, headers)
        }

        // Tipe: movie / anime / donghua rilisan terbaru
        typeFilter?.takeIf { it.state != 0 }?.let {
            val path = when (it.state) {
                1 -> "/movie-terbaru"
                2 -> "/rilisan-anime-terbaru"
                else -> "/rilisan-donghua-terbaru"
            }
            return GET(if (page > 1) "$baseUrl$path/page/$page/" else "$baseUrl$path/", headers)
        }
        // Default: pencarian keyword WP
        return if (query.isBlank()) {
            GET("$baseUrl/rilisan-anime-terbaru" + if (page > 1) "/page/$page/" else "/", headers)
        } else {
            GET("$baseUrl/page/$page/?s=${query.trim()}", headers)
        }
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        // Arsip genre/movie pakai article.bsz, rilisan (Tipe Anime/Donghua, search kosong) pakai article.bs
        val animes = document.select("article.bsz, article.bs").map { element ->
            SAnime.create().apply {
                val link = element.selectFirst("a")!!
                setUrlWithoutDomain(link.absUrl("href"))
                title = link.attr("title").ifEmpty {
                    element.selectFirst("h2")?.text() ?: link.text()
                }
                thumbnail_url = element.selectFirst("img")?.absUrl("src")
            }
        }
        val hasNext = document.selectFirst("a.next.page-numbers, div.hpage a[href*='/page/']") != null
        return AnimesPage(animes, hasNext)
    }

    override fun searchAnimeFromElement(element: Element) = throw UnsupportedOperationException()
    override fun searchAnimeSelector() = throw UnsupportedOperationException()
    override fun searchAnimeNextPageSelector() = throw UnsupportedOperationException()

    // =============================== Filters ==============================

    override fun getFilterList() = AnimeFilterList(
        AnimeFilter.Header("Filter mengikuti struktur situs (tanpa sort/quality)"),
        TypeFilter(),
        GenreFilter(),
    )

    private class TypeFilter :
        AnimeFilter.Select<String>(
            "Tipe",
            arrayOf("Semua", "Movie", "Anime", "Donghua"),
        )

    private class GenreFilter :
        AnimeFilter.Select<String>(
            "Genre",
            arrayOf("Semua") + GENRES.map { it.first },
        )

    // =========================== Anime Details ============================

    override fun animeDetailsParse(document: Document) = SAnime.create().apply {
        val content = document.selectFirst("div.entry-content.serial-info")

        title = document.selectFirst("h1.entry-title")?.text()
            ?.replace(Regex("\\s+Subtitle Indonesia$"), "")
            ?: document.selectFirst("h1")?.text() ?: ""
        thumbnail_url = document.selectFirst("div.entry-content img, .serial-info img")?.absUrl("src")
            ?: document.selectFirst("img.wp-post-image")?.absUrl("src")

        genre = content?.select("tr:has(th:contains(Genre)) td a")?.eachText()?.joinToString(", ")
        status = when (content?.getInfo("Status")?.trim()) {
            "Ongoing" -> SAnime.ONGOING
            "Completed" -> SAnime.COMPLETED
            else -> SAnime.UNKNOWN
        }
        artist = content?.getInfo("Studio")?.trim()

        description = buildString {
            content?.select("p")?.eachText()?.forEach { append(it).append("\n\n") }
            content?.getInfo("Alternatif")?.also { append("Alternatif: $it\n") }
            content?.getInfo("Tipe")?.also { append("Tipe: $it\n") }
            content?.getInfo("Skor Anime")?.also { append("Skor: $it\n") }
            content?.getInfo("Dirilis")?.also { append("Dirilis: $it\n") }
            content?.getInfo("Musim")?.also { append("Musim: $it\n") }
            content?.getInfo("Seri")?.also { append("Seri: $it\n") }
            content?.getInfo("Fansub")?.also { append("Fansub: $it\n") }
        }.trim()
    }

    private fun Element?.getInfo(label: String): String? {
        if (this == null) return null
        return selectFirst("tr:has(th:contains($label)) td")?.text()
    }

    // =============================== Episodes =============================

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val episodes = document.select(episodeListSelector()).map { episodeFromElement(it) }
        if (episodes.isNotEmpty()) return episodes
        // Halaman episode (tanpa daftar): resolve series dari breadcrumb, parse halaman detail.
        val seriesUrl = document.seriesUrlFromBreadcrumb() ?: return emptyList()
        return client.newCall(GET(baseUrl + seriesUrl, headers))
            .execute()
            .use { it.asJsoup().select(episodeListSelector()).map { e -> episodeFromElement(e) } }
    }

    // Link episode series: -episode-N/; link movie: <slug>-1/ (tanpa -episode-)
    override fun episodeListSelector() = "ul.daftar a"

    override fun episodeFromElement(element: Element) = SEpisode.create().apply {
        val text = element.text()
        val num = Regex("(\\d+)\\s*(?:Subtitle.*)?$").find(text)?.groupValues?.get(1)
        episode_number = num?.toFloatOrNull() ?: 1F
        name = if ("Episode " in text) "Episode $num" else text.substringBefore(" Subtitle").trim()
        setUrlWithoutDomain(element.absUrl("href"))
    }

    /** Fallback: halaman episode tidak punya ul.daftar; resolve series dari breadcrumb. */
    private fun Element.seriesUrlFromBreadcrumb(): String? = selectFirst("div.breadcrumb a[href*='/anime/']")?.absUrl("href")
        ?.substringBefore("?")?.trimEnd('/')?.substringAfterLast("/anime/")
        ?.let { "/anime/$it/" }

    // ================================ Video ===============================

    override fun videoListRequest(episode: SEpisode) = GET(baseUrl + episode.url, headers)

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val episodeUrl = response.request.url.toString()
        // Sinkron cookie sesi episode ke WebView (dipakai resolver saat buka player lokal).
        response.headers("Set-Cookie").forEach { CookieManager.getInstance().setCookie(episodeUrl, it) }

        // Cache hasil resolve per episode: buka-ulang player instan tanpa
        // me-resolve ulang ~40 server; TTL 10 menit (token S3 berlaku 1 jam).
        videoCache[episodeUrl]?.let { (ts, videos) ->
            if (System.currentTimeMillis() - ts < CACHE_TTL_MS) {
                Log.e("AnimesailProbe", "cache hit: ${videos.size} videos")
                return videos
            }
            videoCache.remove(episodeUrl)
        }

        // Semua server dari mirror; fallback iframe default kalau mirror kosong.
        // bsrc framezilla (mis. acefile) di-decode agar host tersembunyi terdeteksi.
        val candidates = buildList {
            document.select("select.mirror option[data-em]").forEach { opt ->
                val src = decodeIframeSrc(opt.attr("data-em"))
                if (src != null) add(opt.text().trim() to src)
            }
            document.selectFirst("#pembed[data-default]")?.attr("data-default")?.let { b64 ->
                val src = decodeIframeSrc(b64)
                if (src != null) add("Default" to src)
            }
        }.distinctBy { it.second }
            .filterNot { (_, src) -> isBlacklisted(src) }

        if (candidates.isEmpty()) return emptyList()

        // Prioritas: MP4 → Udon → sisanya → Lokal terakhir (berdasarkan pageUrl).
        val ordered = candidates
            .mapIndexed { index, (label, pageUrl) -> Triple(index, priorityOf(pageUrl), label to pageUrl) }
            .sortedBy { (index, prio, _) -> prio * 1000 + index }

        // Resolve paralel (dibatasi 4 WebView) agar server mati cepat di-drop
        // tanpa membanjiri perangkat dan memicu timeout massal.
        val resolved = runBlocking(Dispatchers.IO) {
            ordered.map { (index, _, pair) ->
                async {
                    index to resolveSemaphore.withPermit {
                        resolveServer(pair.first, pair.second, episodeUrl)
                    }
                }
            }.awaitAll()
                .sortedBy { (index, _) -> index }
                .flatMap { it.second }
                .distinctBy { (key, _) -> key }
                .let { pairs ->
                    // MIN_QUALITY global berdasar key (URL stream atau udon-host-q).
                    // Fallback: kalau tidak ada yang lolos (episode lama hanya
                    // punya Lokal 360p), tampilkan semua daripada kosong.
                    val filtered = pairs.filter { (key, _) ->
                        LABEL_Q_REGEX.find(key)?.groupValues?.get(1)?.toIntOrNull()
                            ?.let { it >= MIN_QUALITY } ?: true
                    }.ifEmpty { pairs }
                    filtered.map { it.second }
                }
        }
        if (resolved.isNotEmpty()) videoCache[episodeUrl] = System.currentTimeMillis() to resolved
        return resolved
    }

    private fun priorityOf(pageUrl: String): Int = when {
        STREAM_URL_REGEX.containsMatchIn(pageUrl) -> 0
        pageUrl.toHttpUrlOrNull()?.host.orEmpty().let { it.contains("doply") || it.contains("playmogo") } -> 1
        pageUrl.contains("/tools/lokal/") -> 9
        else -> 5
    }

    private fun isBlacklisted(src: String): Boolean {
        val host = src.toHttpUrlOrNull()?.host.orEmpty()
        val inner = buildList {
            // framezilla: bsrc=<base64 iframe>
            Regex("bsrc=([A-Za-z0-9+/=]+)").find(src)?.groupValues?.get(1)?.let {
                add(runCatching { String(Base64.decode(it, Base64.DEFAULT)) }.getOrNull().orEmpty())
            }
            // popup player: ?url=<encoded target host>
            src.toHttpUrlOrNull()?.queryParameter("url")?.let { add(it) }
        }.joinToString(" ")
        return BLACKLIST.any { host.contains(it) || inner.contains(it) }
    }

    private fun resolveServer(label: String, pageUrl: String, episodeUrl: String): List<Pair<String, Video>> {
        val host = pageUrl.toHttpUrlOrNull()?.host.orEmpty()
        return when {
            // MP4 direct.
            STREAM_URL_REGEX.containsMatchIn(pageUrl) -> {
                val (q, name) = qualityLabel("MP4", pageUrl)
                if (q != null && q < MIN_QUALITY) {
                    emptyList()
                } else {
                    listOf(
                        pageUrl to Video(
                            url = pageUrl,
                            quality = name,
                            videoUrl = pageUrl,
                            headers = Headers.Builder().add("Referer", episodeUrl).build(),
                        ),
                    )
                }
            }
            // Udon: doply/playmogo — stream m3u8 di JS packed, decode via HTTP.
            host.contains("doply") || host.contains("playmogo") -> resolveUdon(pageUrl, episodeUrl, host)
            // Sisanya via WebView resolver (termasuk Lokal, episode-first).
            else -> resolver.resolve(
                pageUrl,
                episodeUrl,
                when {
                    LokalWebViewResolver.GIDEO_REGEX.containsMatchIn(pageUrl) -> LokalWebViewResolver.GIDEO_TIMEOUT_SEC
                    pageUrl.contains("/tools/lokal/") -> LokalWebViewResolver.LOKAL_TIMEOUT_SEC
                    else -> LokalWebViewResolver.DEFAULT_TIMEOUT_SEC
                },
            ).let { result ->
                // Proxy gideo membungkus iframe doply/playmogo: WebView tak selalu
                // mengekspos stream, tapi embed-nya tertangkap → unpack via HTTP.
                val embed = result.embedUrl
                if (result.url == null && embed != null) {
                    return@let resolveUdon(embed, episodeUrl, embed.toHttpUrlOrNull()?.host.orEmpty())
                }
                val url = result.url ?: return@let emptyList()
                val base = if (pageUrl.contains("/tools/lokal/")) "Lokal" else label.ifEmpty { "Server" }
                val (q, name) = qualityLabel(base, url)
                if (q != null && q < MIN_QUALITY) return@let emptyList()
                listOf(
                    url to Video(
                        url = url,
                        quality = name,
                        videoUrl = url,
                        headers = Headers.Builder().add("Referer", result.referer ?: pageUrl).build(),
                    ),
                )
            }
        }
    }

    /**
     * Udon (doply/playmogo): unpack `eval` JS → master m3u8 → varian via PlaylistUtils.
     * Dipakai langsung maupun untuk embed yang tertangkap WebView dari proxy gideo.
     */
    private fun resolveUdon(embedUrl: String, referer: String, host: String): List<Pair<String, Video>> {
        val embedHeaders = headers.newBuilder().add("Referer", referer).build()
        // Label varian tertangkap di videoNameGen (urutan = urutan video
        // PlaylistUtils); dipakai di key agar filter MIN_QUALITY global bisa
        // membaca kualitas tanpa akses properti Video.
        val labels = mutableListOf<String>()
        val videos = runCatching {
            val body = client.newCall(GET(embedUrl, embedHeaders)).execute().use { it.body.string() }
            val script = Regex("""eval\(function\(p,a,c[^<]*""", RegexOption.DOT_MATCHES_ALL)
                .find(body)?.value
                ?: return@runCatching emptyList<Video>()
            val unpacked = JsUnpacker.unpackAndCombine(script)
                ?: return@runCatching emptyList<Video>()
            val master = M3U8_REGEX.find(unpacked)?.value
                ?: return@runCatching emptyList<Video>()
            playlistUtils.extractFromHls(
                playlistUrl = master,
                referer = "https://$host/",
                videoNameGen = { q ->
                    labels.add(q)
                    "Udon - $q"
                },
            )
        }
            .getOrNull().orEmpty()
        return videos.mapIndexed { i, v ->
            "udon-$host-${labels.getOrNull(i) ?: i}" to v
        }
    }

    /** Kualitas dari filename URL (mis. `-720p`); null kalau tidak tercantum. */
    private fun qualityLabel(base: String, url: String): Pair<Int?, String> {
        val q = QUALITY_REGEX.find(url)?.groupValues?.get(1)?.toIntOrNull()
        return q to if (q != null) "$base ${q}p" else base
    }

    private fun decodeIframeSrc(b64: String?): String? = runCatching {
        val html = String(Base64.decode(b64 ?: return null, Base64.DEFAULT))
        Regex("src=['\"]([^'\"]+)['\"]").find(html)?.groupValues?.get(1)
    }.getOrNull()

    override fun videoFromElement(element: Element) = throw UnsupportedOperationException()
    override fun videoListSelector() = throw UnsupportedOperationException()

    private companion object {
        val GENRES = GenresFile.ALL

        // Host pembatas/mati per pengalaman user; Lokal sengaja tidak di-blacklist
        // karena sering jadi satu-satunya server tersedia.
        // ixdrop menangkap mixdrop/miixdrop/miiiixdrop dst.; pixel menangkap pixeldrain.
        val BLACKLIST = listOf("acefile", "dodo", "mega", "ixdrop", "vikingfile", "pixel", "buzz", "kturbo")
        val M3U8_REGEX = Regex("""https[^"']*m3u8[^"']*""")
        val STREAM_URL_REGEX = Regex("""\.(mp4|m3u8)(\?|$)""")
        val QUALITY_REGEX = Regex("""-(\d{3,4})p""")
        val LABEL_Q_REGEX = Regex("""(\d{3,4})p\b""")

        // / User hanya butuh 720p/1080p; kualitas lebih rendah di-drop.
        private const val MIN_QUALITY = 720

        /** TTL cache resolusi: 10 menit (token S3 berlaku 1 hour). */
        private const val CACHE_TTL_MS = 10 * 60_000L
    }
}

/**
 * Slug genre hasil scrape index /genre/ (100 item).
 */
private object GenresFile {
    val ALL: List<Pair<String, String>> = listOf(
        "Action" to "action",
        "Actions" to "actions",
        "Adult Cast" to "adult-cast",
        "Adventure" to "adventure",
        "Animation" to "animation",
        "Anime" to "anime",
        "Anthropomorphic" to "anthropomorphic",
        "Avant Garde" to "avant-garde",
        "Award Winning" to "award-winning",
        "Boys Love" to "boys-love",
        "Cars" to "cars",
        "CGDCT" to "cgdct",
        "Childcare" to "childcare",
        "Combat Sports" to "combat-sports",
        "Comedy" to "comedy",
        "Crime" to "crime",
        "Crossdressing" to "crossdressing",
        "Cultivation" to "cultivation",
        "Delinquents" to "delinquents",
        "Dementia" to "dementia",
        "Demon" to "demon",
        "Demons" to "demons",
        "Detective" to "detective",
        "Donghua" to "donghua",
        "Drama" to "drama",
        "Ecchi" to "ecchi",
        "Educational" to "educational",
        "Erotica" to "erotica",
        "Family" to "family",
        "Fantasy" to "fantasy",
        "Friendship" to "friendship",
        "Gag Humor" to "gag-humor",
        "Game" to "game",
        "Gender Bender" to "gender-bender",
        "GenreComedy" to "genrecomedy",
        "Girls Love" to "girls-love",
        "Gore" to "gore",
        "Gourmet" to "gourmet",
        "Harem" to "harem",
        "Hentai" to "hentai",
        "High Stakes Game" to "high-stakes-game",
        "Historical" to "historical",
        "Horror" to "horror",
        "Idols (Female)" to "idols-female",
        "Idols (Male)" to "idols-male",
        "Isekai" to "isekai",
        "Iyashikei" to "iyashikei",
        "Josei" to "josei",
        "Kids" to "kids",
        "Legal" to "legal",
        "Life" to "life",
        "Live Action" to "live-action",
        "Love Polygon" to "love-polygon",
        "Love Status Quo" to "love-status-quo",
        "Magic" to "magic",
        "Magical Sex Shift" to "magical-sex-shift",
        "Mahou Shoujo" to "mahou-shoujo",
        "Martial Arts" to "martial-arts",
        "Mecha" to "mecha",
        "Medical" to "medical",
        "Melodrama" to "melodrama",
        "Military" to "military",
        "Music" to "music",
        "Mystery" to "mystery",
        "Mythology" to "mythology",
        "OnGoing" to "ongoing",
        "Organized Crime" to "organized-crime",
        "Otaku Culture" to "otaku-culture",
        "Parody" to "parody",
        "Performing Arts" to "performing-arts",
        "Pets" to "pets",
        "Police" to "police",
        "Psychological" to "psychological",
        "Racing" to "racing",
        "Reincarnation" to "reincarnation",
        "Renzoku" to "renzoku",
        "Reverse Harem" to "reverse-harem",
        "Romance" to "romance",
        "Romantic Subtext" to "romantic-subtext",
        "Samurai" to "samurai",
        "School" to "school",
        "Sci-Fi" to "sci-fi",
        "Seinen" to "seinen",
        "Shoujo" to "shoujo",
        "Shoujo Ai" to "shoujo-ai",
        "Shounen" to "shounen",
        "Shounen Ai" to "shounen-ai",
        "Showbiz" to "showbiz",
        "Slice of Life" to "slice-of-life",
        "Space" to "space",
        "Sport" to "sport",
        "Sports" to "sports",
        "Strategy Game" to "strategy-game",
        "Super Power" to "super-power",
        "Supernatural" to "supernatural",
        "Survival" to "survival",
        "Suspense" to "suspense",
        "Team Sports" to "team-sports",
        "Thriller" to "thriller",
        "Time Travel" to "time-travel",
    )
}
