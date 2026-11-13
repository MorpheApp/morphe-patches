/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4764
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.shared.spoof;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Base64;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.ConsoleMessage;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.annotation.GuardedBy;
import androidx.annotation.Nullable;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

/**
 * The streaming session of a video made by the official mobile website.
 * <p>
 * A hidden WebView opens the watch page of the video, with the web sign in of the user if there is one,
 * and the player of the website makes its PoToken and its streaming session itself.
 * A SABR request of the page gives them, and the media requests of the page are then held,
 * so the page never downloads the media.
 * <p>
 * The page also never reports the playback (watch history, statistics), never loads ads,
 * and never loads images or fonts.
 */
public final class MwebSiteSession {

    private static final String JS_INTERFACE = "MorpheMwebSite";

    private static final String WATCH_URL = "https://m.youtube.com/watch?v=";

    /**
     * The cookie of the cookie consent of YouTube, with all cookies rejected, as the "Reject all" button sets it.
     * Without a consent, the page shows the consent dialog, and its player does not start.
     */
    private static final String CONSENT_REJECTED_COOKIE =
            "SOCS=CAE; Domain=.youtube.com; Path=/; Secure; SameSite=Lax; Max-Age=34128000";

    /**
     * How often the player of the page is checked while a session is made.
     */
    private static final long WATCHDOG_INTERVAL_MILLISECONDS = 1000;

    /**
     * If the player of the page has made no SABR request this long after the video was opened,
     * it is started, as it does not always start by itself in a hidden page.
     */
    private static final long START_PLAYER_DELAY_MILLISECONDS = 3000;

    /**
     * If the player of the page has made no SABR request for this long, without the PoToken yet,
     * it has stopped making them again, so the video is loaded again in the player.
     * The player usually sends the PoToken about 5 seconds after its first requests.
     */
    private static final long STALLED_PLAYER_MILLISECONDS = 7000;

    private static final String START_PLAYER_SCRIPT = "(function(){var p=document.getElementById('movie_player');"
            + "if(p&&p.playVideo){p.playVideo();" + JS_INTERFACE + ".onLog('player started');}"
            + "else{" + JS_INTERFACE + ".onLog('no player in the page');}})();";

    /**
     * Loads the video again in the player of the page. The video id is added with String.format.
     */
    private static final String RELOAD_PLAYER_SCRIPT = "(function(){var p=document.getElementById('movie_player');"
            + "if(p&&p.loadVideoById){p.loadVideoById('%s');" + JS_INTERFACE + ".onLog('player loaded the video again');}})();";


    /**
     * The website makes a session in about 6 seconds, so a longer wait means it does not make it,
     * for example when the app is in the background and the page is paused.
     * The app waits for the streams of a video for 20 seconds in all,
     * so the next client still has time to fetch them.
     */
    private static final long TIMEOUT_MILLISECONDS = 12 * 1000;

    /**
     * A started fetch is used by the fetch of the streams of its video only for this long.
     */
    private static final long STARTED_FETCH_MAX_AGE_MILLISECONDS = 60 * 1000;

    /**
     * Pages not used for this long are closed.
     */
    private static final long IDLE_CLOSE_MILLISECONDS = 5 * 60 * 1000;

    /**
     * Videos whose sessions are made at the same time, as the app fetches the streams of more than one video
     * at a time. Each has its own page.
     */
    private static final int MAX_PAGES = 2;

    /**
     * A PoToken made by BotGuard is about 90 bytes or more. The placeholder of a cold start is about 10 bytes:
     * the player sends it while BotGuard has not made the PoToken yet.
     */
    private static final int MIN_PO_TOKEN_LENGTH = 50;

    /**
     * Errors of the page logged for each video.
     */
    private static final int MAX_LOGGED_ERRORS = 5;

    /**
     * Requests of the page that are answered with an empty response.
     * Playback reports (watch history, statistics), logs, ads, images and fonts.
     */
    private static final String[] BLOCKED_URL_PARTS = {
            "/api/stats/",
            "/ptracking",
            "/youtubei/v1/log_event",
            "/youtubei/v1/feedback",
            "/youtubei/v1/player/ad_break",
            "/generate_204",
            "/csi_204",
            "/pagead/",
            "/get_midroll_",
            "play.google.com/log",
            "doubleclick.net",
            "googlesyndication.com",
            "googleadservices.com",
            "i.ytimg.com",
            "yt3.ggpht.com",
            "yt3.googleusercontent.com",
            "fonts.gstatic.com",
            "fonts.googleapis.com",
    };

    /**
     * Wraps fetch and XMLHttpRequest of the page, and sends its SABR requests to Java.
     * The media requests of the page fail, so the player makes them again, until the session is taken.
     * Then they are held, so the player waits and makes no more requests.
     * <p>
     * It runs with evaluateJavascript, which the content security policy of the page does not limit.
     * The player loads after the page has started, so it uses the wrapped functions.
     */
    private static final String HOOK_SCRIPT = "(function(){"
            + "if(window.__morpheHook)return;window.__morpheHook=1;window.__morpheHold=false;"
            + "function b64(u8){var s='';for(var i=0;i<u8.length;i+=32768){s+=String.fromCharCode.apply(null,u8.subarray(i,i+32768));}return btoa(s);}"
            + "function isMedia(url){return typeof url==='string'&&url.indexOf('googlevideo.com/videoplayback')>=0;}"
            + "function player(){return document.getElementById('movie_player');}"
            + "function adShowing(){var p=player();return !!(p&&p.classList&&(p.classList.contains('ad-showing')||p.classList.contains('ad-interrupting')));}"
            + "function playerVideoId(){try{var p=player();var d=p&&p.getVideoData&&p.getVideoData();return (d&&d.video_id)||'';}catch(e){return '';}}"
            + "function pageVideoId(){try{return new URL(location.href).searchParams.get('v')||'';}catch(e){return '';}}"
            + "function send(url,body){try{var u8=body instanceof Uint8Array?body:new Uint8Array(body instanceof ArrayBuffer?body:body.buffer);"
            + JS_INTERFACE + ".onSabrRequest(url,b64(u8),pageVideoId(),playerVideoId(),adShowing());}catch(e){"
            + JS_INTERFACE + ".onLog('send failed: '+e);}}"
            + "function refuse(){return window.__morpheHold?new Promise(function(){}):Promise.reject(new TypeError('blocked'));}"
            + "var of=window.fetch;window.fetch=function(input,init){"
            + "var url=input instanceof Request?input.url:String(input);"
            + "if(isMedia(url)){"
            + "if(!window.__morpheHold){"
            + "if(init&&init.body){send(url,init.body);}"
            + "else if(input instanceof Request){input.clone().arrayBuffer().then(function(a){if(a.byteLength)send(url,a);});}}"
            + "return refuse();}"
            + "return of.apply(this,arguments);};"
            + "var oo=XMLHttpRequest.prototype.open,os=XMLHttpRequest.prototype.send;"
            + "XMLHttpRequest.prototype.open=function(m,url){this.__u=String(url);return oo.apply(this,arguments);};"
            + "XMLHttpRequest.prototype.send=function(body){if(isMedia(this.__u)){"
            + "if(!window.__morpheHold){if(body)send(this.__u,body);this.abort();}return;}"
            + "return os.apply(this,arguments);};"
            + JS_INTERFACE + ".onLog('hook installed');"
            + "})();";

    /**
     * The streaming session of the website for a video.
     */
    public static final class Session {
        public final String serverAbrStreamingUrl;
        @Nullable
        public final byte[] clientInfo;
        @Nullable
        public final byte[] poToken;
        @Nullable
        public final byte[] ustreamerConfig;

        /**
         * @param requestUrl  Url of a SABR request of the website.
         * @param requestBody Body of a SABR request of the website.
         */
        Session(String requestUrl, byte[] requestBody) throws IOException {
            // The number of the request is added by each player to its own requests.
            Uri uri = Uri.parse(requestUrl);
            Uri.Builder builder = uri.buildUpon().clearQuery();
            for (String name : uri.getQueryParameterNames()) {
                if (!"rn".equals(name)) {
                    builder.appendQueryParameter(name, uri.getQueryParameter(name));
                }
            }
            this.serverAbrStreamingUrl = builder.build().toString();
            this.ustreamerConfig = SabrRequestBody.readMessageField(requestBody,
                    SabrRequestBody.REQUEST_USTREAMER_CONFIG_FIELD);
            byte[] streamerContext = SabrRequestBody.readMessageField(requestBody,
                    SabrRequestBody.REQUEST_STREAMER_CONTEXT_FIELD);
            this.clientInfo = streamerContext == null ? null : SabrRequestBody.readMessageField(streamerContext,
                    SabrRequestBody.STREAMER_CONTEXT_CLIENT_INFO_FIELD);
            this.poToken = streamerContext == null ? null : SabrRequestBody.readMessageField(streamerContext,
                    SabrRequestBody.STREAMER_CONTEXT_PO_TOKEN_FIELD);
        }
    }

    private record StartedFetch(Future<Session> future, long startTime) {
    }

    private record PageLoad(Page page, CompletableFuture<Session> session) {
    }

    private static final ExecutorService executor = Executors.newFixedThreadPool(MAX_PAGES, runnable -> {
        Thread thread = new Thread(runnable, "MwebSiteSession");
        thread.setDaemon(true);
        return thread;
    });

    /**
     * Fetches started before the player request of their video, by video.
     */
    private static final Map<String, StartedFetch> startedFetches =
            Collections.synchronizedMap(Utils.createSizeRestrictedMap(10));

    /**
     * The last session of each video, used by its media requests.
     */
    private static final Map<String, Session> sessions =
            Collections.synchronizedMap(Utils.createSizeRestrictedMap(20));

    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    private static final Runnable closeIdlePages = MwebSiteSession::closeFreePages;

    /**
     * Pages that are open and not used.
     */
    @GuardedBy("freePages")
    private static final ArrayDeque<Page> freePages = new ArrayDeque<>();

    /**
     * Pages made before a reset are closed when they are no longer used.
     */
    private static volatile int generation;

    private MwebSiteSession() {
    }

    /**
     * @return The last session of the video, or null if there is none.
     */
    @Nullable
    public static Session getSession(@Nullable String videoId) {
        return videoId == null ? null : sessions.get(videoId);
    }

    /**
     * Forgets the sessions and closes the pages, for example after a sign in or a sign out.
     */
    public static void reset() {
        generation++;
        sessions.clear();
        startedFetches.clear();
        Utils.runOnMainThread(MwebSiteSession::closeFreePages);
    }

    /**
     * Starts the fetch of the session of a video, so it runs while the player request is made.
     */
    public static void start(String videoId) {
        synchronized (startedFetches) {
            StartedFetch started = startedFetches.get(videoId);
            if (started != null && !isTooOld(started)) {
                return;
            }
            startedFetches.put(videoId, new StartedFetch(executor.submit(() -> load(videoId)),
                    SystemClock.elapsedRealtime()));
        }
    }

    private static boolean isTooOld(StartedFetch started) {
        return SystemClock.elapsedRealtime() - started.startTime > STARTED_FETCH_MAX_AGE_MILLISECONDS;
    }

    /**
     * Waits for the session of a video, and starts its fetch if it was not started.
     * Each fetch of the streams uses a new session, as the website does when the video is opened again.
     * Blocking call, and must be made off the main thread.
     *
     * @return The session, or null if the website did not give one.
     */
    @Nullable
    public static Session fetch(String videoId) {
        Utils.verifyOffMainThread();
        StartedFetch started;
        synchronized (startedFetches) {
            started = startedFetches.remove(videoId);
            if (started == null || isTooOld(started)) {
                started = new StartedFetch(executor.submit(() -> load(videoId)), SystemClock.elapsedRealtime());
            }
        }
        try {
            return started.future.get(TIMEOUT_MILLISECONDS, TimeUnit.MILLISECONDS);
        } catch (Exception ex) {
            Logger.printInfo(() -> "Could not get the website session of " + videoId, ex);
            return null;
        }
    }

    /**
     * Opens the video in a page and waits for a SABR request of its player. Runs on the executor.
     */
    @Nullable
    private static Session load(String videoId) {
        final long start = System.currentTimeMillis();
        CompletableFuture<PageLoad> pageLoadFuture = new CompletableFuture<>();
        Utils.runOnMainThread(() -> {
            Page page = null;
            try {
                page = takePage();
                pageLoadFuture.complete(new PageLoad(page, page.load(videoId)));
            } catch (Exception ex) {
                if (page != null) {
                    releasePage(page, false);
                }
                pageLoadFuture.completeExceptionally(ex);
            }
        });
        Page page = null;
        boolean pageWorks = false;
        try {
            PageLoad pageLoad = pageLoadFuture.get(TIMEOUT_MILLISECONDS, TimeUnit.MILLISECONDS);
            page = pageLoad.page;
            final long remaining = TIMEOUT_MILLISECONDS - (System.currentTimeMillis() - start);
            Session session = pageLoad.session.get(Math.max(remaining, 0), TimeUnit.MILLISECONDS);
            pageWorks = true;
            sessions.put(videoId, session);
            final int blocked = page.blockedRequests.getAndSet(0);
            Logger.printDebug(() -> "Website session of " + videoId + " in "
                    + (System.currentTimeMillis() - start) + " ms, PoToken bytes: "
                    + (session.poToken == null ? 0 : session.poToken.length)
                    + ", ustreamer config: " + (session.ustreamerConfig != null)
                    + ", client: " + (session.clientInfo != null)
                    + ", blocked page requests: " + blocked);
            return session;
        } catch (Exception ex) {
            Logger.printInfo(() -> "The website gave no session for " + videoId + " in "
                    + (System.currentTimeMillis() - start) + " ms", ex);
            return null;
        } finally {
            if (page != null) {
                final Page usedPage = page;
                final boolean keep = pageWorks;
                // A page that gave no session can be stuck, so it is closed.
                Utils.runOnMainThread(() -> releasePage(usedPage, keep));
            } else {
                // The page was not ready in time, and it is closed when it is.
                pageLoadFuture.thenAccept(pageLoad ->
                        Utils.runOnMainThread(() -> releasePage(pageLoad.page, false)));
            }
        }
    }

    /**
     * Main thread only.
     */
    private static Page takePage() {
        mainHandler.removeCallbacks(closeIdlePages);
        Page page;
        synchronized (freePages) {
            page = freePages.poll();
        }
        if (page == null) {
            Logger.printDebug(() -> "Website session: making a new page");
            page = new Page(generation);
        }
        page.attachToCurrentActivity();
        return page;
    }

    /**
     * Main thread only.
     */
    private static void releasePage(Page page, boolean keep) {
        page.finishLoad();
        if (keep && page.generation == generation && !page.rendererGone) {
            synchronized (freePages) {
                freePages.add(page);
            }
        } else {
            page.close();
        }
        mainHandler.removeCallbacks(closeIdlePages);
        mainHandler.postDelayed(closeIdlePages, IDLE_CLOSE_MILLISECONDS);
    }

    /**
     * Main thread only.
     */
    private static void closeFreePages() {
        mainHandler.removeCallbacks(closeIdlePages);
        synchronized (freePages) {
            for (Page page; (page = freePages.poll()) != null; ) {
                page.close();
            }
        }
    }

    private static boolean isBlocked(String url) {
        for (String part : BLOCKED_URL_PARTS) {
            if (url.contains(part)) {
                return true;
            }
        }
        return false;
    }

    /**
     * A hidden page of the website, which makes the session of one video at a time.
     */
    private static final class Page {
        final int generation;
        final WebView webView;
        /**
         * Requests of the page answered with an empty response, since the last session.
         */
        final AtomicInteger blockedRequests = new AtomicInteger();

        @Nullable
        volatile CompletableFuture<Session> pending;
        @Nullable
        volatile String pendingVideoId;

        /**
         * Errors of the page logged for the pending video.
         */
        final AtomicInteger loggedErrors = new AtomicInteger();

        /**
         * If the player of the page has made a SABR request for the pending video.
         */
        volatile boolean sabrRequestSeen;

        /**
         * {@link SystemClock#elapsedRealtime()} when the pending video was opened,
         * and of the last SABR request of its player.
         */
        volatile long openTime;
        volatile long lastRequestTime;

        /**
         * If the renderer of the page is gone, so the page cannot be used again.
         */
        volatile boolean rendererGone;

        /**
         * Checks the player of the page while a session is made. Main thread only.
         */
        final Runnable watchdog = this::checkPlayer;

        /**
         * Main thread only.
         */
        @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
        Page(int generation) {
            this.generation = generation;
            webView = new WebView(Utils.getContext());
            WebSettings settings = webView.getSettings();
            settings.setJavaScriptEnabled(true);
            settings.setDomStorageEnabled(true);
            settings.setMediaPlaybackRequiresUserGesture(false);
            settings.setLoadsImagesAutomatically(false);
            settings.setUserAgentString(ClientType.MWEB.getUserAgent());
            webView.addJavascriptInterface(new Bridge(this), JS_INTERFACE);
            //noinspection WrongConstant
            webView.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false);
            webView.setVisibility(View.INVISIBLE);
            webView.setWebChromeClient(new WebChromeClient() {
                @Override
                public boolean onConsoleMessage(ConsoleMessage message) {
                    if (message.messageLevel() == ConsoleMessage.MessageLevel.ERROR
                            && loggedErrors.incrementAndGet() <= MAX_LOGGED_ERRORS) {
                        Logger.printDebug(() -> "Website session: page error: " + message.message());
                    }
                    return true;
                }
            });
            webView.setWebViewClient(new WebViewClient() {
                @Override
                public void onPageStarted(WebView v, String url, Bitmap favicon) {
                    // Only the host and the path, since the query can name the account.
                    Uri uri = Uri.parse(url);
                    Logger.printDebug(() -> "Website session: page " + uri.getHost() + uri.getPath());
                    v.evaluateJavascript(HOOK_SCRIPT, null);
                }

                @Override
                public void onPageCommitVisible(WebView v, String url) {
                    v.evaluateJavascript(HOOK_SCRIPT, null);
                }

                @Override
                public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest request) {
                    // Never leave the website, for example to open the app.
                    return !"https".equals(request.getUrl().getScheme());
                }

                @Override
                public boolean onRenderProcessGone(WebView v, RenderProcessGoneDetail detail) {
                    // All WebViews of the app share the renderer, which Android can stop to free memory.
                    // The page is closed, and the next video opens a new one.
                    Logger.printInfo(() -> "Website session: the page renderer is gone, crashed: "
                            + detail.didCrash());
                    rendererGone = true;
                    CompletableFuture<Session> future = pending;
                    if (future != null) {
                        future.completeExceptionally(new IllegalStateException("The page renderer is gone"));
                    }
                    return true;
                }

                @Nullable
                @Override
                public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest request) {
                    if (isBlocked(request.getUrl().toString())) {
                        blockedRequests.incrementAndGet();
                        // The page can fetch them from its own origin, so the empty response allows it.
                        return new WebResourceResponse("text/plain", "utf-8", 204, "No Content",
                                Collections.singletonMap("Access-Control-Allow-Origin", "*"),
                                new ByteArrayInputStream(new byte[0]));
                    }
                    return null;
                }
            });
        }

        /**
         * A WebView not in a window does not count towards the priority of its renderer,
         * which then can be frozen. Main thread only.
         */
        void attachToCurrentActivity() {
            Activity activity = Utils.getActivity();
            if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
                Logger.printDebug(() -> "Website session: no activity, the page is not in a window");
                return;
            }
            ViewGroup decorView = (ViewGroup) activity.getWindow().getDecorView();
            if (webView.getParent() == decorView) {
                return;
            }
            if (webView.getParent() instanceof ViewGroup parent) {
                parent.removeView(webView);
            }
            decorView.addView(webView, new ViewGroup.LayoutParams(1, 1));
        }

        /**
         * Starts the player of the page if it has not started by itself,
         * and loads the video again if the player has stopped before it sent the PoToken.
         */
        private void checkPlayer() {
            String videoId = pendingVideoId;
            if (pending == null || videoId == null || rendererGone) {
                return;
            }
            final long now = SystemClock.elapsedRealtime();
            if (!sabrRequestSeen) {
                if (now - openTime >= START_PLAYER_DELAY_MILLISECONDS) {
                    openTime = now;
                    webView.evaluateJavascript(START_PLAYER_SCRIPT, null);
                }
            } else if (now - lastRequestTime >= STALLED_PLAYER_MILLISECONDS) {
                lastRequestTime = now;
                webView.evaluateJavascript(String.format(RELOAD_PLAYER_SCRIPT, videoId), null);
            }
            mainHandler.postDelayed(watchdog, WATCHDOG_INTERVAL_MILLISECONDS);
        }

        /**
         * Opens the watch page of a video. Main thread only.
         */
        CompletableFuture<Session> load(String videoId) {
            CompletableFuture<Session> future = new CompletableFuture<>();
            pendingVideoId = videoId;
            pending = future;
            blockedRequests.set(0);
            sabrRequestSeen = false;
            loggedErrors.set(0);
            openTime = SystemClock.elapsedRealtime();
            // All cookies of YouTube are always rejected in the cookie consent.
            CookieManager.getInstance().setCookie("https://m.youtube.com", CONSENT_REJECTED_COOKIE);
            // A new page for each video: the player of an open page plays the next video much later.
            webView.loadUrl(WATCH_URL + Uri.encode(videoId));
            mainHandler.removeCallbacks(watchdog);
            mainHandler.postDelayed(watchdog, WATCHDOG_INTERVAL_MILLISECONDS);
            return future;
        }

        /**
         * Main thread only.
         */
        void finishLoad() {
            mainHandler.removeCallbacks(watchdog);
            pending = null;
            pendingVideoId = null;
            if (rendererGone) {
                return;
            }
            // The player of the page waits, and makes no more requests.
            webView.evaluateJavascript("window.__morpheHold=true;", null);
        }

        /**
         * Main thread only.
         */
        void close() {
            Logger.printDebug(() -> "Website session: closing a page");
            mainHandler.removeCallbacks(watchdog);
            if (webView.getParent() instanceof ViewGroup parent) {
                parent.removeView(webView);
            }
            webView.destroy();
        }
    }

    private static final class Bridge {
        private final Page page;

        Bridge(Page page) {
            this.page = page;
        }

        /**
         * @param pageVideoId   Video of the page address, or empty.
         * @param playerVideoId Video of the player of the page, or empty.
         * @param adShowing     If the player of the page shows an ad.
         */
        @JavascriptInterface
        public void onSabrRequest(String url, String bodyBase64, String pageVideoId,
                                  String playerVideoId, boolean adShowing) {
            CompletableFuture<Session> future = page.pending;
            String videoId = page.pendingVideoId;
            if (future == null || future.isDone() || videoId == null) {
                return;
            }
            page.sabrRequestSeen = true;
            page.lastRequestTime = SystemClock.elapsedRealtime();
            try {
                if (adShowing) {
                    Logger.printDebug(() -> "Website session: ignoring a request of an ad");
                    return;
                }
                if ((!pageVideoId.isEmpty() && !pageVideoId.equals(videoId))
                        || (!playerVideoId.isEmpty() && !playerVideoId.equals(videoId))) {
                    Logger.printDebug(() -> "Website session: ignoring a request of another video, page: "
                            + pageVideoId + " player: " + playerVideoId + " wanted: " + videoId);
                    return;
                }
                Session session = new Session(url, Base64.decode(bodyBase64, Base64.DEFAULT));
                final int poTokenLength = session.poToken == null ? 0 : session.poToken.length;
                if (poTokenLength < MIN_PO_TOKEN_LENGTH) {
                    // The player sends the PoToken with the next requests,
                    // which it makes again since the media requests fail.
                    Logger.printDebug(() -> "Website session: waiting for the PoToken, request PoToken bytes: "
                            + poTokenLength);
                    return;
                }
                future.complete(session);
            } catch (Exception ex) {
                future.completeExceptionally(ex);
            }
        }

        @JavascriptInterface
        public void onLog(String message) {
            Logger.printDebug(() -> "Website session: " + message);
        }
    }
}
