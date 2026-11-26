/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4764
 * https://github.com/MorpheApp/morphe-patches/pull/5208
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
import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;
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
 * The streaming session of a video made by the official website of a web client:
 * the mobile website for Mobile Web, and the desktop website for Web.
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
 * <p>
 * The desktop website also rotates the cookies of the web sign in, see {@link #rotateCookiesIfDue()}.
 */
public final class WebsiteSession {

    private static final String JS_INTERFACE = "MorpheWebsite";

    private static final String WATCH_PATH = "/watch?v=";

    /**
     * The page of an age-restricted video shows a warning ("I understand and wish to proceed"),
     * and its player does not start. With these parameters the page is opened as the website opens it
     * after the warning: 'has_verified' for the age check and 'bpctr' for the content check.
     * Other videos are not changed.
     */
    private static final String WATCH_URL_VERIFIED_PARAMETERS = "&has_verified=1&bpctr=9999999999";

    /**
     * The desktop website opens its page again with this parameter when the dark theme of the browser
     * is not the theme of the page, which loses the video loading in the page. With it the page is never opened again.
     */
    private static final String DESKTOP_THEME_REFRESH_PARAMETER = "&themeRefresh=1";

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
     * Sets the page address to a video, and keeps {@link #DESKTOP_THEME_REFRESH_PARAMETER} in a page
     * of the desktop website. The video id is added with String.format.
     */
    private static final String SET_PAGE_ADDRESS_SCRIPT = "try{history.replaceState(history.state,'','/watch?v=%s'"
            + "+(window.__morpheDesktop?'" + DESKTOP_THEME_REFRESH_PARAMETER + "':''));}catch(e){}";

    /**
     * Loads a video in the player of a page that has already made a session. The player makes the PoToken
     * of the new video at once, while a new page makes it in several seconds, since BotGuard starts again.
     * The held media requests are given up first, so the player does not wait for them.
     * The page address gets the new video, as the requests of the player are checked with it,
     * and keeps {@link #DESKTOP_THEME_REFRESH_PARAMETER} in a page of the desktop website.
     * If the ads are removed from the player response and the video id (twice) are added with String.format.
     */
    private static final String LOAD_IN_PAGE_SCRIPT = "(function(){" + PLAYER_FUNCTIONS + "var p=player();"
            + "if(!window.__morpheRelease||!p||!p.loadVideoById)return 'no player';"
            + "window.__morphePruneAds=%s;window.__morphePlay=false;window.__morpheRelease();"
            + SET_PAGE_ADDRESS_SCRIPT
            + "p.loadVideoById('%s');return 'ok';})();";

    /**
     * Result of {@link #LOAD_IN_PAGE_SCRIPT} when the video is loaded, as evaluateJavascript gives it.
     */
    private static final String LOAD_IN_PAGE_RESULT_OK = "\"ok\"";

    private static final Pattern VIDEO_ID_PATTERN = Pattern.compile("[\\w-]{11}");

    /**
     * Starts the player of the page, or reports a video that the player of the page cannot play.
     * The player of a page of the desktop website can fetch the media before the hook of the page is installed,
     * and a short video then plays with no more requests, so it is loaded again in the player.
     */
    private static final String START_PLAYER_SCRIPT = "(function(){" + PLAYER_FUNCTIONS + "var p=player();"
            + "var r=null;try{r=p&&p.getPlayerResponse&&p.getPlayerResponse();}catch(e){}"
            + "var s=r&&r.playabilityStatus&&r.playabilityStatus.status;"
            + "if(s&&s!=='OK'){" + JS_INTERFACE + ".onUnplayable(String(s),pageVideoId());}"
            + "else if(window.__morpheDesktop&&p&&p.getPlayerState&&p.getPlayerState()===1&&p.loadVideoById){"
            + "p.loadVideoById(pageVideoId());" + JS_INTERFACE + ".onLog('player plays without requests, loading the video again');}"
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
     * Frame of the accounts site that rotates the cookies of the web sign in. The desktop website adds it,
     * hidden, to each of its pages while it is signed in, unless the browser is of a phone or a tablet,
     * so the mobile website never rotates them. 'yt_pid' is the id of the client of the website.
     */
    private static final String ROTATE_COOKIES_PAGE = "<html><body><iframe style=\"display:none\""
            + " src=\"https://accounts.youtube.com/RotateCookiesPage?origin=" + ClientType.WEB.getWebsiteOrigin()
            + "&yt_pid=" + ClientType.WEB.id + "\"></iframe></body></html>";

    /**
     * Cookies of the web sign in that the frame of {@link #ROTATE_COOKIES_PAGE} renews.
     */
    private static final String[] ROTATED_COOKIES = {"__Secure-1PSIDTS", "__Secure-3PSIDTS"};

    /**
     * The desktop website rotates the cookies with each page it opens, so they are rotated at most this often.
     */
    private static final long ROTATE_COOKIES_INTERVAL_MILLISECONDS = 10 * 60 * 1000;

    /**
     * How long the frame is given to rotate the cookies.
     */
    private static final long ROTATE_COOKIES_TIMEOUT_MILLISECONDS = 15 * 1000;

    /**
     * How often the cookies are checked while they are rotated.
     */
    private static final long ROTATE_COOKIES_CHECK_INTERVAL_MILLISECONDS = 500;

    /**
     * Video of the player response of the page while the player has no response,
     * such as right after a video is loaded in the player of the page.
     */
    private static final String NO_PLAYER_RESPONSE = "none";

    /**
     * Parameters of a player request of the desktop website whose response has no ads,
     * with the same formats as the response of a request without parameters.
     * Signed in, the server otherwise keeps the ads of the video in the streaming session, and its first
     * media response tells the player to wait for most of their length (2 to 20 seconds) before asking again.
     */
    private static final String DESKTOP_NO_ADS_PLAYER_PARAMETERS = "eAFgAQ";

    /**
     * Wraps fetch and XMLHttpRequest of the page, and sends its SABR requests to Java.
     * The media requests of the page fail, so the player makes them again, until the session is taken.
     * Then they are held, so the player waits and makes no more requests, until the next video is loaded
     * in the page, which gives them up.
     * <p>
     * The player requests of the page ask for no ads (playbackContext.adPlaybackContext.pyv, as for the playback
     * of a video ad). Otherwise, the server puts the ads of the video in the streaming session, and as they are
     * never played, the streams wait for most of their length (4 to 24 seconds) before the server sends any media.
     * The page sends its requests as JSON compressed with gzip, so the request is changed when it is turned
     * into JSON. Premium accounts of the web sign in have no ads, and these requests would not get their Premium
     * formats, so the requests are not changed when the page names a Premium membership, as the website reads it.
     * The desktop website answers such a request with an unplayable video, so the player requests of its pages
     * have {@link #DESKTOP_NO_ADS_PLAYER_PARAMETERS} instead, unless they have their own parameters.
     * They also confirm the age and content checks, as {@link #WATCH_URL_VERIFIED_PARAMETERS} do for the watch
     * page, so an age-restricted video loaded in the player of the page plays without opening its watch page.
     * <p>
     * When the page shows an ad anyway, which the watch page has in its player response, the ads are removed
     * from the next player responses of the page: the media requests of the page fail, so the ad never ends
     * and the player never asks for the video.
     * <p>
     * It runs with evaluateJavascript, which the content security policy of the page does not limit.
     * The player loads after the page has started, so it uses the wrapped functions.
     */
    private static final String HOOK_SCRIPT = "(function(){"
            + "if(window.__morpheHook)return;window.__morpheHook=1;window.__morpheHold=false;window.__morpheHeld=[];"
            + "window.__morpheRelease=function(){window.__morpheHold=false;var h=window.__morpheHeld;window.__morpheHeld=[];"
            + "for(var i=0;i<h.length;i++){try{h[i]();}catch(e){}}};"
            + "function b64(u8){var s='';for(var i=0;i<u8.length;i+=32768){s+=String.fromCharCode.apply(null,u8.subarray(i,i+32768));}return btoa(s);}"
            + "function premiumMembership(){try{var o=[window.ytInitialPlayerResponse,window.ytInitialData];"
            + "for(var i=0;i<o.length;i++){var t=o[i]&&o[i].responseContext&&o[i].responseContext.serviceTrackingParams;"
            + "if(!t)continue;for(var j=0;j<t.length;j++){var q=t[j].params||[];"
            + "for(var k=0;k<q.length;k++){if(q[k].key==='premium_membership')return String(q[k].value);}}}}catch(e){}return '';}"
            + "function isPremium(){var m=premiumMembership();return m!==''&&!/^non/i.test(m);}"
            + "var oj=JSON.stringify;JSON.stringify=function(v){var a=Array.prototype.slice.call(arguments);"
            + "try{var pc=v&&v.context&&v.videoId&&v.playbackContext;"
            + "if(pc&&pc.contentPlaybackContext&&!pc.adPlaybackContext&&!window.__morpheDesktop&&!isPremium()){var c=Object.assign({},v);"
            + "c.playbackContext=Object.assign({},pc,{adPlaybackContext:{pyv:true}});a[0]=c;}"
            + "if(pc&&pc.contentPlaybackContext&&window.__morpheDesktop){"
            + "var d=Object.assign({},v,{contentCheckOk:true,racyCheckOk:true});"
            + "if(!v.params&&!isPremium())d.params='" + DESKTOP_NO_ADS_PLAYER_PARAMETERS + "';a[0]=d;}}catch(e){}"
            + "return oj.apply(this,a);};"
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
            + "function playerResponse(){var p=player();if(!p||!p.getPlayerResponse)return undefined;"
            + "try{return p.getPlayerResponse()||null;}catch(e){return null;}}"
            + "function responseVideoId(r){return r===undefined?'':((r&&r.videoDetails&&r.videoDetails.videoId)||'"
            + NO_PLAYER_RESPONSE + "');}"
            + "function responseStreamId(r){try{var u=r&&r.streamingData&&r.streamingData.serverAbrStreamingUrl;"
            + "return u?(new URL(u).searchParams.get('id')||''):'';}catch(e){return '';}}"
            + "function visitorData(){try{return ytcfg.get('INNERTUBE_CONTEXT').client.visitorData||'';}catch(e){return '';}}"
            + "function send(url,body){try{var u8=body instanceof Uint8Array?body:new Uint8Array(body instanceof ArrayBuffer?body:body.buffer);"
            + "var r=playerResponse();"
            + JS_INTERFACE + ".onSabrRequest(url,b64(u8),pageVideoId(),playerVideoId(),responseVideoId(r),responseStreamId(r),adShowing(),visitorData());}catch(e){"
            + JS_INTERFACE + ".onLog('send failed: '+e);}}"
            // The media response of the page is given to Java while it streams in, until it has the stream status.
            + "function watch(f){return f.then(function(r){try{if(r.body&&" + JS_INTERFACE + ".watchesResponse()){"
            + "var rd=r.clone().body.getReader(),parts=[],n=0;var step=function(){rd.read().then(function(x){"
            + "if(x.done||!" + JS_INTERFACE + ".watchesResponse()){rd.cancel().catch(function(){});return;}"
            + "parts.push(x.value);n+=x.value.length;var all=new Uint8Array(n),o=0;"
            + "for(var i=0;i<parts.length;i++){all.set(parts[i],o);o+=parts[i].length;}"
            + JS_INTERFACE + ".onPageResponse(b64(all));step();}).catch(function(){});};step();}}"
            + "catch(e){}return r;});}"
            + "function plays(){if(!" + JS_INTERFACE + ".playsUntilAttested())return false;"
            + "window.__morphePlay=true;try{var p=player();if(p&&p.mute)p.mute();}catch(e){}return true;}"
            + "function refuse(){return window.__morpheHold"
            + "?new Promise(function(res,rej){window.__morpheHeld.push(function(){rej(new TypeError('blocked'));});})"
            + ":Promise.reject(new TypeError('blocked'));}"
            + "var of=window.fetch;window.fetch=function(input,init){"
            + "var url=input instanceof Request?input.url:String(input);"
            + "if(isMedia(url)){"
            + "if(window.__morphePlay&&!window.__morpheHold){return watch(of.apply(this,arguments));}"
            + "if(!window.__morpheHold){"
            + "if(init&&init.body){send(url,init.body);if(plays()){return watch(of.apply(this,arguments));}}"
            // The player of the desktop website sends a request object, whose body can only be read later.
            + "else if(input instanceof Request&&window.__morpheDesktop){var self=this;return input.clone().arrayBuffer()"
            + ".then(function(a){if(a.byteLength){send(url,a);}return plays()?watch(of.call(self,input)):refuse();});}"
            + "else if(input instanceof Request){input.clone().arrayBuffer().then(function(a){if(a.byteLength)send(url,a);});}}"
            + "return refuse();}"
            + "if(window.__morphePruneAds&&isPlayerRequest(url)){return of.apply(this,arguments).then(withoutAds);}"
            + "return of.apply(this,arguments);};"
            + "var oo=XMLHttpRequest.prototype.open,os=XMLHttpRequest.prototype.send;"
            + "XMLHttpRequest.prototype.open=function(m,url){this.__u=String(url);return oo.apply(this,arguments);};"
            + "XMLHttpRequest.prototype.send=function(body){if(isMedia(this.__u)){"
            + "if(window.__morphePlay&&!window.__morpheHold){return os.apply(this,arguments);}"
            + "if(window.__morpheHold){var x=this;window.__morpheHeld.push(function(){x.abort();});}"
            + "else{if(body){send(this.__u,body);if(plays()){return os.apply(this,arguments);}}this.abort();}return;}"
            + "return os.apply(this,arguments);};"
            + JS_INTERFACE + ".onLog('hook installed');"
            + "})();";

    /**
     * Runs before {@link #HOOK_SCRIPT} in the pages of the desktop website. Their player requests ask for no ads
     * in another way, and the request that gives the session is made, see {@link #ATTESTATION_REPORT_PATH}.
     */
    private static final String DESKTOP_PAGE_SCRIPT = "window.__morpheDesktop=true;";

    /**
     * Report of the desktop website with the attestation of a playback. The server keeps the streams of a video
     * only if the playback is attested: without it, the stream protection status is pending, then required,
     * and the server stops the stream. The player of the website makes the attestation one second after
     * the playback has started, so a page of the desktop website plays the video, muted,
     * from the request that gives the session until it sends this report.
     */
    private static final String ATTESTATION_REPORT_PATH = "/api/stats/atr";

    /**
     * The server does not attest the playback of some pages of the desktop website, whatever they send,
     * and the website itself then gets a pending stream protection status, and the stream stops.
     * Another page, with another visitor, is usually attested. So the session of a page of the desktop website
     * is given only when the media responses of the page have an OK stream protection status,
     * and otherwise the page fails, and another page is opened. The page also fails if it gives no status
     * in this time.
     */
    private static final long PAGE_STREAM_STATUS_TIMEOUT_MILLISECONDS = 5000;

    /**
     * How long a page of the desktop website plays the video at most, if it does not send the attestation.
     */
    private static final long ATTESTATION_TIMEOUT_MILLISECONDS = 10 * 1000;

    /**
     * Stops the playback of a page after its attestation. The player of the page waits, and makes no more requests.
     */
    private static final String HOLD_AFTER_ATTESTATION_SCRIPT = "(function(){" + PLAYER_FUNCTIONS
            + "window.__morphePlay=false;window.__morpheHold=true;"
            + "var p=player();if(p&&p.pauseVideo)p.pauseVideo();})();";

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
         * The visitor of the page, or null if the page has none.
         */
        @Nullable
        final String visitorData;
        /**
         * The page that made the session. A stream is kept for hours, so the page is not kept by the session.
         */
        private final WeakReference<Page> page;

        /**
         * @param page        The page that made the session.
         * @param requestUrl  Url of a SABR request of the website.
         * @param requestBody Body of a SABR request of the website.
         * @param visitorData The visitor of the page, or empty.
         */
        Session(Page page, String requestUrl, byte[] requestBody, String visitorData) throws IOException {
            this.page = new WeakReference<>(page);
            this.visitorData = visitorData.isEmpty() ? null : visitorData;
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
        Thread thread = new Thread(runnable, "WebsiteSession");
        thread.setDaemon(true);
        return thread;
    });

    /**
     * Fetches started before the player request of their video, by video.
     */
    private static final Map<String, StartedFetch> startedFetches =
            Collections.synchronizedMap(Utils.createSizeRestrictedMap(10));

    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    private static final Runnable closeIdlePages = WebsiteSession::closeFreePages;

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

    /**
     * Page that rotates the cookies of the web sign in, while it rotates them. Main thread only.
     * Held weakly to prevent leaking the activity context while rotation is in progress.
     */
    @Nullable
    private static WeakReference<WebView> cookieRotationViewRef;

    /**
     * {@link SystemClock#elapsedRealtime()} when the cookies were last rotated. Main thread only.
     */
    private static long lastCookieRotationTime = -ROTATE_COOKIES_INTERVAL_MILLISECONDS;

    private WebsiteSession() {
    }

    /**
     * Forgets the sessions and closes the pages, for example after a sign in or a sign-out.
     */
    static void reset() {
        generation.incrementAndGet();
        WebsiteStream.clear();
        startedFetches.clear();
        Utils.runOnMainThread(() -> {
            closeFreePages();
            Page page = warmUpPage.getAndSet(null);
            if (page != null) {
                page.close();
            }
            if (cookieRotationViewRef != null) {
                WebView v = cookieRotationViewRef.get();
                if (v != null) {
                    finishCookieRotation(v, false);
                }
                cookieRotationViewRef = null;
            }
        });
    }

    /**
     * Opens a page while the app starts, so BotGuard of the website has started when the first video
     * is played, and the player of the page makes the PoToken of the video at once.
     *
     * @param client The web client of the page.
     */
    public static void warmUp(ClientType client) {
        if (client == ClientType.WEB) {
            rotateCookiesIfDue();
        }
        warmUp(client, 1);
    }

    /**
     * The server does not attest the playback of some pages of the desktop website
     * ({@link #PAGE_STREAM_STATUS_TIMEOUT_MILLISECONDS}), so another page is made while the app starts,
     * up to {@link #MAX_PAGE_ATTEMPTS} pages.
     *
     * @param attempt The number of the page made while the app starts.
     */
    private static void warmUp(ClientType client, int attempt) {
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
                Page page = new Page(generation.get(), client);
                attachToCurrentActivity(page.webView);
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
                    if (client == ClientType.WEB && ex instanceof PageFailedException
                            && attempt < MAX_PAGE_ATTEMPTS && page.generation == generation.get()) {
                        warmUp(client, attempt + 1);
                    }
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
     *
     * @param client The web client of the session.
     */
    public static void start(String videoId, ClientType client) {
        synchronized (startedFetches) {
            StartedFetch started = startedFetches.get(videoId);
            if (started != null && !isTooOld(started)) {
                return;
            }
            startedFetches.put(videoId, new StartedFetch(executor.submit(() -> load(videoId, client)),
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
     * @param client The web client of the session.
     * @return The session, or null if the website did not give one.
     */
    @Nullable
    public static Session fetch(String videoId, ClientType client) {
        Utils.verifyOffMainThread();
        StartedFetch started;
        synchronized (startedFetches) {
            started = startedFetches.remove(videoId);
            if (started == null || isTooOld(started)) {
                started = new StartedFetch(executor.submit(() -> load(videoId, client)),
                        SystemClock.elapsedRealtime());
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
     * The desktop website chooses the formats of a stream for the visitor of the page that made its session,
     * and each new page has its own visitor, so the player request of the video is made with that visitor.
     * Waits for the session of a video that was started, and keeps it for {@link #fetch(String, ClientType)}.
     * Blocking call, and must be made off the main thread.
     *
     * @return The visitor of the page that made the session of the video, or null if there is none.
     */
    @Nullable
    public static String getVisitorData(String videoId) {
        Utils.verifyOffMainThread();
        StartedFetch started = startedFetches.get(videoId);
        if (started == null) {
            return null;
        }
        try {
            Session session = started.future.get(TIMEOUT_MILLISECONDS, TimeUnit.MILLISECONDS);
            return session == null ? null : session.visitorData;
        } catch (Exception ex) {
            // The fetch of the streams reports it.
            return null;
        }
    }

    /**
     * Opens the video in a page and waits for a SABR request of its player. Runs on the executor.
     */
    @Nullable
    private static Session load(String videoId, ClientType client) {
        final long start = System.currentTimeMillis();
        for (int attempt = 1; ; attempt++) {
            CompletableFuture<PageLoad> pageLoadFuture = new CompletableFuture<>();
            Utils.runOnMainThread(() -> {
                Page page = null;
                try {
                    page = takePage(client);
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
    private static Page takePage(ClientType client) {
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
            page = new Page(generation.get(), client);
        }
        attachToCurrentActivity(page.webView);
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
        if (keep && !page.sessionFailed && page.generation == generation.get() && !page.rendererGone) {
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
     * Called when the stream of a session cannot be played. The page that made the session is not used again,
     * since its sessions can keep failing, for example after the server stopped attesting its playback.
     * Then the next session of the web client is made by a new page, as when the app starts again.
     */
    static void onSessionFailed(Session session) {
        Utils.runOnMainThread(() -> {
            Page page = session.page.get();
            if (page == null || page.sessionFailed) {
                return;
            }
            Logger.printDebug(() -> "Website session: the page of a failed session is not used again");
            page.sessionFailed = true;
            final boolean free;
            synchronized (freePages) {
                free = freePages.remove(page);
            }
            // A page in use is closed when it's released.
            if (free) {
                page.close();
            }
        });
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

    /**
     * A WebView not in a window does not count towards the priority of its renderer,
     * which then can be frozen. Main thread only.
     */
    private static void attachToCurrentActivity(WebView webView) {
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
     * Rotates the cookies of the web sign in as the desktop website does, if they were not rotated recently.
     * Google ends a desktop session whose cookies are not rotated, while the mobile website never rotates them.
     * <p>
     * The frame that the desktop website adds to its pages is added to a page of the desktop website made here,
     * and the frame rotates the cookies in the cookie store of the WebView. It does not wait for the rotation,
     * and the next requests use the rotated cookies.
     */
    @SuppressLint("SetJavaScriptEnabled")
    static void rotateCookiesIfDue() {
        if (!WebsiteSignIn.of(ClientType.WEB).isSignedIn()) {
            return;
        }
        Utils.runOnMainThread(() -> {
            final long start = SystemClock.elapsedRealtime();
            final WebView activeView = cookieRotationViewRef != null ? cookieRotationViewRef.get() : null;
            if (activeView != null || start - lastCookieRotationTime < ROTATE_COOKIES_INTERVAL_MILLISECONDS) {
                return;
            }
            lastCookieRotationTime = start;
            try {
                WebView webView = new WebView(Utils.getContext());
                WebViewProfiles.setProfile(webView, ClientType.WEB);
                WebSettings settings = webView.getSettings();
                settings.setJavaScriptEnabled(true);
                settings.setLoadsImagesAutomatically(false);
                settings.setUserAgentString(ClientType.WEB.getUserAgent());
                webView.setVisibility(View.INVISIBLE);
                webView.setWebViewClient(new WebViewClient() {
                    @Override
                    public boolean onRenderProcessGone(WebView v, RenderProcessGoneDetail detail) {
                        // All WebViews of the app share the renderer, which Android can stop to free memory.
                        finishCookieRotation(v, false);
                        return true;
                    }

                    @Override
                    public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest request) {
                        // Never leave the website, for example to open the app.
                        return !"https".equals(request.getUrl().getScheme());
                    }
                });
                attachToCurrentActivity(webView);
                cookieRotationViewRef = new WeakReference<>(webView);

                String cookiesBefore = getRotatedCookies();
                Logger.printDebug(() -> "Rotating the cookies of the web sign in");
                webView.loadDataWithBaseURL(ClientType.WEB.getWebsiteOrigin() + "/", ROTATE_COOKIES_PAGE,
                        "text/html", "utf-8", null);
                mainHandler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        if (cookieRotationViewRef == null || cookieRotationViewRef.get() != webView) {
                            return;
                        }
                        final boolean rotated = !cookiesBefore.equals(getRotatedCookies());
                        if (rotated || SystemClock.elapsedRealtime() - start >= ROTATE_COOKIES_TIMEOUT_MILLISECONDS) {
                            finishCookieRotation(webView, rotated);
                        } else {
                            mainHandler.postDelayed(this, ROTATE_COOKIES_CHECK_INTERVAL_MILLISECONDS);
                        }
                    }
                }, ROTATE_COOKIES_CHECK_INTERVAL_MILLISECONDS);
            } catch (Exception ex) {
                // WebView is missing or broken.
                Logger.printInfo(() -> "Could not rotate the cookies of the web sign in", ex);
            }
        });
    }

    /**
     * @return The values of the cookies that are rotated, which are never logged.
     */
    private static String getRotatedCookies() {
        String cookies = WebsiteSignIn.of(ClientType.WEB).getCookies();
        StringBuilder values = new StringBuilder();
        for (String name : ROTATED_COOKIES) {
            values.append(WebsiteSignIn.getCookieValue(cookies, name)).append(';');
        }
        return values.toString();
    }

    /**
     * Main thread only.
     */
    private static void finishCookieRotation(WebView webView, boolean rotated) {
        if (cookieRotationViewRef == null || cookieRotationViewRef.get() != webView) {
            return;
        }
        cookieRotationViewRef = null;
        Logger.printDebug(() -> rotated
                ? "Cookies of the web sign in rotated"
                : "Cookies of the web sign in were not rotated");
        if (webView.getParent() instanceof ViewGroup parent) {
            parent.removeView(webView);
        }
        webView.destroy();
        CookieManager cookieManager = WebViewProfiles.getCookieManager(ClientType.WEB);
        if (cookieManager != null) {
            cookieManager.flush();
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
        final ClientType client;
        final WebView webView;

        /**
         * {@link #HOOK_SCRIPT} as the website of the client of the page needs it.
         */
        final String hookScript;

        /**
         * If the page plays the video of the last session until it sends the attestation of the playback,
         * see {@link #ATTESTATION_REPORT_PATH}.
         */
        volatile boolean playsUntilAttested;

        /**
         * Session of a page of the desktop website, until its first media response gives the stream protection status,
         * see {@link #PAGE_STREAM_STATUS_TIMEOUT_MILLISECONDS}.
         */
        @Nullable
        volatile Session unconfirmedSession;

        /**
         * Fails the page if it gave no stream protection status in time. Main thread only.
         */
        final Runnable giveUnconfirmedSession = this::onPageStreamStatusTimeout;

        /**
         * Stops the playback of the page after its attestation, or after {@link #ATTESTATION_TIMEOUT_MILLISECONDS}.
         * Main thread only.
         */
        final Runnable holdAfterAttestation = this::stopPlaybackAfterAttestation;
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
         * If the stream of a session of the page could not be played, so the page is not used again.
         * Main thread only.
         */
        boolean sessionFailed;

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
        Page(int generation, ClientType client) {
            this.generation = generation;
            this.client = client;
            hookScript = client == ClientType.WEB ? DESKTOP_PAGE_SCRIPT + HOOK_SCRIPT : HOOK_SCRIPT;
            webView = new WebView(Utils.getContext());
            WebViewProfiles.setProfile(webView, client);
            WebSettings settings = webView.getSettings();
            settings.setJavaScriptEnabled(true);
            settings.setDomStorageEnabled(true);
            settings.setMediaPlaybackRequiresUserGesture(false);
            settings.setLoadsImagesAutomatically(false);
            settings.setUserAgentString(client.getUserAgent());
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
                    v.evaluateJavascript(hookScript, null);
                }

                @Override
                public void onPageCommitVisible(WebView v, String url) {
                    v.evaluateJavascript(hookScript, null);
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
                    String url = request.getUrl().toString();
                    if (client == ClientType.WEB && url.contains(ATTESTATION_REPORT_PATH)) {
                        if (playsUntilAttested) {
                            Logger.printDebug(() -> "Website session: the playback of the page is attested");
                            mainHandler.post(holdAfterAttestation);
                        }
                        return null;
                    }
                    if (isBlocked(url)) {
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
            playsUntilAttested = false;
            unconfirmedSession = null;
            mainHandler.removeCallbacks(holdAfterAttestation);
            mainHandler.removeCallbacks(giveUnconfirmedSession);
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
            String origin = client.getWebsiteOrigin();
            CookieManager cookieManager = WebViewProfiles.getCookieManager(client);
            if (cookieManager != null) {
                cookieManager.setCookie(origin, CONSENT_REJECTED_COOKIE);
            }
            webView.loadUrl(origin + WATCH_PATH + Uri.encode(videoId) + WATCH_URL_VERIFIED_PARAMETERS
                    + (client == ClientType.WEB ? DESKTOP_THEME_REFRESH_PARAMETER : ""));
        }

        /**
         * Main thread only.
         */
        void finishLoad() {
            mainHandler.removeCallbacks(watchdog);
            mainHandler.removeCallbacks(giveUnconfirmedSession);
            unconfirmedSession = null;
            pending = null;
            pendingVideoId = null;
            if (rendererGone) {
                return;
            }
            if (playsUntilAttested) {
                mainHandler.postDelayed(holdAfterAttestation, ATTESTATION_TIMEOUT_MILLISECONDS);
                return;
            }
            // The player of the page waits, and makes no more requests.
            webView.evaluateJavascript("window.__morpheHold=true;", null);
        }

        /**
         * Main thread only.
         */
        private void onPageStreamStatusTimeout() {
            Session session = unconfirmedSession;
            CompletableFuture<Session> future = pending;
            unconfirmedSession = null;
            if (session != null && future != null) {
                Logger.printDebug(() -> "Website session: the page gave no stream protection status");
                playsUntilAttested = false;
                future.completeExceptionally(new PageFailedException("The page gave no stream protection status"));
            }
        }

        /**
         * Main thread only.
         */
        private void stopPlaybackAfterAttestation() {
            mainHandler.removeCallbacks(holdAfterAttestation);
            if (!playsUntilAttested) {
                return;
            }
            playsUntilAttested = false;
            if (!rendererGone && pending == null) {
                webView.evaluateJavascript(HOLD_AFTER_ATTESTATION_SCRIPT, null);
            }
        }

        /**
         * Main thread only.
         */
        void close() {
            Logger.printDebug(() -> "Website session: closing a page");
            mainHandler.removeCallbacks(watchdog);
            mainHandler.removeCallbacks(holdAfterAttestation);
            mainHandler.removeCallbacks(giveUnconfirmedSession);
            if (webView.getParent() instanceof ViewGroup parent) {
                parent.removeView(webView);
            }
            webView.destroy();
        }
    }

    @SuppressWarnings("unused")
    private record Bridge(Page page) {

        /**
             * @param pageVideoId      Video of the page address, or empty.
             * @param playerVideoId    Video of the player of the page, or empty.
             * @param responseVideoId  Video of the player response of the page, {@link #NO_PLAYER_RESPONSE}
             *                         if the player has no response yet, or empty if the player cannot give it.
             * @param responseStreamId The 'id' parameter of the stream url of the player response of the page,
             *                         or empty.
             * @param adShowing        If the player of the page shows an ad.
             * @param visitorData      The visitor of the page, or empty.
             */
            @JavascriptInterface
            public void onSabrRequest(String url, String bodyBase64, String pageVideoId, String playerVideoId,
                                      String responseVideoId, String responseStreamId, boolean adShowing,
                                      String visitorData) {
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
                    // The page made while the app starts can still be starting when the video is loaded in its player,
                    // and the website then sets the page address back to the video the page opened.
                    // The address is set to the video again, as the player is started again with it.
                    final boolean addressChanged = !pageVideoId.isEmpty() && !pageVideoId.equals(videoId)
                            && page.loadedInPage && playerVideoId.equals(videoId) && responseVideoId.equals(videoId);
                    if (addressChanged) {
                        Logger.printDebug(() -> "Website session: the website changed the page address to: "
                                + pageVideoId + ", setting it again to: " + videoId);
                        mainHandler.post(() -> page.webView.evaluateJavascript(
                                String.format(SET_PAGE_ADDRESS_SCRIPT, videoId), null));
                    }
                    if ((!pageVideoId.isEmpty() && !pageVideoId.equals(videoId) && !addressChanged)
                            || (!playerVideoId.isEmpty() && !playerVideoId.equals(videoId))
                            || (!responseVideoId.isEmpty() && !responseVideoId.equals(videoId))) {
                        Logger.printDebug(() -> "Website session: ignoring a request of another video, page: "
                                + pageVideoId + " player: " + playerVideoId + " response: " + responseVideoId
                                + " wanted: " + videoId);
                        return;
                    }
                    // A request of the video loaded before in the page, which the player makes again when its held
                    // requests are given up, has the page address and the player of the new video already,
                    // but the stream of the earlier video. The server then sends the media of the earlier video.
                    String requestStreamId = Uri.parse(url).getQueryParameter("id");
                    if (!responseStreamId.isEmpty() && !responseStreamId.equals(requestStreamId)) {
                        Logger.printDebug(() -> "Website session: ignoring a request of an earlier stream, request: "
                                + requestStreamId + " response: " + responseStreamId + " video: " + videoId);
                        return;
                    }
                    Session session = new Session(page, url, Base64.decode(bodyBase64, Base64.DEFAULT), visitorData);
                    final int poTokenLength = session.poToken == null ? 0 : session.poToken.length;
                    if (poTokenLength < MIN_PO_TOKEN_LENGTH) {
                        // No PoToken, or the placeholder that a new page sends until BotGuard makes the PoToken.
                        // The player sends the PoToken with the next requests,
                        // which it makes again since the media requests fail.
                        Logger.printDebug(() -> "Website session: waiting for the PoToken, request PoToken bytes: "
                                + poTokenLength);
                        return;
                    }
                    if (page.client == ClientType.WEB) {
                        // The request is made, and the session is given with the first media response of the page.
                        page.unconfirmedSession = session;
                        page.playsUntilAttested = true;
                        mainHandler.postDelayed(page.giveUnconfirmedSession, PAGE_STREAM_STATUS_TIMEOUT_MILLISECONDS);
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

            /**
             * @return If the page plays the video of its session until the playback is attested,
             *         so its media requests are made.
             */
            @JavascriptInterface
            public boolean playsUntilAttested() {
                return page.playsUntilAttested;
            }

            /**
             * @return If the media responses of the page are given to {@link #onPageResponse(String)}.
             */
            @JavascriptInterface
            public boolean watchesResponse() {
                return page.unconfirmedSession != null;
            }

            /**
             * A media response of the page of the desktop website, which gives the session of the page
             * if the server attests its playback, see {@link #PAGE_STREAM_STATUS_TIMEOUT_MILLISECONDS}.
             */
            @JavascriptInterface
            public void onPageResponse(String bodyBase64) {
                Session session = page.unconfirmedSession;
                CompletableFuture<Session> future = page.pending;
                if (session == null || future == null || future.isDone()) {
                    return;
                }
                final int status = new SabrResponseReader().read(
                        ByteBuffer.wrap(Base64.decode(bodyBase64, Base64.DEFAULT)));
                if (status < 0) {
                    // The status is in a later response.
                    return;
                }
                page.unconfirmedSession = null;
                mainHandler.removeCallbacks(page.giveUnconfirmedSession);
                if (status == WebsiteStream.STATUS_OK) {
                    future.complete(session);
                    return;
                }
                Logger.printDebug(() -> "Website session: the server does not attest the playback of the page,"
                        + " stream protection status: " + status + ", PoToken bytes: "
                        + (session.poToken == null ? 0 : session.poToken.length));
                page.playsUntilAttested = false;
                future.completeExceptionally(new PageFailedException("The playback of the page is not attested"));
            }

            @JavascriptInterface
            public void onLog(String message) {
                Logger.printDebug(() -> "Website session: " + message);
            }
        }
}
