package eu.kanade.tachiyomi.animeextension.id.animesail

import android.annotation.SuppressLint
import android.app.Application
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import okhttp3.Headers
import uy.kohesive.injekt.injectLazy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Headless WebView resolver untuk semua server AnimeSail.
 *
 * - Player lokal (`tools/lokal/?token=...`): token divalidasi terhadap sesi yang
 *   dibuat halaman episode + dilindungi Cloudflare. WebView harus membuka halaman
 *   episode dulu (fase 1) untuk membangun cookie/origin seperti browser asli,
 *   lalu membuka player (fase 2). Redirect `error.php` berarti sesi belum valid:
 *   kembali ke episode dan coba buka player sekali lagi.
 * - Embed eksternal (Acefile, StreamWish, dll.): load langsung, tangkap `.mp4`/`.m3u8`.
 */
class LokalWebViewResolver(private val globalHeaders: Headers) {

    private val context: Application by injectLazy()
    private val handler by lazy { Handler(Looper.getMainLooper()) }

    data class Result(
        val url: String?,
        val referer: String?,
        /** Iframe doply/playmogo yang tertangkap (player proxy gideo). */
        val embedUrl: String? = null,
    )

    @SuppressLint("SetJavaScriptEnabled")
    fun resolve(pageUrl: String, referer: String? = null, timeoutSec: Long = DEFAULT_TIMEOUT_SEC): Result {
        val latch = CountDownLatch(1)
        var webView: WebView? = null
        var mediaUrl: String? = null
        var mediaReferer: String? = null
        var embedUrl: String? = null
        var retried = false
        var pageLoaded = false
        val isLokal = pageUrl.contains("/tools/lokal/")

        fun log(message: String) {
            // ponytail: probe diagnostik sementara; hapus setelah fix terverifikasi.
            Log.e("AnimesailProbe", "resolve: $message")
        }

        fun load(v: WebView, target: String) {
            val extraHeaders = referer?.takeIf { it.isNotBlank() }
                ?.let { mapOf("Referer" to it) } ?: emptyMap()
            v.loadUrl(target, extraHeaders)
        }

        handler.post {
            val webview = WebView(context)
            webView = webview
            with(webview.settings) {
                javaScriptEnabled = true
                domStorageEnabled = true
                mediaPlaybackRequiresUserGesture = false
                userAgentString = WebSettings.getDefaultUserAgent(context)
            }
            CookieManager.getInstance().setAcceptThirdPartyCookies(webview, true)
            webview.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest,
                ): WebResourceResponse? {
                    val url = request.url.toString()
                    // /utils/player/ = halaman player internal (mis. pdrive v2
                    // berakhiran .mp4 di query id) — bukan stream.
                    val isInternalPlayer = url.contains("/utils/player/")
                    if (!isInternalPlayer && STREAM_REGEX.containsMatchIn(url)) {
                        log("stream caught: $url")
                        mediaUrl = url
                        mediaReferer = request.requestHeaders?.get("Referer")
                            ?: request.requestHeaders?.get("referer")
                            ?: referer
                        latch.countDown()
                    } else if (url.contains("doply") || url.contains("playmogo")) {
                        // Iframe Udon di dalam player proxy: stream tak akan keluar
                        // via WebView (CF nested) — kirim embed untuk unpack via HTTP.
                        log("embed caught: $url")
                        embedUrl = url
                        latch.countDown()
                    }
                    return super.shouldInterceptRequest(view, request)
                }

                override fun onPageFinished(view: WebView, url: String) {
                    log("page finished: $url")
                    when {
                        // Fase 1 selesai: sesi episode siap, buka player lokal.
                        isLokal && !url.contains("error.php") && !pageLoaded &&
                            mediaUrl == null -> {
                            pageLoaded = true
                            load(view, pageUrl)
                        }
                        // Sesi ditolak → bangun ulang sesi lalu coba player sekali lagi.
                        isLokal && url.contains("error.php") && !retried -> {
                            retried = true
                            pageLoaded = false
                            referer?.let { load(view, it) }
                        }
                    }
                    // CF interstitial (403#3): hanya Lokal yang di-reload sekali
                    // (pass kedua sering lolos dengan cookie pass pertama).
                    // gideo TIDAK di-reload — iframe-nya ditangkap via JS;
                    // embed eksternal tidak butuh reload.
                    if (isLokal && pageLoaded && !retried &&
                        mediaUrl == null && embedUrl == null &&
                        !url.contains("error.php") && url != referer
                    ) {
                        retried = true
                        handler.postDelayed({
                            if (mediaUrl == null && embedUrl == null) {
                                log("reload cf: $url")
                                load(view, url)
                            }
                        }, 4000)
                    }
                    // Player proxy gideo: iframe doply/playmogo sering dibuat JS
                    // setelah challenge; baca src-nya langsung dari DOM.
                    if (GIDEO_REGEX.containsMatchIn(url) && mediaUrl == null && embedUrl == null) {
                        handler.postDelayed({
                            view.evaluateJavascript("(document.querySelector('iframe')||{}).src||''") { r ->
                                val src = r?.trim('"', ' ')
                                if (!src.isNullOrBlank() && (src.contains("doply") || src.contains("playmogo"))) {
                                    log("embed found: $src")
                                    embedUrl = src
                                    latch.countDown()
                                }
                            }
                        }, 1500)
                    }
                }
            }

            // Episode-first untuk lokal: fase 1 halaman episode, fase 2 player.
            if (isLokal && referer != null) {
                log("phase 1: episode $referer")
                load(webview, referer)
            } else {
                log("direct: $pageUrl")
                load(webview, pageUrl)
            }
        }

        latch.await(timeoutSec, TimeUnit.SECONDS)
        log("done: url=${mediaUrl != null}")

        handler.post {
            webView?.stopLoading()
            webView?.destroy()
            webView = null
        }

        return Result(mediaUrl, mediaReferer, embedUrl)
    }

    companion object {
        // Timeout per tipe server: gideo gagal cepat (iframe muncul <3s kalau
        // lolos CF), Lokal butuh fase 1+2+reload, embed jwplayer lambat ~12s.
        const val GIDEO_TIMEOUT_SEC = 8L
        const val LOKAL_TIMEOUT_SEC = 14L
        const val DEFAULT_TIMEOUT_SEC = 20L

        // Anchor (?|$): tanpa ini ".mp4" match di "www.mp4upload.com" —
        // script rocket-loader pernah tertangkap sebagai "stream".
        private val STREAM_REGEX = Regex("""\.(mp4|m3u8)(\?|$)""")
        val GIDEO_REGEX = Regex("""/utils/player/(gideo|popup)/""")
    }
}
