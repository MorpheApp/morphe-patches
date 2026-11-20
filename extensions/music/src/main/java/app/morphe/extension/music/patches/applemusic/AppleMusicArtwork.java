package app.morphe.extension.music.patches.applemusic;

import android.app.Activity;
import android.content.Context;

import app.morphe.extension.music.settings.Settings;
import android.graphics.Matrix;
import android.graphics.Outline;
import android.graphics.SurfaceTexture;
import android.media.MediaMetadata;
import android.media.MediaPlayer;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;

import java.io.File;
import java.lang.ref.WeakReference;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Overlays Apple Music animated artwork on the YouTube Music player thumbnail.
 * Entry points are called from patched app code. Everything is defensive: any failure
 * just leaves the original thumbnail untouched.
 */
public final class AppleMusicArtwork {
    private static final String TAG = "AppleMusicArtwork";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService IO = Executors.newFixedThreadPool(3);

    private static WeakReference<Activity> activityRef = new WeakReference<>(null);
    private static boolean polling;
    private static Context appCtx;
    private static String[] pending; // {key,title,artist,album} seen before an activity existed
    private static String lastState = "";
    private static boolean lastEnabled = true;
    private static int sameRetry;
    private static volatile String currentKey = "";
    private static volatile File currentFile;     // null = no artwork for current track
    private static volatile boolean playing = true;

    private static TextureView texture;
    private static MediaPlayer player;
    private static boolean surfaceReady;
    private static File playerFile;
    private static int videoW, videoH;
    private static boolean rendered;
    private static volatile boolean wanted;
    private static Surface playerSurface;
    private static WeakReference<View> thumbRef = new WeakReference<>(null);
    private static boolean preDrawAttached;
    private static boolean textureDirty;

    private static boolean isEnabled() {
        try {
            return Settings.APPLE_MUSIC_ARTWORK_ENABLED.get();
        } catch (Throwable t) {
            return true;
        }
    }

    private static void showCrash(final Activity a, final String crash) {
        try {
            if (a.isFinishing()) return;
            android.widget.TextView tv = new android.widget.TextView(a);
            tv.setText(crash);
            tv.setTextIsSelectable(true);
            tv.setTextSize(10);
            tv.setTypeface(android.graphics.Typeface.MONOSPACE);
            int pad = (int) (12 * a.getResources().getDisplayMetrics().density);
            tv.setPadding(pad, pad, pad, pad);
            android.widget.ScrollView sv = new android.widget.ScrollView(a);
            sv.addView(tv);
            new android.app.AlertDialog.Builder(a)
                    .setTitle("App crashed last time")
                    .setView(sv)
                    .setPositiveButton("Copy", new android.content.DialogInterface.OnClickListener() {
                        @Override public void onClick(android.content.DialogInterface d, int w) {
                            android.content.ClipboardManager cm = (android.content.ClipboardManager) a.getSystemService(Context.CLIPBOARD_SERVICE);
                            cm.setPrimaryClip(android.content.ClipData.newPlainText("crash", crash));
                        }
                    })
                    .setNegativeButton("Dismiss", null)
                    .show();
        } catch (Throwable t) { DebugLog.err("showCrash", t); }
    }

    private static boolean enabled() { return appCtx == null || isEnabled(); }

    public static void onActivityCreate(Activity a) {
        try {
            activityRef = new WeakReference<>(a);
            appCtx = a.getApplicationContext();
            DebugLog.init(appCtx);
            DebugLog.installCrashHandler(appCtx);
            final String crash = DebugLog.takeCrash(appCtx);
            if (crash != null) {
                DebugLog.log("CRASH recorded from last run:\n" + crash);
                final Activity act = a;
                MAIN.postDelayed(new Runnable() { @Override public void run() { showCrash(act, crash); } }, 2000);
            }
            DebugLog.log("MusicActivity created; hook OK; feature " + (enabled() ? "ON" : "OFF"));
            if (pending != null && enabled()) {
                String[] p = pending;
                pending = null;
                startResolve(p[0], p[1], p[2], p[3]);
            }
            if (!polling) {
                polling = true;
                MAIN.postDelayed(TICK, 300);
            }
        } catch (Throwable t) { Log.w(TAG, t); }
    }

    public static void onMetadata(MediaMetadata m) {
        try {
            if (m == null) return;
            final String title = clean(m.getString(MediaMetadata.METADATA_KEY_TITLE));
            final String artist = clean(m.getString(MediaMetadata.METADATA_KEY_ARTIST));
            final String album = clean(m.getString(MediaMetadata.METADATA_KEY_ALBUM));
            final String key = title + "|" + artist + "|" + album;
            if (key.equals(currentKey)) return;
            DebugLog.log("Metadata: title=" + title + " | artist=" + artist + " | album=" + album);
            currentKey = key;
            currentFile = null;
            MAIN.post(new Runnable() { @Override public void run() { if (currentFile == null) { if (texture != null) texture.setAlpha(0f); releasePlayer(); } } });
            if (title == null || artist == null) { DebugLog.log("Skipped: title or artist missing"); return; }
            if (appCtx == null || !enabled()) { DebugLog.log("Waiting (no activity yet or feature off)"); pending = new String[]{key, title, artist, album}; return; }
            sameRetry = 0;
            startResolve(key, title, artist, album);
        } catch (Throwable t) { DebugLog.err("onMetadata", t); }
    }

    private static String clean(String x) { if (x == null) return null; x = x.trim(); return x.isEmpty() ? null : x; }

    private static void startResolve(final String key, final String title, final String artist, final String album) {
        final File dir = new File(appCtx.getCacheDir(), "apple_artwork");
        IO.execute(new Runnable() { @Override public void run() { resolve(key, title, artist, album, dir); } });
    }

    public static void onPlaybackState(PlaybackState s) {
        if (s != null) {
            boolean p = s.getState() == PlaybackState.STATE_PLAYING || s.getState() == PlaybackState.STATE_BUFFERING;
            if (p != playing) DebugLog.log("Playback state -> " + (p ? "playing" : "paused") + " (" + s.getState() + ")");
            playing = p;
        }
    }

    private static String[] cur; // {key,title,artist,album} of the track being shown
    private static int curAttempt;

    private static void resolve(String key, String title, String artist, String album, File dir) {
        try {
            if (!key.equals(currentKey)) { DebugLog.log("Resolve skipped (stale): " + key); return; }
            dir.mkdirs();
            android.content.SharedPreferences prefs = appCtx.getSharedPreferences("apple_artwork", Context.MODE_PRIVATE);
            String id = md5(key);
            int attempt = prefs.getInt(id, 0);
            File out = new File(dir, id + "_" + attempt + ".mp4");
            cur = new String[]{key, title, artist, album};
            curAttempt = attempt;
            if (!out.exists() || out.length() == 0) {
                String sf = Locale.getDefault().getCountry().toLowerCase(Locale.ROOT);
                if (sf.length() != 2) sf = "us";
                String m3u8 = AppleCatalog.findMotion(title, artist, album, sf);
                if (m3u8 == null && album != null) m3u8 = AppleCatalog.findMotion(title, artist, null, sf);
                if (m3u8 == null && !"us".equals(sf)) m3u8 = AppleCatalog.findMotion(title, artist, album, "us");
                if (m3u8 == null && !"us".equals(sf) && album != null) m3u8 = AppleCatalog.findMotion(title, artist, null, "us");
                if (!key.equals(currentKey)) return;
                if (m3u8 == null) { DebugLog.log("RESULT: no motion artwork found for " + key); return; }
                DebugLog.log("Motion artwork m3u8: " + m3u8);
                java.util.List<String> vs = HlsFetcher.variants(m3u8);
                if (attempt >= vs.size()) { DebugLog.log("GAVE UP: all " + vs.size() + " variants failed to play for " + key); return; }
                DebugLog.log("Using variant #" + attempt + " of " + vs.size() + ": " + vs.get(attempt));
                HlsFetcher.fetch(vs.get(attempt), out);
                DebugLog.log("Downloaded " + out.length() + " bytes -> " + out.getName());
                trimCache(dir);
            }
            if (key.equals(currentKey)) { currentFile = out; DebugLog.log("Artwork ready: " + out.getName()); }
            else DebugLog.log("Artwork finished but track changed, dropped");
        } catch (Throwable t) { DebugLog.err("resolve", t); }
    }

    /** The player rejected this file: remember, delete it and retry with the next variant. */
    private static void onPlayFailed(final File bad) {
        try {
            if (sameRetry < 1 && bad != null && bad.exists()) {
                sameRetry++;
                final String key = currentKey;
                DebugLog.log("Playback failed; retrying the same file once");
                MAIN.postDelayed(new Runnable() { @Override public void run() {
                    if (key.equals(currentKey) && currentFile == null) currentFile = bad;
                } }, 500);
                return;
            }
            String[] c = cur;
            if (c == null || appCtx == null) return;
            android.content.SharedPreferences prefs = appCtx.getSharedPreferences("apple_artwork", Context.MODE_PRIVATE);
            prefs.edit().putInt(md5(c[0]), curAttempt + 1).apply();
            if (bad != null) bad.delete();
            DebugLog.log("Playback failed for variant #" + curAttempt + "; retrying with next variant");
            currentKey = c[0];
            startResolve(c[0], c[1], c[2], c[3]);
        } catch (Throwable t) { DebugLog.err("onPlayFailed", t); }
    }

    private static void trimCache(File dir) {
        File[] fs = dir.listFiles();
        if (fs == null || fs.length <= 40) return;
        java.util.Arrays.sort(fs, new java.util.Comparator<File>() { @Override public int compare(File a, File b) { return Long.compare(a.lastModified(), b.lastModified()); } });
        for (int i = 0; i < fs.length - 40; i++) fs[i].delete();
    }

    private static String md5(String s) throws Exception {
        byte[] d = java.security.MessageDigest.getInstance("MD5").digest(s.getBytes("UTF-8"));
        StringBuilder sb = new StringBuilder();
        for (byte b : d) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    // ---------------------------------------------------------------- UI

    private static final Runnable TICK = new Runnable() {
        @Override public void run() {
            long next = 400;
            try { next = sync() ? 150 : 400; } catch (Throwable t) { Log.w(TAG, t); }
            MAIN.postDelayed(this, next);
        }
    };

    /** @return true while the overlay is showing (poll faster so it follows animations). */
    private static boolean sync() {
        Activity a = activityRef.get();
        File f = currentFile;
        if (!enabled()) {
            if (lastEnabled) { lastEnabled = false; DebugLog.log("Feature turned OFF"); }
            currentFile = null;
            hide();
            return false;
        }
        if (!lastEnabled) { lastEnabled = true; DebugLog.log("Feature turned ON"); }
        if (pending != null && appCtx != null) {
            String[] p = pending;
            pending = null;
            if (p[0].equals(currentKey)) startResolve(p[0], p[1], p[2], p[3]);
        }
        if (a == null || a.isFinishing()) { hide(); return false; }
        View thumb = f == null ? null : findThumbnail(a);
        String st = "file=" + (f == null ? "none" : f.getName()) + " thumb=" + (thumb != null)
                + " rendered=" + rendered + " player=" + (player != null) + " surface=" + surfaceReady + " playing=" + playing;
        if (!st.equals(lastState)) { lastState = st; DebugLog.log("STATE " + st); }
        if (thumb == null) { hide(); return false; }

        FrameLayout content = (FrameLayout) a.findViewById(android.R.id.content);
        wanted = true;
        if (player != null && !f.equals(playerFile)) releasePlayer();
        if (textureDirty && player == null) {
            if (texture != null && texture.getParent() == content) content.removeView(texture);
            texture = null;
            surfaceReady = false;
            textureDirty = false;
            DebugLog.log("Fresh video surface");
        }
        thumbRef = new WeakReference<>(thumb);
        int w = thumb.getWidth(), h = thumb.getHeight();

        if (texture == null || texture.getParent() != content) {
            texture = new TextureView(a);
            final float r = 8 * a.getResources().getDisplayMetrics().density;
            texture.setOutlineProvider(new ViewOutlineProvider() {
                @Override public void getOutline(View v, Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), r); }
            });
            texture.setClipToOutline(true);
            texture.setClickable(false);
            texture.setFocusable(false);
            texture.setPivotX(0f);
            texture.setPivotY(0f);
            texture.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
                @Override public void onSurfaceTextureAvailable(SurfaceTexture s, int sw, int sh) { surfaceReady = true; startPlayer(s); }
                @Override public void onSurfaceTextureSizeChanged(SurfaceTexture s, int sw, int sh) { applyCrop(); }
                @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture s) { surfaceReady = false; releasePlayer(); return true; }
                @Override public void onSurfaceTextureUpdated(SurfaceTexture s) { }
            });
            content.addView(texture, new FrameLayout.LayoutParams(w, h));
            if (!preDrawAttached) {
                preDrawAttached = true;
                content.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener() {
                    @Override public boolean onPreDraw() { try { track(); } catch (Throwable ignored) { } return true; }
                });
            }
        } else {
            texture.bringToFront();
        }
        ViewGroup.LayoutParams lp = texture.getLayoutParams();
        if (lp.width != w || lp.height != h) { lp.width = w; lp.height = h; texture.setLayoutParams(lp); }
        texture.setVisibility(View.VISIBLE);
        track();

        if (surfaceReady && player == null) startPlayer(texture.getSurfaceTexture());
        if (player != null) {
            try {
                if (playing && !player.isPlaying()) player.start();
                else if (!playing && player.isPlaying()) player.pause();
            } catch (Throwable ignored) { }
        }
        return true;
    }


    /** Runs every frame: follows the thumbnail's position, scale and fade (sheet animations). */
    private static void track() {
        TextureView tv = texture;
        View th = thumbRef.get();
        if (tv == null || th == null || !th.isAttachedToWindow() || tv.getParent() == null) return;
        if (!wanted) { tv.setAlpha(0f); return; }
        View content = (View) tv.getParent();
        float sx = th.getScaleX(), sy = th.getScaleY(), al = th.getAlpha();
        int[] t = new int[2], c = new int[2];
        th.getLocationInWindow(t);
        content.getLocationInWindow(c);
        int screenH = th.getResources().getDisplayMetrics().heightPixels;
        if (t[1] >= screenH || t[1] + th.getHeight() * sy <= 0) al = 0f;
        tv.setTranslationX(t[0] - c[0]);
        tv.setTranslationY(t[1] - c[1]);
        tv.setScaleX(sx);
        tv.setScaleY(sy);
        tv.setAlpha(rendered ? Math.max(0f, Math.min(1f, al)) : 0f);
    }


    private static void hide() {
        wanted = false;
        if (texture != null) texture.setAlpha(0f);
        if (currentFile == null) {
            releasePlayer(); // track changed or no artwork: drop the old video completely
        } else if (player != null) {
            try { if (player.isPlaying()) player.pause(); } catch (Throwable ignored) { }
        }
    }

    /**
     * The now-playing artwork: the biggest roughly-square visible view inside the player page.
     * The player page id is the same one Morphe's own music patches rely on.
     */
    private static View findThumbnail(Activity a) {
        int id = a.getResources().getIdentifier("player_page", "id", a.getPackageName());
        if (id == 0) return null;
        View page = a.findViewById(id);
        if (page == null || !page.isShown()) return null;
        int screenW = a.getResources().getDisplayMetrics().widthPixels;
        View[] best = new View[1];
        search(page, screenW, best);
        View v = best[0];
        if (v == null) return null;
        return v;
    }

    private static void search(View v, int screenW, View[] best) {
        if (v == texture || v.getVisibility() != View.VISIBLE) return;
        int w = v.getWidth(), h = v.getHeight();
        if (w >= screenW * 0.45f && h > 0 && Math.abs(w - h) <= w * 0.04f && w <= screenW) {
            View b = best[0];
            boolean better = b == null || w * h > b.getWidth() * b.getHeight()
                    || (w * h == b.getWidth() * b.getHeight() && v instanceof ImageView);
            if (better) best[0] = v;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) search(g.getChildAt(i), screenW, best);
        }
    }

    // ---------------------------------------------------------------- playback

    private static void startPlayer(SurfaceTexture st) {
        if (player != null) return;
        File f = currentFile;
        if (f == null || st == null) return;
        rendered = false;
        videoW = videoH = 0;
        DebugLog.log("Starting player for " + f.getName());
        if (texture != null) texture.setAlpha(0f);
        try {
            MediaPlayer mp = new MediaPlayer();
            playerSurface = new Surface(st);
            mp.setSurface(playerSurface);
            mp.setDataSource(f.getAbsolutePath());
            mp.setVolume(0f, 0f);
            mp.setLooping(true);
            mp.setOnVideoSizeChangedListener(new MediaPlayer.OnVideoSizeChangedListener() { @Override public void onVideoSizeChanged(MediaPlayer m, int w, int h) { videoW = w; videoH = h; applyCrop(); } });
            mp.setOnErrorListener(new MediaPlayer.OnErrorListener() { @Override public boolean onError(MediaPlayer m, int what, int extra) {
                DebugLog.log("MediaPlayer ERROR what=" + what + " extra=" + extra);
                File bad = currentFile;
                currentFile = null;
                releasePlayer();
                onPlayFailed(bad);
                return true;
            } });
            mp.setOnInfoListener(new MediaPlayer.OnInfoListener() { @Override public boolean onInfo(MediaPlayer m, int what, int extra) {
                DebugLog.log("Player info " + what);
                if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START && m == player) { rendered = true; DebugLog.log("First frame rendered"); applyCrop(); }
                return false;
            } });
            mp.setOnPreparedListener(new MediaPlayer.OnPreparedListener() { @Override public void onPrepared(MediaPlayer m) { DebugLog.log("Player prepared " + m.getVideoWidth() + "x" + m.getVideoHeight() + " dur=" + m.getDuration() + " playing=" + playing); if (playing) m.start(); } });
            mp.prepareAsync();
            player = mp;
            playerFile = f;
        } catch (Throwable t) { DebugLog.err("startPlayer", t); releasePlayer(); currentFile = null; }
    }

    private static void releasePlayer() {
        MediaPlayer mp = player;
        player = null;
        playerFile = null;
        rendered = false;
        if (mp != null) textureDirty = true; // its SurfaceTexture can't take another MediaPlayer (error -38)
        if (mp != null) { try { mp.setSurface(null); } catch (Throwable ignored) { } try { mp.release(); } catch (Throwable ignored) { } }
        Surface sf = playerSurface;
        playerSurface = null;
        if (sf != null) { try { sf.release(); } catch (Throwable ignored) { } }
    }

    /** Center-crop the video to fill the square. */
    private static void applyCrop() {
        TextureView tv = texture;
        if (tv == null || videoW <= 0 || videoH <= 0 || tv.getWidth() == 0) return;
        float vw = tv.getWidth(), vh = tv.getHeight();
        float s = Math.max(vw / videoW, vh / videoH);
        Matrix m = new Matrix();
        m.setScale(videoW * s / vw, videoH * s / vh, vw / 2f, vh / 2f);
        tv.setTransform(m);
    }
}
