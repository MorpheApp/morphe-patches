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
import android.webkit.WebResourceError;
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
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

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
 * The session is taken only with the PoToken of the video, so the server sends the media from the first request.
 * A new page starts BotGuard, which makes the first PoToken in several seconds, and sends a placeholder PoToken
 * until then. The next videos are loaded in the player of the same page, which makes their PoTokens at once,
 * so a page is opened while the app starts, before the first video is played.
 * <p>
 * The page also never reports the playback (watch history, statistics), never loads ads,
 * and never loads images or fonts.
 */
public final class MwebSiteSession {

    private static final String JS_INTERFACE = "MorpheMwebSite";

    private static final String WATCH_URL = MwebSignIn.ORIGIN + "/watch?v=";

    /**
     * The page of an age-restricted video shows a warning ("I understand and wish to proceed"),
     * and its player does not start. With these parameters the page is opened as the website opens it
     * after the warning: 'has_verified' for the age check and 'bpctr' for the content check.
     * Other videos are not changed.
     */
    private static final String WATCH_URL_VERIFIED_PARAMETERS = "&has_verified=1&bpctr=9999999999";

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

    /**
     * The player of the page, and the video of the page address: the 'v' parameter of a watch page,
     * or the path of a Shorts page.
     */
    private static final String PLAYER_FUNCTIONS =
            "function player(){return document.getElementById('movie_player')||document.querySelector('.html5-video-player');}"
            + "function pageVideoId(){try{var u=new URL(location.href);var v=u.searchParams.get('v');if(v)return v;"
            + "var m=u.pathname.match(/^\\/shorts\\/([\\w-]{11})/);return m?m[1]:'';}catch(e){return '';}}";

    /**
     * Loads a video in the player of a page that has already made a session. The player makes the PoToken
     * of the new video at once, while a new page makes it in several seconds, since BotGuard starts again.
     * The held media requests are given up first, so the player does not wait for them.
     * The page address gets the new video, as the requests of the player are checked with it.
     * If the ads are removed from the player response and the video id (twice) are added with String.format.
     */
    private static final String LOAD_IN_PAGE_SCRIPT = "(function(){" + PLAYER_FUNCTIONS + "var p=player();"
            + "if(!window.__morpheRelease||!p||!p.loadVideoById)return 'no player';"
            + "window.__morphePruneAds=%s;window.__morpheRelease();"
            + "try{history.replaceState(history.state,'','/watch?v=%s');}catch(e){}"
            + "p.loadVideoById('%s');return 'ok';})();";

    /**
     * Result of {@link #LOAD_IN_PAGE_SCRIPT} when the video is loaded, as evaluateJavascript gives it.
     */
    private static final String LOAD_IN_PAGE_RESULT_OK = "\"ok\"";

    private static final Pattern VIDEO_ID_PATTERN = Pattern.compile("[\\w-]{11}");

    /**
     * Starts the player of the page, or reports a video that the player of the page cannot play.
     */
    private static final String START_PLAYER_SCRIPT = "(function(){" + PLAYER_FUNCTIONS + "var p=player();"
            + "var r=null;try{r=p&&p.getPlayerResponse&&p.getPlayerResponse();}catch(e){}"
            + "var s=r&&r.playabilityStatus&&r.playabilityStatus.status;"
            + "if(s&&s!=='OK'){" + JS_INTERFACE + ".onUnplayable(String(s),pageVideoId());}"
            + "else if(p&&p.playVideo){p.playVideo();" + JS_INTERFACE + ".onLog('player started');}"
            + "else{" + JS_INTERFACE + ".onLog('no player in the page');}})();";

    /**
     * Loads the video again in the player of the page. The video id is added with String.format.
     */
    private static final String RELOAD_PLAYER_SCRIPT = "(function(){" + PLAYER_FUNCTIONS + "var p=player();"
            + "if(p&&p.loadVideoById){p.loadVideoById('%s');" + JS_INTERFACE + ".onLog('player loaded the video again');}})();";


    /**
     * The website makes a session in about 6 seconds, so a longer wait means it does not make it,
     * for example when the app is in the background and the page is paused.
     * The app waits for the streams of a video for 20 seconds in all,
     * so the next client still has time to fetch them.
     */
    private static final long TIMEOUT_MILLISECONDS = 12 * 1000;

    /**
     * Pages opened for the session of a video. A page that fails, such as when its renderer is gone,
     * or it cannot be loaded, is replaced by a new page, in the time of {@link #TIMEOUT_MILLISECONDS}.
     */
    private static final int MAX_PAGE_ATTEMPTS = 3;

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
     * Video opened by the page made while the app starts. A short video that is always available,
     * whose session is not used, and the page never reports the playback.
     */
    private static final String WARM_UP_VIDEO_ID = "jNQXAC9IVRw";

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
     * Then they are held, so the player waits and makes no more requests, until the next video is loaded
     * in the page, which gives them up.
     * <p>
     * When the page shows an ad, the ads are removed from the next player responses of the page: the media
     * requests of the page fail, so the ad never ends and the player never asks for the video. The ads are
     * not removed otherwise, as the server then makes the streams of most videos wait a few seconds.
     * <p>
     * It runs with evaluateJavascript, which the content security policy of the page does not limit.
     * The player loads after the page has started, so it uses the wrapped functions.
     */
    private static final String HOOK_SCRIPT = "(function(){"
            + "if(window.__morpheHook)return;window.__morpheHook=1;window.__morpheHold=false;window.__morpheHeld=[];"
            + "window.__morpheRelease=function(){window.__morpheHold=false;var h=window.__morpheHeld;window.__morpheHeld=[];"
            + "for(var i=0;i<h.length;i++){try{h[i]();}catch(e){}}};"
            + "function b64(u8){var s='';for(var i=0;i<u8.length;i+=32768){s+=String.fromCharCode.apply(null,u8.subarray(i,i+32768));}return btoa(s);}"
            + "function isMedia(url){return typeof url==='string'&&url.indexOf('googlevideo.com/videoplayback')>=0;}"
            + "function isPlayerRequest(url){return typeof url==='string'&&/\\/youtubei\\/v1\\/player(\\?|$)/.test(url);}"
            + "function withoutAds(r){return r.clone().json().then(function(j){"
            + "if(!j||!(j.adPlacements||j.playerAds||j.adSlots))return r;"
            + "delete j.adPlacements;delete j.playerAds;delete j.adSlots;"
            + "var h=new Headers(r.headers);h.delete('content-length');"
            + "return new Response(JSON.stringify(j),{status:r.status,statusText:r.statusText,headers:h});"
            + "}).catch(function(){return r;});}"
            + PLAYER_FUNCTIONS
            + "function adShowing(){var p=player();return !!(p&&p.classList&&(p.classList.contains('ad-showing')||p.classList.contains('ad-interrupting')));}"
            + "function playerVideoId(){try{var p=player();var d=p&&p.getVideoData&&p.getVideoData();return (d&&d.video_id)||'';}catch(e){return '';}}"
            + "function send(url,body){try{var u8=body instanceof Uint8Array?body:new Uint8Array(body instanceof ArrayBuffer?body:body.buffer);"
            + JS_INTERFACE + ".onSabrRequest(url,b64(u8),pageVideoId(),playerVideoId(),adShowing());}catch(e){"
            + JS_INTERFACE + ".onLog('send failed: '+e);}}"
            + "function refuse(){return window.__morpheHold"
            + "?new Promise(function(res,rej){window.__morpheHeld.push(function(){rej(new TypeError('blocked'));});})"
            + ":Promise.reject(new TypeError('blocked'));}"
            + "var of=window.fetch;window.fetch=function(input,init){"
            + "var url=input instanceof Request?input.url:String(input);"
            + "if(isMedia(url)){"
            + "if(!window.__morpheHold){"
            + "if(init&&init.body){send(url,init.body);}"
            + "else if(input instanceof Request){input.clone().arrayBuffer().then(function(a){if(a.byteLength)send(url,a);});}}"
            + "return refuse();}"
            + "if(window.__morphePruneAds&&isPlayerRequest(url)){return of.apply(this,arguments).then(withoutAds);}"
            + "return of.apply(this,arguments);};"
            + "var oo=XMLHttpRequest.prototype.open,os=XMLHttpRequest.prototype.send;"
            + "XMLHttpRequest.prototype.open=function(m,url){this.__u=String(url);return oo.apply(this,arguments);};"
            + "XMLHttpRequest.prototype.send=function(body){if(isMedia(this.__u)){"
            + "if(window.__morpheHold){var x=this;window.__morpheHeld.push(function(){x.abort();});}"
            + "else{if(body)send(this.__u,body);this.abort();}return;}"
            + "return os.apply(this,arguments);};"
            + JS_INTERFACE + ".onLog('hook installed');"
            + "})();";

    /**
     * The streaming session of the website for a video.
     */
    public static final class Session {
        public final String serverAbrStreamingUrl;
        /**
         * The 'id' parameter of the stream url.
         */
        @Nullable
        final String streamId;
        @Nullable
        final byte[] clientInfo;
        @Nullable
        final byte[] poToken;
        @Nullable
        final byte[] ustreamerConfig;

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
            this.streamId = uri.getQueryParameter("id");
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

    /**
     * The page failed, and another page can make the session.
     */
    private static final class PageFailedException extends Exception {
        PageFailedException(String message) {
            super(message);
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
    private static final AtomicInteger generation = new AtomicInteger();

    /**
     * Page opened while the app starts, until a video is loaded in it, or it is free. Main thread only.
     */
    private static final AtomicReference<Page> warmUpPage = new AtomicReference<>();

    private MwebSiteSession() {
    }

    /**
     * Forgets the sessions and closes the pages, for example after a sign in or a sign-out.
     */
    static void reset() {
        generation.incrementAndGet();
        MwebStream.clear();
        startedFetches.clear();
        Utils.runOnMainThread(() -> {
            closeFreePages();
            Page page = warmUpPage.getAndSet(null);
            if (page != null) {
                page.close();
            }
        });
    }

    /**
     * Opens a page while the app starts, so BotGuard of the website has started when the first video
     * is played, and the player of the page makes the PoToken of the video at once.
     */
    public static void warmUp() {
        Utils.runOnMainThread(() -> {
            if (warmUpPage.get() != null) {
                return;
            }
            synchronized (freePages) {
                if (!freePages.isEmpty()) {
                    return;
                }
            }
            try {
                Logger.printDebug(() -> "Website session: making a page while the app starts");
                Page page = new Page(generation.get());
                page.attachToCurrentActivity();
                CompletableFuture<Session> future = page.load(WARM_UP_VIDEO_ID);
                page.warmUp = true;
                warmUpPage.set(page);
                future.whenComplete((session, ex) -> Utils.runOnMainThread(() -> {
                    if (!warmUpPage.compareAndSet(page, null)) {
                        // A video was loaded in the page, or the page was closed.
                        return;
                    }
                    page.warmUp = false;
                    page.finishLoad();
                    if (ex != null) {
                        Logger.printInfo(() -> "Website session: the page made while the app starts"
                                + " gave no session: " + ex);
                    }
                    addToFreePagesOrClose(page, ex == null);
                }));
                mainHandler.postDelayed(() -> future.completeExceptionally(
                        new TimeoutException("No session of the page made while the app starts")),
                        TIMEOUT_MILLISECONDS);
            } catch (Exception ex) {
                Logger.printInfo(() -> "Website session: could not make a page while the app starts", ex);
            }
        });
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
        for (int attempt = 1; ; attempt++) {
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
                PageLoad pageLoad = pageLoadFuture.get(Math.max(TIMEOUT_MILLISECONDS
                        - (System.currentTimeMillis() - start), 0), TimeUnit.MILLISECONDS);
                page = pageLoad.page;
                final long remaining = TIMEOUT_MILLISECONDS - (System.currentTimeMillis() - start);
                Session session = pageLoad.session.get(Math.max(remaining, 0), TimeUnit.MILLISECONDS);
                pageWorks = true;
                final int blocked = page.blockedRequests.getAndSet(0);
                final boolean inPage = page.loadedInPage;
                Logger.printDebug(() -> "Website session of " + videoId + " in "
                        + (System.currentTimeMillis() - start) + " ms" + (inPage ? " (loaded in the page)" : "")
                        + ", PoToken bytes: "
                        + (session.poToken == null ? 0 : session.poToken.length)
                        + ", ustreamer config: " + (session.ustreamerConfig != null)
                        + ", client: " + (session.clientInfo != null)
                        + ", blocked page requests: " + blocked);
                return session;
            } catch (Exception ex) {
                Logger.printInfo(() -> "The website gave no session for " + videoId + " in "
                        + (System.currentTimeMillis() - start) + " ms", ex);
                // A page that showed an ad is not stuck, and its player can load the next video.
                pageWorks = page != null && page.adShown;
                if (attempt >= MAX_PAGE_ATTEMPTS || !(ex.getCause() instanceof PageFailedException)
                        || System.currentTimeMillis() - start >= TIMEOUT_MILLISECONDS) {
                    return null;
                }
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
            Logger.printDebug(() -> "Website session: opening another page for " + videoId);
        }
    }

    /**
     * Main thread only.
     */
    private static Page takePage() {
        mainHandler.removeCallbacks(closeIdlePages);
        Page page;
        synchronized (freePages) {
            // The renderer is shared by all pages, so pages that are not used can be gone as well.
            while ((page = freePages.poll()) != null && page.rendererGone) {
                page.close();
            }
        }
        if (page == null) {
            Page warmUp = warmUpPage.get();
            if (warmUp != null && !warmUp.rendererGone && warmUpPage.compareAndSet(warmUp, null)) {
                // The page made while the app starts, which loads the video in its player
                // even if BotGuard has not made its first PoToken yet.
                page = warmUp;
            }
        }
        if (page == null) {
            Logger.printDebug(() -> "Website session: making a new page");
            page = new Page(generation.get());
        }
        page.attachToCurrentActivity();
        return page;
    }

    /**
     * Main thread only.
     */
    private static void releasePage(Page page, boolean keep) {
        page.finishLoad();
        addToFreePagesOrClose(page, keep);
    }

    /**
     * Main thread only.
     */
    private static void addToFreePagesOrClose(Page page, boolean keep) {
        if (keep && page.generation == generation.get() && !page.rendererGone) {
            page.madeSession = true;
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
         * If the page has made a session, so its player can load the next video. Main thread only.
         */
        boolean madeSession;

        /**
         * If the pending video is loaded in the player of the page, instead of in a new page.
         */
        volatile boolean loadedInPage;

        /**
         * If the player of the page has shown an ad for the pending video.
         */
        volatile boolean adShown;

        /**
         * If the page was made while the app starts and opens its video. Main thread only.
         */
        boolean warmUp;

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
                        future.completeExceptionally(new PageFailedException("The page renderer is gone"));
                    }
                    return true;
                }

                @Override
                public void onReceivedError(WebView v, WebResourceRequest request, WebResourceError error) {
                    onMainFrameError(request, "error " + error.getErrorCode());
                }

                @Override
                public void onReceivedHttpError(WebView v, WebResourceRequest request,
                                                WebResourceResponse response) {
                    onMainFrameError(request, "HTTP status " + response.getStatusCode());
                }

                /**
                 * The watch page of the pending video cannot be loaded, for example without a network,
                 * so the session fails now, instead of after the timeout.
                 */
                private void onMainFrameError(WebResourceRequest request, String error) {
                    CompletableFuture<Session> future = pending;
                    String videoId = pendingVideoId;
                    // A page that is opened again stops the load of the page before it.
                    if (future == null || videoId == null || !request.isForMainFrame()
                            || !videoId.equals(request.getUrl().getQueryParameter("v"))) {
                        return;
                    }
                    Logger.printInfo(() -> "Website session: the watch page cannot be loaded, " + error);
                    future.completeExceptionally(new PageFailedException("The watch page cannot be loaded"));
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
         * Loads a video in the player of the page if the page has made a session,
         * or opens the watch page of the video. Main thread only.
         */
        CompletableFuture<Session> load(String videoId) {
            adShown = false;
            CompletableFuture<Session> future = new CompletableFuture<>();
            pendingVideoId = videoId;
            pending = future;
            blockedRequests.set(0);
            sabrRequestSeen = false;
            loggedErrors.set(0);
            openTime = SystemClock.elapsedRealtime();
            mainHandler.removeCallbacks(watchdog);
            mainHandler.postDelayed(watchdog, WATCHDOG_INTERVAL_MILLISECONDS);

            // The page made while the app starts has the player of its video, or is loading it.
            final boolean hasPlayer = madeSession || warmUp;
            warmUp = false;
            if (!hasPlayer || !VIDEO_ID_PATTERN.matcher(videoId).matches()) {
                openWatchPage(videoId);
                return future;
            }

            loadInPage(videoId, false);
            return future;
        }

        /**
         * Loads the pending video in the player of the page, if it is still pending. Main thread only.
         * If the video is loaded again because the page showed an ad, the ads are removed from its player response,
         * and the watch page is not opened if the player cannot load it, as the watch page would show the ad again.
         *
         * @param adShown If the page showed an ad for the video.
         */
        void loadInPage(String videoId, boolean adShown) {
            CompletableFuture<Session> future = pending;
            if (future == null || !videoId.equals(pendingVideoId) || rendererGone) {
                return;
            }
            loadedInPage = true;
            sabrRequestSeen = false;
            openTime = SystemClock.elapsedRealtime();
            webView.evaluateJavascript(String.format(LOAD_IN_PAGE_SCRIPT, adShown, videoId, videoId), result -> {
                if (!LOAD_IN_PAGE_RESULT_OK.equals(result) && pending == future) {
                    Logger.printDebug(() -> "Website session: the video cannot be loaded in the page: " + result);
                    if (!adShown) {
                        openWatchPage(videoId);
                    }
                }
            });
        }

        /**
         * Opens the watch page of the pending video, if it is still pending. Main thread only.
         */
        void openWatchPage(String videoId) {
            if (pending == null || !videoId.equals(pendingVideoId) || rendererGone) {
                return;
            }
            loadedInPage = false;
            sabrRequestSeen = false;
            openTime = SystemClock.elapsedRealtime();
            // All cookies of YouTube are always rejected in the cookie consent.
            CookieManager.getInstance().setCookie(MwebSignIn.ORIGIN, CONSENT_REJECTED_COOKIE);
            webView.loadUrl(WATCH_URL + Uri.encode(videoId) + WATCH_URL_VERIFIED_PARAMETERS);
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

    @SuppressWarnings("unused")
    private record Bridge(Page page) {

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
                        if (!page.adShown) {
                            page.adShown = true;
                            // The media requests of the page fail, so the ad never ends. The player of the page loads
                            // the video again, and the ads are removed from its player response.
                            // The watch page of the video has its player response in the page, with the ads.
                            Logger.printDebug(() -> "Website session: the page shows an ad, loading the video again");
                            mainHandler.post(() -> page.loadInPage(videoId, true));
                        }
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
                        // No PoToken, or the placeholder that a new page sends until BotGuard makes the PoToken.
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

            /**
             * The player of the page cannot play the video, for example an age-restricted video
             * of an account whose age is not verified, so the website gives no session for it.
             * The session fails now, instead of after the timeout, and the next client is used.
             *
             * @param status      Playability status of the player of the page.
             * @param pageVideoId Video of the page address, or empty.
             */
            @JavascriptInterface
            public void onUnplayable(String status, String pageVideoId) {
                CompletableFuture<Session> future = page.pending;
                if (future == null || future.isDone() || !pageVideoId.equals(page.pendingVideoId)) {
                    return;
                }
                if (page.loadedInPage) {
                    // The watch page opens the video with the parameters of the age and content checks,
                    // which the player of the page does not have.
                    Logger.printDebug(() -> "Website session: the player of the page cannot play the video, status: "
                            + status + ", opening its watch page");
                    page.loadedInPage = false;
                    mainHandler.post(() -> page.openWatchPage(pageVideoId));
                    return;
                }
                Logger.printDebug(() -> "Website session: the page cannot play the video, status: " + status);
                future.completeExceptionally(new IllegalStateException("The page cannot play the video: " + status));
            }

            @JavascriptInterface
            public void onLog(String message) {
                Logger.printDebug(() -> "Website session: " + message);
            }
        }
}
