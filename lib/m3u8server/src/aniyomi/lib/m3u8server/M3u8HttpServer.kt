package aniyomi.lib.m3u8server

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.nanohttpd.protocols.http.IHTTPSession
import org.nanohttpd.protocols.http.NanoHTTPD
import org.nanohttpd.protocols.http.response.Response
import org.nanohttpd.protocols.http.response.Response.newFixedLengthResponse
import org.nanohttpd.protocols.http.response.Status
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.URLEncoder
import java.security.GeneralSecurityException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Real HTTP server for M3U8 processing using NanoHTTPD
 * Compatible with Android and provides actual HTTP endpoints
 *
 * @param client the OkHttpClient used for all upstream fetches. May carry a
 *   Cloudflare-solve interceptor; if so, supply [fallbackClient] so a failed
 *   WebView solve does not hard-crash the m3u8 path.
 * @param fallbackClient optional secondary client consulted when [client]
 *   throws an IOException that looks like a Cloudflare-solve failure (the
 *   canonical case is `lib/cloudflareinterceptor` raising "Cloudflare
 *   WebView solve produced no cookies"). The fallback is expected to have
 *   no CF interceptor and to use HTTP/1.1; it lets the request flow
 *   through with whatever browser-fingerprint headers the caller attached
 *   so a header-gated CDN can still be served. May be null — when null,
 *   solve failures bubble up as `UpstreamStatusException(503, …)`.
 */
class M3u8HttpServer(
    private val client: OkHttpClient,
    port: Int = 0, // 0 means random port
    private val fallbackClient: OkHttpClient? = null,
) : NanoHTTPD(port) {

    // Deadline wrapper around the caller-supplied clients. Decoy-segment
    // hosts (image-*.webp / *.css fillers in obfuscated playlists) can accept
    // the connection, send headers, then go silent forever — an unbounded
    // execute() blocked this server's NanoHTTPD worker thread (runBlocking in
    // handleSegmentRequest) and queued every subsequent player request behind
    // it, surfacing as an endless black loading screen. callTimeout caps the
    // ENTIRE call (connect + headers + body) as one deadline; newBuilder()
    // keeps any caller interceptors (Cloudflare solve, etc.) intact.
    private val fetchClient = client.newBuilder()
        .callTimeout(15, TimeUnit.SECONDS)
        .build()

    private val fetchFallbackClient = fallbackClient?.let { fb ->
        fb.newBuilder()
            .callTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    val port: Int
        get() = super.getListeningPort()

    private val tag by lazy { javaClass.simpleName }

    @Volatile
    private var isRunning = false

    override fun start() {
        try {
            super.start()
            isRunning = true
            Log.d(tag, "M3U8 HTTP Server started on port $port")
        } catch (e: Exception) {
            Log.e(tag, "Failed to start server: ${e.message}")
            throw e
        }
    }

    override fun stop() {
        super.stop()
        isRunning = false
        Log.d(tag, "M3U8 HTTP Server stopped")
    }

    fun isRunning(): Boolean = isRunning

    override fun handle(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method
        val handleStart = System.currentTimeMillis()

        Log.d(tag, "Received request: $method $uri from ${session.remoteIpAddress}")
        val diagEndpoint = uri.startsWith("/segment") || uri.startsWith("/m3u8")
        if (diagEndpoint) diag("IN   $uri".take(220))

        val response = when {
            uri.startsWith("/m3u8") -> handleM3u8Request(session)
            uri.startsWith("/segment") -> handleSegmentRequest(session)
            uri.startsWith("/health") -> handleHealthRequest()
            else -> {
                Log.w(tag, "Unknown endpoint: $uri")
                newFixedLengthResponse(Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found")
            }
        }

        Log.d(tag, "Response status: ${response.status}")
        if (diagEndpoint) diag("OUT  ${response.status} in ${System.currentTimeMillis() - handleStart}ms")
        return response
    }

    private fun handleM3u8Request(session: IHTTPSession): Response {
        val url = session.parameters["url"]?.first()
        val fallbackReferer = session.parameters["referer"]?.first()
        val fallbackUserAgent = session.parameters["useragent"]?.first()
        val headers = extractHeadersFromSession(session, fallbackReferer, fallbackUserAgent)

        Log.d(tag, "Processing M3U8 request for URL: $url")
        Log.d(tag, "Headers: $headers")
        diag("PLAYLIST ${url?.let { diagTrack(it) } ?: "null"} requested")

        if (url.isNullOrBlank()) {
            Log.w(tag, "Missing URL parameter in M3U8 request")
            return newFixedLengthResponse(Status.BAD_REQUEST, MIME_PLAINTEXT, "Missing url parameter")
        }

        return try {
            Log.d(tag, "Starting M3U8 processing for: $url")
            val token = session.parameters["token"]?.first()
            val processedContent = runBlocking { processM3u8Content(url, headers, token) }
            Log.d(tag, "M3U8 processing completed successfully, content length: ${processedContent.length}")
            newFixedLengthResponse(Status.OK, "application/vnd.apple.mpegurl", processedContent)
        } catch (e: UpstreamStatusException) {
            Log.w(tag, "Upstream HTTP ${e.code} for $url: ${e.message}")
            passThroughStatus(e)
        } catch (e: Exception) {
            Log.e(tag, "Error processing M3U8: ${e.message}", e)
            newFixedLengthResponse(Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Error: ${e.message}")
        }
    }

    private fun handleSegmentRequest(session: IHTTPSession): Response {
        val rawParam = session.parameters["url"]?.first()
        val stableParam = session.parameters["k"]?.first()
        // Stable-identity resolution (see [stableUpstream]): the player-visible
        // /segment URL carries only the (playlist, index) key, so the playlist
        // the player holds never changes across upstream rotations. Resolve it
        // to the FRESHEST raw upstream URL (current host/path/JWT) here.
        // Legacy `url=` playlists (rewritten before this scheme existed) still
        // resolve directly.
        // Token fallback for JWT-less CDNs: rotated Idlix-style URLs already
        // carry `?t=<JWT>` (leave untouched — double-appending `t=` gets the
        // CDN to 403 and desyncs the prefetch cache key); only append the
        // `token=` attachment when the resolved URL has none.
        val tokenParam = session.parameters["token"]?.first()
        val rawResolved = if (stableParam != null) {
            stableUpstream[stableParam] ?: run {
                Log.w(tag, "Unknown stable segment key: ${stableParam.take(120)}")
                diag("STABLE-MISS key=${stableParam.take(80)}")
                return newFixedLengthResponse(Status.NOT_FOUND, MIME_PLAINTEXT, "Unknown segment")
            }
        } else {
            rawParam
        }

        if (rawResolved.isNullOrBlank()) {
            Log.w(tag, "Missing URL parameter in segment request")
            return newFixedLengthResponse(Status.BAD_REQUEST, MIME_PLAINTEXT, "Missing url parameter")
        }
        val url = if (!tokenParam.isNullOrBlank() && !hasTokenParam(rawResolved)) {
            rawResolved + (if ("?" in rawResolved) "&" else "?") + "t=$tokenParam&pm=browser"
        } else {
            rawResolved
        }
        val keyUrl = session.parameters["key"]?.first()
        val iv = session.parameters["iv"]?.first()
        val fallbackReferer = session.parameters["referer"]?.first()
        val fallbackUserAgent = session.parameters["useragent"]?.first()
        val headers = extractHeadersFromSession(session, fallbackReferer, fallbackUserAgent)

        Log.d(tag, "Processing segment request for URL: $url (key=${keyUrl != null}, iv=${iv != null})")
        Log.d(tag, "Headers: $headers")

        // Canonical identity for this segment. The CDN rotates BOTH the host
        // (g6.* pool) and the sub-path variant (/files/ vs /cdn/) on every
        // playlist fetch, so the raw URL is not a stable cache key: the same
        // segment after a manifest reload or ABR rendition switch would miss
        // and re-download. The registry already maps every URL variant to
        // (playlistUrl, index) — reuse that as the key.
        val bareUrl = url.substringBefore("?")
        val pos = segmentPosition[url] ?: segmentPosition[bareUrl]
        val segKey = canonicalSegmentKey(url)

        // ── DIAG ── the player's blocking request. `range` matters: this
        // handler always answers with the FULL body and a 200, so if ExoPlayer
        // asks for a byte range (CMAF sidx / partial-segment reads) it gets
        // more bytes than it budgeted for and may mis-track its buffer.
        val reqStart = System.currentTimeMillis()
        val track = pos?.let { diagTrack(it.first) }
            ?: "untracked:${url.substringAfterLast('/').substringBefore('?').take(24)}"
        val idx = pos?.second
        val rangeHeader = session.headers["range"] ?: session.headers["Range"]
        val now = System.currentTimeMillis()
        val sinceLast = lastTrackRequestAt.put(track, now)
        val gapMs = if (sinceLast == null) Long.MAX_VALUE else now - sinceLast
        val gap = if (sinceLast == null) "first" else "${gapMs}ms"
        diag("SEG  $track idx=$idx range=${rangeHeader ?: "-"} key=${keyUrl != null} iv=${iv != null} sinceLast=$gap ${diagQueue()}")

        // Position-jump detection:
        // Only a NON-SEQUENTIAL jump (FF / rewind / seek to unbuffered)
        // cancels this track's pending look-ahead: the queued window belongs
        // to the old position. Sequential advances (idx == prev+1, even with
        // a tiny < 400ms gap while mpv buffer-fills) must NEVER cancel —
        // that is exactly the traffic prefetch exists to serve. Cancelling it
        // forced every next segment into a serial cold fetch (Idlix demuxed:
        // 60% MISS, each video chunk paying full DNS+TLS latency) and turned
        // every seek refill into a permanent-feeling stall.
        pos?.let {
            val prev = lastServedIndex[it.first]
            val maxLookahead = if (it.first in nonVideoPlaylists) rapidScanLookahead else prefetchLookahead
            if (prev != null && (it.second < prev - 3 || it.second > prev + maxLookahead + 4)) {
                cancelPendingPrefetch(it.first, "jump $prev->${it.second}")
            }
            lastServedIndex[it.first] = it.second
            playlistHighWater.merge(it.first, it.second) { a, b -> maxOf(a, b) }
            if (it.second + catchUpMargin < (playlistHighWater[it.first] ?: 0)) {
                cancelStalePrefetch(it.first)
            }
        }

        // Serve a prefetched segment straight from RAM — zero network latency.
        cacheGet(segKey)?.let { cached ->
            Log.d(tag, "Prefetch hit: ${url.substringAfterLast('/')} (${cached.size} bytes)")
            diag("HIT  $track idx=$idx ${cached.size}B head[${diagHead(cached)}] in ${System.currentTimeMillis() - reqStart}ms")
            // Keep the lookahead window full on every hit, including rapid
            // buffer-fill bursts (small gaps): schedulePrefetch dedups against
            // the cache / in-flight set, so topping up is a cheap no-op when
            // the window is already full — and it is the only thing that keeps
            // the window from collapsing exactly when the player consumes
            // fastest.
            schedulePrefetch(url, headers)
            return newFixedLengthResponse(
                Status.OK,
                AutoDetector.detectContainerMime(cached),
                ByteArrayInputStream(cached),
                cached.size.toLong(),
            )
        }

        // Prefetch for this URL is still downloading — join it instead of double-fetching
        prefetchFutures[segKey]?.let { fut ->
            val joinStart = System.currentTimeMillis()
            diag("JOIN $track idx=$idx waiting on in-flight prefetch")
            try {
                val joined = fut.get(2, java.util.concurrent.TimeUnit.SECONDS)
                Log.d(tag, "Prefetch join: ${url.substringAfterLast('/')} (${joined.size} bytes)")
                diag("JOIN-OK $track idx=$idx ${joined.size}B waited ${System.currentTimeMillis() - joinStart}ms head[${diagHead(joined)}]")
                schedulePrefetch(url, headers)
                return newFixedLengthResponse(
                    Status.OK,
                    AutoDetector.detectContainerMime(joined),
                    ByteArrayInputStream(joined),
                    joined.size.toLong(),
                )
            } catch (e: Exception) {
                Log.d(tag, "Prefetch join failed for ${url.substringAfterLast('/')}: ${e.message}")
                diag("JOIN-FAIL $track idx=$idx after ${System.currentTimeMillis() - joinStart}ms: ${e.javaClass.simpleName}: ${e.message} -> cold fetch")
            }
        }

        val hasAes = keyUrl != null && iv != null
        diag("MISS $track idx=$idx cold-fetch aes=$hasAes (futures=${prefetchFutures.size} cacheKB=${prefetchCacheBytes / 1024})")
        return try {
            Log.d(tag, "Starting segment processing for: $url (aes=$hasAes)")
            val segmentData = runBlocking {
                processSegmentUrl(url, headers, keyUrl, iv)
            }
            Log.d(tag, "Segment processing completed successfully, data size: ${segmentData.size} bytes")
            diag("SERVE $track idx=$idx ${segmentData.size}B took ${System.currentTimeMillis() - reqStart}ms head[${diagHead(segmentData)}]")
            val inputStream = ByteArrayInputStream(segmentData)
            newFixedLengthResponse(
                Status.OK,
                AutoDetector.detectContainerMime(segmentData),
                inputStream,
                segmentData.size.toLong(),
            )
        } catch (e: UpstreamStatusException) {
            Log.w(tag, "Upstream segment HTTP ${e.code} for $url: ${e.message}")
            diag("ERR  $track idx=$idx upstream ${e.code} after ${System.currentTimeMillis() - reqStart}ms")
            passThroughStatus(e)
        } catch (e: Exception) {
            Log.e(tag, "Error processing segment: ${e.message}", e)
            diag("ERR  $track idx=$idx ${e.javaClass.simpleName}: ${e.message} after ${System.currentTimeMillis() - reqStart}ms")
            newFixedLengthResponse(Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Error: ${e.message}")
        }
    }

    /**
     * Wraps the [UpstreamStatusException] code in an HTTP response that
     * surfaces the upstream's status (403, 503, …) to the player instead of
     * collapsing every failure into [Status.INTERNAL_ERROR]. The body carries
     * the upstream URL + code so logcat / mpv diagnostics can pin the cause
     * without re-reading the chain.
     */
    private fun passThroughStatus(e: UpstreamStatusException): Response {
        val nanoStatus = when (e.code) {
            401 -> Status.UNAUTHORIZED
            403 -> Status.FORBIDDEN
            404 -> Status.NOT_FOUND
            429 -> Status.TOO_MANY_REQUESTS
            in 400..499 -> Status.BAD_REQUEST
            500 -> Status.INTERNAL_ERROR
            503 -> Status.SERVICE_UNAVAILABLE
            else -> Status.INTERNAL_ERROR
        }
        val body = "Upstream ${e.code} for ${e.url}\n${e.message}"
        return newFixedLengthResponse(nanoStatus, MIME_PLAINTEXT, body)
    }

    private fun handleHealthRequest(): Response {
        Log.d(tag, "Health check requested")
        val status = getHealthStatus()
        Log.d(tag, "Health status: $status")
        return newFixedLengthResponse(Status.OK, MIME_PLAINTEXT, status)
    }

    /**
     * Build the upstream-fetch header map. Session headers (forwarded by the
     * media player when it opens `http://localhost:…/m3u8?url=…`) seed the map;
     * per-URL attachments from [fallbackReferer]/[fallbackUserAgent] then
     * OVERRIDE / add their values, so the original extension's `video.headers`
     * (encoded into the proxied URL by [createLocalUrl]) always win over
     * whatever ExoPlayer/mpv copied to the localhost request.
     *
     * This prioritisation is the root-cause fix for `vault-99.owocdn.top` and
     * similar Miruro-CDN hosts: the m3u8 server was previously fetching the
     * upstream with no Referer (mpv does not carry the original `Referer`
     * through to localhost), and the CDN returned a Cloudflare-signed 403 —
     * the CloudflareInterceptor then kicked in, attempted a WebView solve, and
     * when it failed (CDN sets no `cf_clearance` for null-Referer requests),
     * the m3u8 path crashed with HTTP 500. Encoding the extension's Referer
     * directly in the proxied URL cuts that whole branch off.
     */
    private fun extractHeadersFromSession(
        session: IHTTPSession,
        fallbackReferer: String? = null,
        fallbackUserAgent: String? = null,
    ): Map<String, String> {
        val headers = mutableMapOf<String, String>()

        session.headers.forEach { (key, value) ->
            when (key.lowercase()) {
                "user-agent", "referer", "origin", "accept", "accept-language",
                "accept-encoding", "connection", "cache-control", "pragma",
                -> {
                    headers[key.lowercase()] = value
                }
            }
        }

        if (!fallbackUserAgent.isNullOrBlank()) {
            headers["user-agent"] = fallbackUserAgent
        }
        if (!fallbackReferer.isNullOrBlank()) {
            headers["referer"] = fallbackReferer
        }

        Log.d(tag, "Extracted headers (referer=${headers["referer"]?.take(80) ?: "none"}, ua=${headers["user-agent"]?.take(40) ?: "none"})")
        return headers
    }

    /**
     * Thrown by `fetchM3u8Content` / `fetchSegmentBytes` when the upstream
     * returns a non-2xx HTTP code, OR when both the primary [client] and the
     * secondary [fallbackClient] (CF-stripped) fail to retrieve the resource.
     *
     * The [code] carries the upstream's HTTP status when known; for
     * transform-layer failures (e.g., a Cloudflare-solve crash with no
     * response yet observed) it defaults to 503 so callers see a
     * service-unavailable NanoHTTPD response instead of opaque INTERNAL_ERROR.
     *
     * Caught by [handleM3u8Request]/[handleSegmentRequest] → [passThroughStatus]
     * so mpv receives a meaningful 403/503 (not a `500 Error: …` wrapper).
     */
    private class UpstreamStatusException(
        val code: Int,
        val url: String,
        message: String,
        cause: Throwable? = null,
    ) : IOException("$code for $url: $message", cause)

    /**
     * Daemon pool for best-effort DNS prefetch. Size 2 keeps the warmup from
     * saturating the device resolver. It must NEVER share threads with
     * [segmentPrefetchExecutor] — hundreds of queued DNS lookups would starve
     * segment prefetches and re-introduce resume loading stalls.
     */
    private val dnsWarmupExecutor by lazy {
        Executors.newFixedThreadPool(2) { runnable ->
            Thread(runnable, "M3u8DnsWarmup").apply { isDaemon = true }
        }
    }

    /**
     * Daemon pool dedicated to segment prefetching. Kept separate from the
     * DNS warmup pool so that a seek/resume gets its next segments
     * downloading immediately regardless of how much DNS warmup work is
     * still queued.
     */
    private val segmentPrefetchExecutor by lazy {
        Executors.newFixedThreadPool(4) { runnable ->
            Thread(runnable, "M3u8SegmentPrefetch").apply { isDaemon = true }
        }
    }

    /**
     * Best-effort DNS prefetch for every host referenced by a playlist
     * (segments, EXT-X-MAP init segments, EXT-X-MEDIA audio/subtitle URIs).
     *
     * The majorplay-style CDNs rotate the segment host per segment across
     * hundreds of domains, so the player's FIRST fetch of any given segment
     * pays a fresh DNS lookup (+ TLS handshake) — that is the dominant delay
     * when seeking into an un-buffered middle position. Resolving all hosts
     * in the background right after the playlist is fetched means the OS DNS
     * cache is hot by the time the player requests any segment.
     *
     * All failures are swallowed: a failed lookup must never affect playback.
     */
    private fun warmDnsForPlaylist(content: String, baseUrl: String) {
        try {
            val base = baseUrl.toHttpUrlOrNull()
            val uriAttrRegex = Regex("""URI="([^"]+)"""")
            val hosts = LinkedHashSet<String>()
            for (line in content.lines()) {
                val uri = when {
                    line.isBlank() -> null
                    line.startsWith("#EXT-X-MAP:") || line.startsWith("#EXT-X-MEDIA:") ->
                        uriAttrRegex.find(line)?.groupValues?.get(1)
                    line.startsWith("#") -> null
                    else -> line.trim()
                } ?: continue
                val host = base?.resolve(uri)?.host ?: uri.toHttpUrlOrNull()?.host
                if (!host.isNullOrBlank()) hosts.add(host)
            }
            if (hosts.isEmpty()) return
            Log.d(tag, "DNS warmup: resolving ${hosts.size} host(s) from playlist")
            hosts.forEach { host ->
                dnsWarmupExecutor.execute {
                    runCatching { InetAddress.getByName(host) }
                }
            }
        } catch (e: Exception) {
            Log.d(tag, "DNS warmup skipped: ${e.message}")
        }
    }

    /**
     * Ordered segment URLs per child-playlist URL (key = the URL the playlist
     * was fetched from). Filled by [modifyM3u8Content] as it rewrites the
     * playlist — no extra parsing pass needed. Also indexed per segment URL
     * in [segmentPosition] for O(1) lookahead lookup.
     */
    private val segmentOrder = ConcurrentHashMap<String, List<String>>()
    private val segmentPosition = ConcurrentHashMap<String, Pair<String, Int>>()

    /**
     * Player-visible stable identity → freshest raw upstream URL.
     *
     * The CDN rotates host (`g2.*` pool), sub-path (`/files/` vs `/cache/`),
     * disguise filename AND the `?t=` JWT on every playlist fetch. The
     * rewritten playlist handed to the player must therefore NOT embed raw
     * upstream URLs: after any manifest reload (seek, resume, periodic
     * refresh) the player would hold a playlist whose URLs match nothing it
     * saw before, and older ffmpeg HLS demuxers (mpv's) report
     * `hls: The m3u8 list sequence may have been wrapped`, lose the seek
     * target, feed mis-assembled bytes to the decoder (`Invalid NAL unit
     * size` bursts) and abandon the audio leg — the permanent-spinner stall
     * on Idlix demuxed streams. hls.js is immune (sequence-based), which is
     * why the same stream seeks fine on web.
     *
     * So `/segment` URLs carry only a stable key — `s#<playlist>#<index>`
     * for media segments (see [stableSegmentKey]) or the existing
     * `init#<bare>` key for `#EXT-X-MAP` inits — resolved here to the latest
     * raw URL on every request. Same growing-registry pattern as
     * [segmentOrder]/[segmentPosition]: entries are tiny and keyed per video
     * rendition, so no eviction (an evicted key would 404 a segment the
     * player legitimately still holds).
     */
    private val stableUpstream = ConcurrentHashMap<String, String>()

    private fun stableSegmentKey(playlistUrl: String, index: Int): String = "s#$playlistUrl#$index"

    /**
     * Canonical cache keys for `#EXT-X-MAP` init segments, keyed by the URL
     * variant the player asks for. Init URIs are NOT registered in
     * [segmentPosition] on purpose: that map drives seek/jump detection, and
     * an init fetch (index-less, arriving mid-stream on every player reopen)
     * would otherwise read as a giant position jump and cancel the track's
     * whole look-ahead. The CDN rotates host and `/files/` vs `/cache/`
     * sub-path on every playlist fetch, so without a stable key the same 771 B
     * init is cold-downloaded on every reopen (observed at 55.9 s and 181.5 s
     * in the demuxed-stall capture).
     */
    private val initSegmentKeys = ConcurrentHashMap<String, String>()

    /**
     * Canonical keys of `#EXT-X-MAP` init segments, exempt from the LRU
     * eviction in [cachePut]. They are only a few hundred bytes but are
     * re-requested on every player reopen, and losing one mid-stream makes
     * ffmpeg fail with `hls: Failed to open an initialization section in
     * playlist 1`. Plain LRU ordering evicts them first, because they are
     * inserted before the segment burst that follows them.
     */
    private val pinnedCacheKeys: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private val prefetchInFlight: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /**
     * In-flight prefetch jobs keyed by the segment's canonical key
     * (`playlistUrl#index`, see [canonicalSegmentKey]) rather than the raw
     * URL, so a host/path variant change does not orphan an in-flight fetch.
     * Lets [handleSegmentRequest] join an ongoing download instead of racing
     * it with a duplicate fetch.
     */
    private val prefetchFutures = ConcurrentHashMap<String, Future<ByteArray>>()

    /** Last served segment index per playlist URL — detects position jumps. */
    private val lastServedIndex = ConcurrentHashMap<String, Int>()

    /** Furthest segment index ever served per child playlist. A track serving
     *  far below its OWN mark is re-reading toward the playhead (mpv/ffmpeg
     *  hls linear-seeks demuxed streams from 0 after a reload/seek) —
     *  [schedulePrefetch] widens its window and [cancelStalePrefetch] frees
     *  the pool for the race. Per-playlist on purpose: normal pacing and
     *  fresh episodes (new URLs, mark 0) never misfire, and the mark survives
     *  a quality switch because the audio playlist URL is unchanged. */
    private val playlistHighWater = ConcurrentHashMap<String, Int>()

    // ───────────────────────── DIAGNOSTICS ─────────────────────────
    // Temporary instrumentation for the Idlix demuxed (single "IDLIX" quality)
    // seek/resume stall. Log-only, no behavior change. Capture with:
    //   adb logcat -s M3u8Diag
    // then reproduce: forward into un-buffered territory, wait for the spinner,
    // then seek BACK into territory that already played.

    private val diagStartedAt = System.currentTimeMillis()

    private fun diag(msg: String) {
        // Thread name is part of the payload: if video and audio segment
        // requests are served from the SAME thread, NanoHTTPD is serialising
        // them and one slow upstream fetch blocks the other track outright.
        Log.d("M3u8Diag", "${System.currentTimeMillis() - diagStartedAt}ms [${Thread.currentThread().name}] $msg")
    }

    /**
     * Short stable label for a child playlist, tagged by track kind, so the
     * video and audio legs of a demuxed stream are tellable apart in the log
     * (`…/data-625529.json` -> `video:data-625529`).
     */
    private fun diagTrack(playlistUrl: String): String {
        val name = playlistUrl.substringAfterLast('/').substringBefore('?')
        val kind = if (playlistUrl in nonVideoPlaylists) "audio" else "video"
        return "$kind:${name.ifBlank { playlistUrl.takeLast(18) }}"
    }

    /**
     * Leading bytes as hex. Reveals whether what we hand the player is really
     * fMP4 (`00 00 xx xx 66 74 79 70` = "ftyp") / TS (`47 …`) or something the
     * demuxer will choke on — a bad junk-strip or a decoy segment would stall
     * the player permanently, and re-seeking would re-request the same bytes.
     */
    private fun diagHead(data: ByteArray): String = (0 until minOf(12, data.size)).joinToString(" ") { "%02X".format(data[it].toInt() and 0xFF) }

    /**
     * Wall-clock of the last player request per track. The gap distinguishes a
     * player-side stall (no requests at all — ExoPlayer is waiting on its own
     * buffer/decoder, not on us) from a proxy-side stall (requests keep coming,
     * often for the SAME index, i.e. the player is retrying a response it
     * rejected).
     */
    private val lastTrackRequestAt = ConcurrentHashMap<String, Long>()

    /** Prefetch pool backlog — a deep queue makes [handleSegmentRequest]'s JOIN wait behind the other track. */
    private fun diagQueue(): String {
        val ex = segmentPrefetchExecutor as? java.util.concurrent.ThreadPoolExecutor
            ?: return "pool=?"
        return "pool=${ex.activeCount}/${ex.poolSize} q=${ex.queue.size}"
    }

    /**
     * Bounded in-memory LRU cache of processed (junk-stripped) segment bytes
     * keyed by the segment's canonical key (`playlistUrl#index`, see
     * [canonicalSegmentKey]) — NOT the raw URL, which the CDN rewrites with a
     * rotating host + path variant on every playlist fetch. Kept in RAM (not
     * disk) because the working set is tiny — lookahead 4 chunks ≈ 5 MB — and
     * it avoids needing a Context for a cache dir. Evicts oldest entries past
     * [prefetchCacheMaxBytes].
     */
    private val prefetchCache = LinkedHashMap<String, ByteArray>(16, 0.75f, true)
    private var prefetchCacheBytes = 0L

    private val prefetchCacheMaxBytes = 32L * 1024 * 1024
    private val prefetchLookahead = 4

    /** Look-ahead for demuxed audio/subtitle tracks. */
    private val nonVideoLookahead = 8

    /** Rapid-scan lookahead for fast-forwarding/linear seeks where the player rapidly consumes
     *  segments (< 200 ms gap). */
    private val rapidScanLookahead = 8
    private val rapidScanThresholdMs = 200L

    /** Index margin below the playhead high-water at which a track counts as
     *  racing. Normal pacing stays within the look-ahead window of the
     *  playhead; a reload/seek re-read starts at 0, far below it. */
    private val catchUpMargin = 4

    /** Look-ahead while racing. */
    private val catchUpLookahead = 8

    /** No player request for this long while another track races = the track
     *  was abandoned (old rendition after a quality switch); its queued
     *  prefetches are dead weight on the pool and CDN bandwidth. */
    private val staleTrackMs = 5000L

    /** Child playlist URLs registered by [rewriteMediaUri] when the master
     *  references them as `#EXT-X-MEDIA` audio/subtitle tracks, so
     *  [schedulePrefetch] can give them the reduced window. */
    private val nonVideoPlaylists: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /**
     * Cache of fully-processed (rewritten) playlists keyed by
     * `url|token|referer|ua`. The player reloads the same manifest in bursts
     * while resuming (5-8 GETs within 2 s); each reload used to re-download
     * the upstream playlist and re-rewrite up to 2169 segment URLs. Bounded to
     * [playlistCacheMaxEntries] with a [playlistCacheTtlMs] freshness window —
     * long enough to absorb a reload burst, short enough that a re-signed
     * token / rotated playlist is refetched quickly. VOD-only assumption
     * (ponytail: live playlists would need a shorter TTL; none of the sources
     * using this lib serve live).
     */
    private val playlistCache = LinkedHashMap<String, Pair<String, Long>>(8, 0.75f, true)
    private val playlistCacheMaxEntries = 8
    private val playlistCacheTtlMs = 60_000L

    private fun playlistCacheGet(key: String): String? = synchronized(playlistCache) {
        val entry = playlistCache[key] ?: return@synchronized null
        if (System.currentTimeMillis() - entry.second > playlistCacheTtlMs) {
            playlistCache.remove(key)
            null
        } else {
            entry.first
        }
    }

    private fun playlistCachePut(key: String, content: String) {
        synchronized(playlistCache) {
            playlistCache[key] = content to System.currentTimeMillis()
            while (playlistCache.size > playlistCacheMaxEntries) {
                val it = playlistCache.entries.iterator()
                if (it.hasNext()) it.next() else break
                it.remove()
            }
        }
    }

    private fun cachePut(url: String, data: ByteArray) {
        synchronized(prefetchCache) {
            prefetchCache.put(url, data)?.let { prefetchCacheBytes -= it.size }
            prefetchCacheBytes += data.size
            val it = prefetchCache.entries.iterator()
            while (prefetchCacheBytes > prefetchCacheMaxBytes && it.hasNext()) {
                val entry = it.next()
                // Init segments are pinned and never evicted; see
                // [pinnedCacheKeys]. Their combined size is negligible, so
                // they are not counted against the byte budget.
                if (entry.key in pinnedCacheKeys) continue
                prefetchCacheBytes -= entry.value.size
                it.remove()
            }
        }
    }

    private fun cacheGet(url: String): ByteArray? = synchronized(prefetchCache) { prefetchCache[url] }

    private fun cachePeek(url: String): Boolean = synchronized(prefetchCache) { prefetchCache.containsKey(url) }

    /**
     * Stable identity for a segment, used as the key for [prefetchCache],
     * [prefetchFutures] and [prefetchInFlight]. The CDN serves the same
     * segment from a different host (`g6.*` pool) and a different sub-path
     * variant (`/files/` on one rendition, `/cdn/` on another) every time the
     * playlist is fetched, so raw-URL keys thrash the cache on every manifest
     * reload / ABR switch. The (playlistUrl, index) pair from [segmentPosition]
     * is variant-independent; init segments and untracked URLs fall back to the
     * bare (query-stripped) URL.
     */
    private fun canonicalSegmentKey(url: String): String {
        initSegmentKeys[url]?.let { return it }
        val bare = url.substringBefore("?")
        initSegmentKeys[bare]?.let { return it }
        val pos = segmentPosition[url] ?: segmentPosition[bare]
        return pos?.let { "${it.first}#${it.second}" } ?: bare
    }

    /**
     * Cancels the pending background prefetches of ONE child playlist when the
     * player jumps (FF / rewind) within that track: the look-ahead queued from
     * the old position is stale traffic that saturates the 4 prefetch threads
     * with warm-CDN reads (~11 MB/s observed) while the segment the player
     * blocks on crawls. [schedulePrefetch] re-arms the window from the new
     * position.
     *
     * Scoped by [playlistUrl] instead of cancelling everything: canonical keys
     * are `"$playlistUrl#$index"` (see [canonicalSegmentKey]), so a prefix
     * match isolates the jumped track. Demuxed streams (Idlix's single
     * "IDLIX" quality, where the whole master is proxied) play video and audio
     * as two separately proxied child playlists, and the old global cancel also
     * nuked the AUDIO track's look-ahead on every video seek — forcing it to
     * re-fetch cold from the new position and compete with the video refill for
     * the prefetch threads and CDN bandwidth. ExoPlayer waits for both tracks
     * before advancing, so that surfaced as a stuck loading spinner with
     * bandwidth still transferring after resume / forward. Muxed sources have
     * one track, so narrowing the cancel cannot change their behavior.
     */
    private fun cancelPendingPrefetch(playlistUrl: String, reason: String) {
        val prefix = "$playlistUrl#"
        var cancelled = 0
        for (key in prefetchFutures.keys) {
            if (!key.startsWith(prefix)) continue
            // Drop this key's in-flight marker here rather than wholesale: a
            // job cancelled before it starts never reaches its own finally
            // block, so the marker has to go — but clearing the whole set also
            // erased markers of freshly queued prefetches, letting the same
            // segment download twice mid-resume.
            prefetchInFlight.remove(key)
            val future = prefetchFutures.remove(key)
            if (future != null && future.cancel(true)) cancelled++
        }
        if (cancelled > 0) {
            Log.d(tag, "Cancelled $cancelled pending prefetch task(s) for ${playlistUrl.substringAfterLast('/').substringBefore('?')} ($reason)")
            diag("CANCEL ${diagTrack(playlistUrl)} cancelled=$cancelled ($reason)")
        }
    }

    /**
     * While [activePlaylist] is racing to catch up, cancel the pending
     * prefetches of every OTHER child playlist that has stopped being served.
     * A quality switch abandons the old rendition's playlist, but its
     * look-ahead stays queued in the 4-thread pool and keeps pulling CDN
     * bandwidth for segments the player will never request again (v14 log:
     * zero CANCEL lines across a full 1080p→720p→1080p session). A track
     * counts as abandoned once [staleTrackMs] pass with no player request;
     * tracks still being served (e.g. audio racing alongside video after a
     * seek) are left alone.
     */
    private fun cancelStalePrefetch(activePlaylist: String) {
        val now = System.currentTimeMillis()
        val stale = prefetchFutures.keys
            .map { it.substringBeforeLast('#') }
            .distinct()
            .filter { it != activePlaylist }
            .filter { (lastTrackRequestAt[diagTrack(it)] ?: 0L) < now - staleTrackMs }
        for (pl in stale) {
            cancelPendingPrefetch(pl, "catch-up race on ${diagTrack(activePlaylist)}")
        }
    }

    /**
     * After segment N is served, background-fetch N+1..N+4 into the cache so
     * the player never waits on the network for the next chunks, and a seek
     * within the lookahead window is instant. Best-effort: failures are
     * logged and swallowed. AES-encrypted playlists are skipped (ponytail:
     * prefetching them needs key+iv plumbing; add when an AES source shows up).
     */
    private fun schedulePrefetch(fetchedUrl: String, headers: Map<String, String>) {
        // Player requests carry the token query (t=…&pm=browser) appended by
        // createLocalSegmentUrl; the registry keys on raw playlist URLs.
        val tokenQuery = when {
            "?t=" in fetchedUrl -> "t=" + fetchedUrl.substringAfter("?t=")
            "&t=" in fetchedUrl -> "t=" + fetchedUrl.substringAfter("&t=")
            else -> null
        }
        val bareUrl = tokenQuery?.let {
            fetchedUrl.substringBefore("?t=").substringBefore("&t=")
        } ?: fetchedUrl
        val pos = segmentPosition[fetchedUrl] ?: segmentPosition[bareUrl]
        if (pos == null) {
            // Silent bail-out: no look-ahead at all for this segment, so every
            // next chunk is a cold fetch at the player's pace. Log which URL
            // shape failed to resolve so a token/variant mismatch is visible.
            diag("PREFETCH-SKIP ${diagTrack(fetchedUrl.substringBefore("?t=").substringBefore("&t="))} unregistered url=${fetchedUrl.take(90)} bare=${bareUrl.take(90)}")
            return
        }
        val order = segmentOrder[pos.first]
        if (order == null) {
            diag("PREFETCH-SKIP ${diagTrack(pos.first)} idx=${pos.second} no segmentOrder (playlist=${pos.first.take(70)})")
            return
        }
        // Demuxed audio/subtitle tracks get a short look-ahead window; the
        // freed bandwidth goes to the video refill that the player blocks on.
        // Exception: while a track is RACING (serving far below the playhead
        // high-water — a catch-up re-read after a reload or seek) every
        // segment it requests is a cold fetch the player waits on serially,
        // so widen the window and let the pool pipeline them instead.
        val isNonVideo = pos.first in nonVideoPlaylists
        val trackKey = diagTrack(pos.first)
        val lastReq = lastTrackRequestAt[trackKey] ?: 0L
        val isRapid = (System.currentTimeMillis() - lastReq) in 0..rapidScanThresholdMs
        val racing = pos.second + catchUpMargin < (playlistHighWater[pos.first] ?: 0)
        val lookahead = when {
            isNonVideo && (isRapid || racing) -> rapidScanLookahead
            racing -> catchUpLookahead
            isNonVideo -> nonVideoLookahead
            else -> prefetchLookahead
        }
        var queued = 0
        var skipped = 0
        for (i in pos.second + 1..pos.second + lookahead) {
            val next = order.getOrNull(i) ?: break
            // Key on the canonical (playlist, index) identity, not the raw URL:
            // the CDN hands back a different host/path variant per playlist
            // fetch, so a URL-keyed future/cache is never joined by the
            // player's next request and the whole window re-downloads.
            val nextKey = "${pos.first}#$i"
            if (cachePeek(nextKey) || prefetchFutures.containsKey(nextKey) || !prefetchInFlight.add(nextKey)) {
                skipped++
                continue
            }
            val nextAuth = if (tokenQuery != null && !hasTokenParam(next)) {
                next + (if ("?" in next) "&" else "?") + tokenQuery
            } else {
                next
            }
            val future = segmentPrefetchExecutor.submit(
                java.util.concurrent.Callable {
                    try {
                        stripInterleavedJunk(runBlocking { fetchSegmentBytes(nextAuth, headers) }).also { bytes ->
                            cachePut(nextKey, bytes)
                        }
                    } finally {
                        prefetchFutures.remove(nextKey)
                        prefetchInFlight.remove(nextKey)
                    }
                },
            )
            prefetchFutures[nextKey] = future
            queued++
        }
        if (queued > 0 || skipped > 0) {
            diag("PREFETCH ${diagTrack(pos.first)} idx=${pos.second} window=$lookahead${if (isNonVideo) "(non-video)" else ""}${if (isRapid) "(rapid-scan)" else ""}${if (racing) "(catch-up)" else ""} queued=$queued skipped=$skipped orderSize=${order.size}")
        }
        if (queued > 0) Log.d(tag, "Prefetch: queued $queued segment(s) after ${fetchedUrl.substringAfterLast('/').substringBefore('?')}")
    }

    /**
     * Process M3U8 content through the server
     */
    private suspend fun processM3u8Content(url: String, headers: Map<String, String> = emptyMap(), token: String? = null): String = withContext(Dispatchers.IO) {
        // Player reloads the same manifest in bursts (5-8 requests within 2 s
        // while resuming). Each reload used to re-fetch the upstream playlist
        // (0.7-1.9 MB), re-rewrite up to 2169 segment URLs and re-warm DNS —
        // pure waste that also delayed the segment requests queued behind it.
        // Serve the rewritten result from RAM instead.
        val cacheKey = "$url|${token.orEmpty()}|${headers["referer"].orEmpty()}|${headers["user-agent"].orEmpty()}"
        playlistCacheGet(cacheKey)?.let { cached ->
            Log.d(tag, "Playlist cache hit: ${url.substringAfterLast('/').substringBefore('?')}")
            return@withContext cached
        }
        try {
            Log.d(tag, "Fetching M3U8 content from: $url with headers: $headers")
            // Collapse the rendition ladder BEFORE rewriting: a demuxed master
            // (series, some movies) handed to the player in full lets ExoPlayer
            // ABR switch variants; each switch is a new child playlist with
            // cold prefetch/cache state, and the ~0ms localhost cache-hit
            // timing inflates the bandwidth estimate -> oscillation (switch up
            // on a fake spike, stall on the miss, drop down). One variant = no
            // ABR decision = no switch stalls.
            // "#pv=<height|bandwidth>" fragments appended by extensions that
            // offer one Video per rendition (Idlix split-audio) select which
            // variant to collapse to. The fragment is stripped before the
            // upstream fetch; the playlist cache key above keeps it, so each
            // quality gets its own rewritten-master cache entry.
            // "#pv=all" (ExoPlayer backend) skips the collapse entirely: the
            // full variant ladder reaches the player, whose native track
            // selection doesn't suffer from the ABR oscillation the collapse
            // was added to fix on MPV.
            val hint = url.substringAfter("#pv=", "")
            val preferred = hint.toIntOrNull()
            val fetched = sanitizePlaylistStart(fetchM3u8Content(url.substringBefore("#"), headers))
            val m3u8Content = if (hint == "all") fetched else keepBestVariantOnly(fetched, preferred)
            Log.d(tag, "Original M3U8 content length: ${m3u8Content.length}")
            Log.d(
                tag,
                "M3U8 head: " + m3u8Content.take(240).replace("\n", "\\n").replace("\r", ""),
            )
            warmDnsForPlaylist(m3u8Content, url)

            val referer = headers["referer"]
            val userAgent = headers["user-agent"]
            val modifiedContent = modifyM3u8Content(m3u8Content, url, port, referer, userAgent, token)
            // No head-segment warm-start: on a resume the player seeks to a
            // saved position deep in the playlist, so the first 2 segments
            // (~3 MB) were downloaded and thrown away, stealing bandwidth and
            // prefetch threads from the segment actually awaited. The
            // look-ahead in schedulePrefetch indexes off the FIRST segment the
            // player requests, which covers both cold start (0) and resume.
            playlistCachePut(cacheKey, modifiedContent)
            Log.d(tag, "Modified M3U8 content length: ${modifiedContent.length}")
            Log.d(tag, "M3U8 processing completed successfully")

            modifiedContent
        } catch (e: UpstreamStatusException) {
            Log.w(tag, "Upstream ${e.code} propagating from processM3u8Content for $url")
            throw e
        } catch (e: Exception) {
            Log.e(tag, "Error processing M3U8 URL: ${e.message}", e)
            throw UpstreamStatusException(503, url, "Error processing m3u8: ${e.message}", e)
        }
    }

    /**
     * Guarantees the playlist body starts exactly at `#EXTM3U`.
     *
     * Some CDNs (Idlix/majorplay's shaka-packager manifests included) prepend
     * a UTF-8 BOM or leading blank lines to the manifest. mpv/ffmpeg and VLC
     * tolerate that; Media3's HlsPlaylistParser is strict —
     * `Input does not start with the #EXTM3U header` — so the rewritten
     * playlist must be normalized before it reaches an ExoPlayer backend.
     */
    private fun sanitizePlaylistStart(content: String): String {
        val bomStripped = content.removePrefix("\uFEFF")
        if (bomStripped.startsWith("#EXTM3U")) return bomStripped
        val idx = bomStripped.indexOf("#EXTM3U")
        if (idx > 0) {
            Log.d(tag, "sanitizePlaylistStart: stripped $idx leading byte(s) before #EXTM3U")
            return bomStripped.substring(idx)
        }
        return bomStripped
    }

    /**
     * Collapses a master playlist to a single video variant while keeping the
     * audio/subtitle `#EXT-X-MEDIA` groups verbatim — series playback depends
     * on the audio group surviving the rewrite (mpv only handles demuxed HLS
     * audio natively through that line, not through externally added tracks).
     *
     * No-op on media playlists (they carry no `#EXT-X-STREAM-INF`), so the
     * per-variant child playlists movies already use are never touched.
     * Masters with ≤1 variant pass through unchanged.
     *
     * [preferred] — a height or bandwidth parsed from an extension-supplied
     * `#pv=` hint — selects that rendition; falls back to the best variant
     * when it matches none.
     */
    private fun keepBestVariantOnly(content: String, preferred: Int? = null): String {
        if ("#EXT-X-STREAM-INF" !in content) return content
        var pending: IntArray? = null // (height, bandwidth) of the last STREAM-INF line
        val variants = mutableListOf<Pair<IntArray, IntArray>>() // (infoIdx, uriIdx) to (height, bandwidth)
        content.lines().forEachIndexed { idx, line ->
            when {
                line.startsWith("#EXT-X-STREAM-INF:") -> {
                    val height = Regex("""RESOLUTION=\d+x(\d+)""").find(line)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                    val bandwidth = parseHlsAttributes(line)["BANDWIDTH"]?.toIntOrNull() ?: 0
                    pending = intArrayOf(height, bandwidth)
                }
                line.startsWith("#") || line.isBlank() -> Unit
                pending != null -> {
                    variants += intArrayOf(idx - 1, idx) to pending!!
                    pending = null
                }
            }
        }
        if (variants.size <= 1) return content
        val best = preferred?.let { p -> variants.firstOrNull { it.second[0] == p || it.second[1] == p } }
            ?: variants.maxWithOrNull(compareBy({ it.second[0] }, { it.second[1] })) ?: return content
        val dropIdxs = variants.filter { it !== best }.flatMap { (idxs, _) -> listOf(idxs[0], idxs[1]) }.toHashSet()
        Log.d(tag, "keepBestVariantOnly: kept ${best.second[0]}p/${best.second[1]}bps (hint=$preferred), dropped ${variants.size - 1} variant(s)")
        diag("COLLAPSE kept ${best.second[0]}p/${best.second[1]}bps hint=$preferred dropped=${variants.size - 1} (master had ${variants.size} variants)")
        return content.lines().filterIndexed { idx, _ -> idx !in dropIdxs }.joinToString("\n")
    }

    /**
     * Process segment with automatic detection. When [keyUrl] and [iv] are
     * both supplied, the segment is treated as AES-128-CBC encrypted (the
     * `#EXT-X-KEY:METHOD=AES-128` playlist path) and the bytes are decrypted
     * BEFORE junk-byte interleaving is stripped (ChillX-style obfuscation
     * may stack on top of AES-128; decryption first produces the raw TS that
     * AutoDetector then scans for image-magic-junk blocks).
     */
    suspend fun processSegmentUrl(
        url: String,
        headers: Map<String, String> = emptyMap(),
        keyUrl: String? = null,
        iv: String? = null,
    ): ByteArray = withContext(Dispatchers.IO) {
        try {
            Log.d(tag, "Fetching segment from: $url with headers: $headers (aes=${keyUrl != null})")
            // Look-ahead is scheduled AFTER this fetch finishes (see below).
            // Kicking it off BEFORE put 4 x ~1.5 MB of prefetches on the wire
            // while the player waited on the single segment it actually needed
            // — the "bandwidth flying but video stuck" resume symptom. The
            // post-fetch call keeps the lookahead window full instead.
            val rawSegment = fetchSegmentBytes(url, headers)
            val plaintext = if (keyUrl != null && iv != null) {
                val keyBytes = fetchSegmentBytes(keyUrl, headers)
                decryptAes128Cbc(rawSegment, keyBytes, iv).also {
                    Log.d(tag, "AES-128 decrypted segment: ${rawSegment.size} → ${it.size} bytes")
                }
            } else {
                rawSegment
            }
            val stripped = stripInterleavedJunk(plaintext)
            if (keyUrl == null) {
                cachePut(canonicalSegmentKey(url), stripped)
                schedulePrefetch(url, headers)
            }
            Log.d(tag, "Segment processing completed, final size: ${stripped.size} bytes")
            stripped
        } catch (e: UpstreamStatusException) {
            Log.w(tag, "Segment fetch upstream ${e.code} for $url")
            throw e
        } catch (e: Exception) {
            Log.e(tag, "Error processing segment URL: ${e.message}", e)
            throw UpstreamStatusException(503, url, "Error processing segment: ${e.message}", e)
        }
    }

    private suspend fun fetchSegmentBytes(url: String, headers: Map<String, String>): ByteArray = withContext(Dispatchers.IO) {
        Log.d(tag, "Making HTTP request to fetch segment with headers: $headers")

        val requestBuilder = Request.Builder().url(url)
        headers.forEach { (key, value) ->
            requestBuilder.addHeader(key, value)
        }
        val request = requestBuilder.build()

        fetchClient.newCall(request).execute().use { response ->
            Log.d(tag, "Segment HTTP response code: ${response.code}")
            if (!response.isSuccessful) {
                Log.e(tag, "Failed to fetch segment, HTTP code: ${response.code}")
                throw UpstreamStatusException(response.code, url, "Failed to fetch segment")
            }
            response.body.bytes()
        }
    }

    private fun stripInterleavedJunk(fullData: ByteArray): ByteArray {
        // Fast path: clean video segment (fMP4 / TS / AVI) → no junk, skip the
        // full-buffer byte scan. Scanning every segment byte-by-byte is
        // CPU-heavy (causes slow load / bandwidth drain) and can false-positive
        // strip 252-byte blocks when an image magic appears inside real video
        // data. Only obfuscated segments START with an image magic, and only
        // those need the deep interleaved scan.
        if (AutoDetector.detectSkipBytes(fullData) == 0) return fullData
        val skipRanges = AutoDetector.detectInterleavedSkips(fullData)
        if (skipRanges.isEmpty()) return fullData
        val strippedBytes = skipRanges.sumOf { it.last - it.first + 1 }
        val finalSize = fullData.size - strippedBytes
        Log.d(tag, "Stripping ${skipRanges.size} interleaved junk block(s) ($strippedBytes bytes total), final size: $finalSize bytes")
        val stripped = ByteArrayOutputStream(finalSize.coerceAtLeast(0))
        var cursor = 0
        for (range in skipRanges) {
            if (range.first > cursor) {
                stripped.write(copyRegion(fullData, cursor, range.first - cursor))
            }
            cursor = range.last + 1
        }
        if (cursor < fullData.size) {
            stripped.write(copyRegion(fullData, cursor, fullData.size - cursor))
        }
        return stripped.toByteArray()
    }

    private fun decryptAes128Cbc(data: ByteArray, key: ByteArray, iv: String): ByteArray {
        if (key.size != 16) {
            throw IOException("Invalid AES-128 key length: ${key.size}")
        }
        val normalizedIv = iv.normalizeHlsIv()
        if (normalizedIv.length != 32) {
            throw IOException("Invalid AES-128 IV length: ${normalizedIv.length}")
        }
        return try {
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key, "AES"),
                IvParameterSpec(normalizedIv.hexToByteArray()),
            )
            cipher.doFinal(data)
        } catch (e: GeneralSecurityException) {
            throw IOException("Failed to decrypt AES-128 segment", e)
        } catch (e: NumberFormatException) {
            throw IOException("Invalid AES-128 IV", e)
        }
    }

    private fun Long.toHlsIv(): String = toString(16).padStart(32, '0')

    private fun String.normalizeHlsIv(): String = removePrefix("0x").removePrefix("0X").padStart(32, '0')

    private fun String.hexToByteArray(): ByteArray = ByteArray(length / 2) { index ->
        substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }

    /**
     * Health check
     */
    fun getHealthStatus(): String = if (isRunning) {
        "M3U8 HTTP Server is running on port $port"
    } else {
        "M3U8 HTTP Server is not running"
    }

    private suspend fun fetchM3u8Content(url: String, headers: Map<String, String> = emptyMap()): String = withContext(Dispatchers.IO) {
        Log.d(tag, "Making HTTP request to fetch M3U8 content with headers: $headers")

        val requestBuilder = Request.Builder().url(url)
        headers.forEach { (key, value) ->
            // Never forward Accept-Encoding: when the CLIENT explicitly sends
            // `Accept-Encoding: gzip` (Media3's DefaultHttpDataSource does),
            // OkHttp treats it as a user-set header and SKIPS its transparent
            // gzip decompression — body.string() then returns raw gzip bytes,
            // which Media3's HlsPlaylistParser rejects
            // (`Input does not start with the #EXTM3U header`). Dropping the
            // header lets OkHttp negotiate gzip itself and decompress for us.
            if (key.equals("accept-encoding", ignoreCase = true)) return@forEach
            requestBuilder.addHeader(key, value)
        }
        val request = requestBuilder.build()

        try {
            fetchClient.newCall(request).execute().use { response ->
                Log.d(tag, "M3U8 HTTP response code: ${response.code}")

                if (!response.isSuccessful) {
                    Log.e(tag, "Failed to fetch M3U8 content, HTTP code: ${response.code}")
                    throw UpstreamStatusException(response.code, url, "Failed to fetch m3u8")
                }

                val content = response.body.string()
                if (content.isBlank()) {
                    Log.e(tag, "Empty M3U8 response body")
                    throw UpstreamStatusException(502, url, "Empty response body")
                }

                Log.d(tag, "Successfully fetched M3U8 content")
                content
            }
        } catch (primary: UpstreamStatusException) {
            throw primary
        } catch (primary: Exception) {
            // Primary client threw; if it was a CF-solve failure we are not
            // a typed exception here (lib stays agnostic of cloudflareinterceptor).
            // Use the fallback client when supplied — header-gated CDNs may
            // return 200 once the WebView detour is bypassed.
            Log.w(tag, "Primary client failed for $url: ${primary.javaClass.simpleName}: ${primary.message}; ${if (fallbackClient != null) "attempting fallback" else "no fallback configured"}")
            val fb = fallbackClient ?: throw UpstreamStatusException(503, url, "Primary client failed: ${primary.message}", primary)

            try {
                val fbClient = fetchFallbackClient ?: fb
                fbClient.newCall(request).execute().use { response ->
                    Log.d(tag, "M3U8 fallback HTTP response code: ${response.code}")
                    if (!response.isSuccessful) {
                        Log.e(tag, "Fallback also failed for M3U8, HTTP code: ${response.code}")
                        throw UpstreamStatusException(response.code, url, "Fallback failed: ${primary.message}", primary)
                    }
                    val content = response.body.string()
                    if (content.isBlank()) {
                        throw UpstreamStatusException(502, url, "Empty fallback body", primary)
                    }
                    Log.d(tag, "Fallback fetch succeeded, content length: ${content.length}")
                    content
                }
            } catch (fb: UpstreamStatusException) {
                throw fb
            } catch (fb: Exception) {
                Log.e(tag, "Fallback client threw: ${fb.javaClass.simpleName}: ${fb.message}", fb)
                throw UpstreamStatusException(503, url, "Both primary and fallback failed (primary=${primary.message}; fallback=${fb.message})", primary)
            }
        }
    }

    private fun copyRegion(src: ByteArray, off: Int, len: Int): ByteArray {
        if (off == 0 && len == src.size) return src
        if (len == 0) return ByteArray(0)
        val out = ByteArray(len)
        System.arraycopy(src, off, out, 0, len)
        return out
    }

    /**
     * Creates a local M3U8 URL that re-enters this server at `/m3u8`. The
     * caller can attach the original extension's [referer] and [userAgent]
     * so the upstream-fetch path can re-issue them even when the media
     * player (mpv / ExoPlayer) does not carry them through to localhost.
     *
     * This is the root-cause fix for Cloudflare-fronted CDNs that reject
     * null-Referer (or wrong-Referer) requests with a 403 — the m3u8 server
     * would otherwise hit the upstream with whatever headers the player
     * copied to localhost, which on most MPV integrations is "no Referer".
     */
    fun createLocalUrl(
        m3u8Url: String,
        referer: String? = null,
        userAgent: String? = null,
        token: String? = null,
    ): String {
        // NOTE: the fake `/master.m3u8` filename is load-bearing, not
        // cosmetic. External players launched via Aniyomi's long-press
        // "open in external" intent receive type `video/any` (not
        // `application/vnd.apple.mpegurl`), so VLC falls back to
        // extension-based dispatch: an extensionless `/m3u8?url=…` URL is
        // treated as a raw video file (title loads, picture stays 00:00),
        // while `/m3u8/master.m3u8?url=…` takes the HLS path and plays.
        // Routing still matches on the `/m3u8` prefix (see [handle]).
        val sb = StringBuilder("http://localhost:$port/m3u8/master.m3u8?url=")
            .append(URLEncoder.encode(m3u8Url, Charsets.UTF_8.name()))
        if (!referer.isNullOrBlank()) {
            sb.append("&referer=").append(URLEncoder.encode(referer, Charsets.UTF_8.name()))
        }
        if (!userAgent.isNullOrBlank()) {
            sb.append("&useragent=").append(URLEncoder.encode(userAgent, Charsets.UTF_8.name()))
        }
        if (!token.isNullOrBlank()) {
            sb.append("&token=").append(URLEncoder.encode(token, Charsets.UTF_8.name()))
        }
        return sb.toString()
    }

    private fun modifyM3u8Content(
        content: String,
        originalUrl: String,
        serverPort: Int,
        referer: String? = null,
        userAgent: String? = null,
        token: String? = null,
    ): String {
        Log.d(tag, "Modifying M3U8 content for server port: $serverPort (referer=${referer?.take(80) ?: "none"})")
        val lines = content.lines()
        val modifiedLines = mutableListOf<String>()
        var segmentCount = 0
        var mediaSequence = 0L
        var segmentSequence = mediaSequence
        var currentKey: HlsKey? = null
        var expectSubPlaylist = false
        val segmentUrls = mutableListOf<String>()

        val baseHttpUrl = originalUrl.toHttpUrlOrNull()

        // VLC only auto-plays an audio rendition flagged DEFAULT=YES; Idlix
        // marks its single audio group DEFAULT=NO (AUTOSELECT=YES), which
        // mpv honors but VLC ignores — VLC then plays video in silence
        // without ever requesting the audio playlist. Flip the flag when (and
        // only when) the master declares exactly one audio group, so players
        // without a track picker still get sound. Multi-audio masters are
        // left untouched to preserve the user's language choice.
        val singleAudioGroup = lines.count { it.startsWith("#EXT-X-MEDIA:") && it.contains("TYPE=AUDIO") } == 1

        for (line in lines) {
            when {
                line.startsWith("#EXTM3U") -> {
                    modifiedLines.add(line)
                    // RFC 8216 wants EXT-X-VERSION right after EXTM3U and some
                    // players (VLC's HLS parser) are strict about tag order:
                    // inject INDEPENDENT-SEGMENTS only after VERSION, or
                    // directly when the playlist declares no VERSION at all.
                    // A misplaced tag makes VLC reject the playlist and poll
                    // the manifests in a loop without ever fetching segments.
                    if ("#EXT-X-VERSION:" !in content && "#EXT-X-INDEPENDENT-SEGMENTS" !in content) {
                        modifiedLines.add("#EXT-X-INDEPENDENT-SEGMENTS")
                    }
                }
                line.startsWith("#EXT-X-VERSION:") -> {
                    modifiedLines.add(line)
                    if ("#EXT-X-INDEPENDENT-SEGMENTS" !in content) {
                        modifiedLines.add("#EXT-X-INDEPENDENT-SEGMENTS")
                    }
                }
                line.startsWith("#EXT-X-STREAM-INF:") || line.startsWith("#EXT-X-MEDIA:") -> {
                    expectSubPlaylist = true
                    // #EXT-X-MEDIA lines carry their own URI= (audio / subtitle
                    // child playlists). Rewrite it so gated CDNs get the token
                    // and the fetch re-enters the proxy (AUDIO .json/.m3u8) or
                    // gets a token-appended direct URL (SUBTITLES .vtt — not a
                    // playlist, must not be proxied or its VTT mime is lost).
                    if (line.startsWith("#EXT-X-MEDIA:")) {
                        modifiedLines.add(rewriteMediaUri(line, baseHttpUrl, referer, userAgent, token, singleAudioGroup))
                    } else {
                        modifiedLines.add(line)
                    }
                }
                line.startsWith("#EXT-X-MEDIA-SEQUENCE:") -> {
                    mediaSequence = line.substringAfter(":").trim().toLongOrNull() ?: mediaSequence
                    segmentSequence = mediaSequence
                    modifiedLines.add(line)
                }
                line.startsWith("#EXT-X-KEY:") -> {
                    val attributes = parseHlsAttributes(line)
                    when (attributes["METHOD"]?.uppercase()) {
                        "AES-128" -> {
                            val keyUri = attributes["URI"]
                            if (keyUri.isNullOrBlank()) {
                                currentKey = null
                                modifiedLines.add(line)
                            } else {
                                val resolvedKeyUrl = resolveHlsUrl(baseHttpUrl, keyUri)
                                val iv = attributes["IV"]
                                currentKey = HlsKey(
                                    url = resolvedKeyUrl,
                                    iv = iv?.let { it.normalizeHlsIv() } ?: segmentSequence.toHlsIv(),
                                )
                                Log.d(tag, "AES-128 detected, intercepting key at proxy: $resolvedKeyUrl (iv=${currentKey.iv})")
                            }
                        }
                        "NONE" -> {
                            currentKey = null
                            modifiedLines.add(line)
                        }
                        else -> {
                            currentKey = null
                            modifiedLines.add(line)
                        }
                    }
                }
                line.startsWith("#EXT-X-MAP:") -> {
                    // Init segment (fMP4 / hero.webp) needs auth token too,
                    // or player can't seek / init fMP4 stream → stuck at 21s.
                    // Proxy through local server so headers + token are added.
                    val m = Regex("""URI="([^"]*)"""").find(line)
                    if (m != null) {
                        val resolved = resolveHlsUrl(baseHttpUrl, m.groupValues[1])
                        val initBare = resolved.substringBefore("?")
                        // Stable cache identity across the CDN's host/path
                        // rotation, so a player reopen hits RAM instead of
                        // cold-fetching the init again. See [initSegmentKeys].
                        //
                        // Keyed by the INIT URL, not by the referencing
                        // playlist: Idlix's video and audio child playlists
                        // both declare the same `#EXT-X-MAP` (intro.html), and
                        // a per-playlist key let the second rewrite silently
                        // move the identity away from the bytes already cached
                        // under the first, so every track open / seek paid a
                        // cold init fetch. Same URL means the same bytes, so
                        // the two renditions share one pinned entry instead.
                        val initKey = "init#$initBare"
                        initSegmentKeys[resolved] = initKey
                        if (initBare != resolved) initSegmentKeys[initBare] = initKey
                        pinnedCacheKeys.add(initKey)
                        // Register under the stable key too: the init URL
                        // rotates like everything else, but the player-held
                        // playlist must keep working after a reload.
                        stableUpstream[initKey] = resolved
                        val proxied = createLocalStableSegmentUrl(serverPort, initKey, null, referer, userAgent, token)
                        modifiedLines.add(line.replace(m.value, "URI=\"$proxied\""))
                    } else {
                        modifiedLines.add(line)
                    }
                }
                line.startsWith("#") || line.isBlank() -> {
                    modifiedLines.add(line)
                }
                else -> {
                    val resolvedUrl = resolveHlsUrl(baseHttpUrl, line)
                    // Shaka Packager emits HLS playlists with a .json extension
                    // (config-*.json, data-*.json). Use the EXT-X-STREAM-INF /
                    // EXT-X-MEDIA flag to distinguish sub-playlists from segments.
                    if (resolvedUrl.contains(".m3u8", ignoreCase = true) || expectSubPlaylist) {
                        expectSubPlaylist = false
                        modifiedLines.add(buildChildM3u8Url(serverPort, resolvedUrl, referer, userAgent, token))
                    } else {
                        // Stable player-visible URL (see [stableUpstream]):
                        // the key survives CDN rotation, the registry always
                        // resolves to the freshest raw URL + JWT.
                        val segIndex = segmentUrls.size
                        stableUpstream[stableSegmentKey(originalUrl, segIndex)] = resolvedUrl
                        modifiedLines.add(
                            createLocalStableSegmentUrl(serverPort, stableSegmentKey(originalUrl, segIndex), currentKey, referer, userAgent, token),
                        )
                        segmentUrls.add(resolvedUrl)
                        segmentCount++
                    }
                    segmentSequence++
                }
            }
        }

        if (segmentUrls.isNotEmpty()) {
            segmentOrder[originalUrl] = segmentUrls
            segmentUrls.forEachIndexed { index, u ->
                segmentPosition[u] = originalUrl to index
                // Idlix-style playlists already carry ?t=<JWT> on each segment
                // (createLocalSegmentUrl leaves those untouched). Index the
                // bare path too so schedulePrefetch lookups resolve either way.
                val bare = u.substringBefore("?")
                if (bare != u) segmentPosition[bare] = originalUrl to index
            }
            diag("REG  ${diagTrack(originalUrl)} segments=${segmentUrls.size} order+position registered")
        } else {
            diag("REG  ${diagTrack(originalUrl)} NO SEGMENTS REGISTERED (segmentUrls empty)")
        }
        Log.d(tag, "Modified M3U8 content: $segmentCount segments redirected, ${if (currentKey != null) "AES-128 keys proxied" else "no AES keys"}")
        return modifiedLines.joinToString("\n")
    }

    private fun buildChildM3u8Url(
        serverPort: Int,
        m3u8Url: String,
        referer: String?,
        userAgent: String?,
        token: String?,
    ): String {
        // Fake `.m3u8` filename for the same extension-based dispatch reason
        // documented on [createLocalUrl]: child playlists are opened by the
        // player as sub-resources of the master, and VLC needs the hint.
        val sb = StringBuilder("http://localhost:$serverPort/m3u8/child.m3u8?url=")
            .append(URLEncoder.encode(m3u8Url, Charsets.UTF_8.name()))
        if (!referer.isNullOrBlank()) {
            sb.append("&referer=").append(URLEncoder.encode(referer, Charsets.UTF_8.name()))
        }
        if (!userAgent.isNullOrBlank()) {
            sb.append("&useragent=").append(URLEncoder.encode(userAgent, Charsets.UTF_8.name()))
        }
        if (!token.isNullOrBlank()) {
            sb.append("&token=").append(URLEncoder.encode(token, Charsets.UTF_8.name()))
        }
        return sb.toString()
    }

    private data class HlsKey(val url: String, val iv: String)

    private val hlsAttributeRegex = Regex("""([A-Z0-9-]+)=("[^"]*"|[^,]*)""")

    private fun parseHlsAttributes(line: String): Map<String, String> = hlsAttributeRegex.findAll(line.substringAfter(":")).associate {
        it.groupValues[1] to it.groupValues[2].trim('"')
    }

    private fun resolveHlsUrl(baseHttpUrl: HttpUrl?, uri: String): String = baseHttpUrl?.resolve(uri)?.toString() ?: uri

    /**
     * Rewrites the `URI="…"` attribute of an `#EXT-X-MEDIA` line.
     *
     * - AUDIO (and any `.m3u8`/`.json` child playlist) is re-routed through
     *   the local proxy so the upstream fetch carries the token + headers
     *   that the master playlist itself was fetched with.
     * - SUBTITLES `.vtt` is left direct but gets the `?t=` token appended,
     *   otherwise the gated CDN 404s the subtitle fetch.
     */
    private fun rewriteMediaUri(
        line: String,
        baseHttpUrl: HttpUrl?,
        referer: String?,
        userAgent: String?,
        token: String?,
        singleAudioGroup: Boolean = false,
    ): String {
        val m = Regex("""URI="([^"]*)"""").find(line) ?: return line
        val resolved = resolveHlsUrl(baseHttpUrl, m.groupValues[1])
        val isSubtitle = line.contains("TYPE=SUBTITLES") || resolved.contains(".vtt", ignoreCase = true)
        // Track the playlist as non-video so schedulePrefetch uses the
        // reduced look-ahead (bandwidth belongs to the video track).
        nonVideoPlaylists.add(resolved)
        diag("MEDIA ${if (isSubtitle) "subtitle" else "audio"} registered as non-video: ${resolved.take(90)}")
        val newUri = if (isSubtitle) {
            appendToken(resolved, token)
        } else {
            buildChildM3u8Url(port, resolved, referer, userAgent, token)
        }
        var rewritten = line.replace(m.value, "URI=\"$newUri\"")
        if (!isSubtitle && singleAudioGroup && rewritten.contains("TYPE=AUDIO") && rewritten.contains("DEFAULT=NO")) {
            rewritten = rewritten.replace("DEFAULT=NO", "DEFAULT=YES")
        }
        return rewritten
    }

    private fun appendToken(url: String, token: String?): String {
        if (token.isNullOrBlank() || hasTokenParam(url)) return url
        val separator = if (url.contains('?')) "&" else "?"
        return "$url${separator}t=$token&pm=browser"
    }

    /**
     * True when the URL already carries a `t=` query parameter. Idlix-style
     * playlists embed a per-segment JWT on every URI; blindly appending a
     * second `t=` yields `?t=A&t=B&pm=browser`, which gated CDNs reject and
     * which desyncs the prefetch cache key. Only append when absent.
     */
    private fun hasTokenParam(url: String): Boolean = url.contains(Regex("""[?&]t=[^&]*"""))

    /**
     * Player-visible `/segment` URL carrying a stable [stableUpstream] key
     * (`k=`) instead of the raw upstream URL. The key resolves server-side to
     * the freshest host/path/JWT on every request, so a playlist the player
     * holds stays valid across CDN rotations and manifest reloads — older
     * ffmpeg demuxers match segments across reloads by URL and wedge
     * (`sequence may have been wrapped`) when every URL changes at once.
     *
     * Auth (`t=`) is resolved at request time from the registered raw URL
     * (which already carries the fresh JWT); [token] is still forwarded so
     * the resolver path keeps the same header/token behavior as
     * [createLocalSegmentUrl] for JWT-less CDNs.
     *
     * The path carries a fake `.m4s` filename: some players (VLC's adaptive
     * demuxer) key stream identification off the URL filename and stall at
     * 00:00 on extensionless `/segment?k=…` URLs even though the bytes and
     * Content-Type are correct. Routing still matches on the `/segment`
     * prefix, so this is purely cosmetic for us and decisive for them.
     */
    private fun createLocalStableSegmentUrl(
        serverPort: Int,
        stableKey: String,
        key: HlsKey?,
        referer: String? = null,
        userAgent: String? = null,
        token: String? = null,
    ): String = buildString {
        append("http://localhost:$serverPort/segment/s.m4s?k=")
        append(URLEncoder.encode(stableKey, Charsets.UTF_8.name()))
        if (key != null) {
            append("&key=")
            append(URLEncoder.encode(key.url, Charsets.UTF_8.name()))
            append("&iv=")
            append(URLEncoder.encode(key.iv, Charsets.UTF_8.name()))
        }
        if (!referer.isNullOrBlank()) {
            append("&referer=")
            append(URLEncoder.encode(referer, Charsets.UTF_8.name()))
        }
        if (!userAgent.isNullOrBlank()) {
            append("&useragent=")
            append(URLEncoder.encode(userAgent, Charsets.UTF_8.name()))
        }
        if (!token.isNullOrBlank()) {
            append("&token=")
            append(URLEncoder.encode(token, Charsets.UTF_8.name()))
        }
    }
}
