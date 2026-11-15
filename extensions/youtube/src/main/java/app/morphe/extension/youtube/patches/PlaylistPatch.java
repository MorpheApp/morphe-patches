/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4324
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.patches;

import android.app.AlertDialog;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.TouchDelegate;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.facebook.litho.ComponentHost;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.MessageLite;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceType;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.patches.components.BufferAsciiStrings;
import app.morphe.extension.shared.patches.components.ContextInterface;
import app.morphe.extension.shared.patches.components.Filter;
import app.morphe.extension.shared.patches.components.StringFilterGroup;
import app.morphe.extension.youtube.innertube.PlaylistEdit.PlaylistEditAction;
import app.morphe.extension.youtube.innertube.PlaylistEdit.PlaylistEditActionType;
import app.morphe.extension.youtube.innertube.queue.PlaylistQueueRenderer.QueueSaveEditResponse;
import app.morphe.extension.youtube.settings.Settings;
import app.morphe.extension.youtube.shared.PlayerType;

@SuppressWarnings("unused")
public final class PlaylistPatch extends Filter {

    public interface PlaylistEditCallback {
        MessageLite patch_parsePlaylistEditResponse(byte[] bytes);
    }

    private static final String PLAYLIST_BROWSE_ID_PREFIX = "VL";
    private static final String WLPRO_FAB_TAG = "venom_wlpro_floating_button";

    private static final int DRAG_HANDLE_WIDTH_DIP = 32;
    private static final int MENU_BUTTON_WIDTH_DIP = 48;

    private static final int DRAG_HANDLE_CONTAINER_ID = ResourceUtils.getIdentifier(
            ResourceType.ID, "drag_handle_container");

    private static final int DRAG_HANDLE_DESCRIPTION_ID = ResourceUtils.getIdentifier(
            ResourceType.STRING, "accessibility_drag_handle");

    private static volatile boolean playlistShown;
    private static volatile String activePlaylistId = "VLWL";

    // =========================================================================
    // WLPro Smart Tagging & Permanent SQLite State
    // =========================================================================
    private static volatile VenomTagDatabase dbInstance;
    private static final ExecutorService TAG_EXECUTOR = Executors.newFixedThreadPool(2);
    private static final Set<String> inFlightVideoIds = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private static volatile WeakReference<TextView> activeFabRef = new WeakReference<>(null);

    private static final Pattern VIDEO_ID_PATTERN = Pattern.compile("(?:/vi/|/vi_webp/|watch\\?v=)([a-zA-Z0-9_-]{11})");
    private static final Pattern YEAR_PATTERN = Pattern.compile("\\b(202[0-9]|201[0-9])\\b");

    @Nullable
    private static RowLongPress rowLongPress;
    private static boolean dispatchingCancel;

    private final StringFilterGroup playlistRowGroup = new StringFilterGroup(
            Settings.PLAYLIST_WIDE_DRAG_HANDLES,
            "video_lockup",
            "playlist_video",
            "compact_video",
            "video_with_context"
    );

    public PlaylistPatch() {
        addPathCallbacks(playlistRowGroup);
    }

    @Override
    public boolean isFiltered(ContextInterface contextInterface,
                              String identifier,
                              String accessibility,
                              CharSequence path,
                              byte[] buffer,
                              BufferAsciiStrings asciiStrings,
                              StringFilterGroup matchedGroup,
                              FilterContentType contentType,
                              int contentIndex) {
        if (playlistShown && buffer != null && buffer.length > 0) {
            scanBufferForPlaylistVideo(buffer, accessibility);
        }
        return false; // Never hide playlist items; only index them
    }

    private static final class DragHandleTouchDelegate extends TouchDelegate {
        final Rect area;

        DragHandleTouchDelegate(Rect area, View handle) {
            super(area, handle);
            this.area = area;
        }
    }

    /**
     * Injection point.
     */
    public static void setBrowseId(@Nullable String browseId) {
        playlistShown = browseId != null && browseId.startsWith(PLAYLIST_BROWSE_ID_PREFIX)
                && browseId.length() > PLAYLIST_BROWSE_ID_PREFIX.length();
        if (playlistShown) {
            activePlaylistId = normalizePlaylistId(browseId);
        }
        updateFloatingTagButtonVisibility();
    }

    /**
     * Injection point.
     */
    public static void clearBrowseId() {
        playlistShown = false;
        updateFloatingTagButtonVisibility();
    }

    private static boolean isPlaylistShown() {
        return playlistShown && !PlayerType.getCurrent().isMaximizedOrFullscreen();
    }

    private static boolean isWideDragHandlesActive() {
        return Settings.PLAYLIST_WIDE_DRAG_HANDLES.get() && isPlaylistShown();
    }

    private static int dipToPixels(View view, int dip) {
        return Math.round(dip * view.getResources().getDisplayMetrics().density);
    }

    private static int dp(Context ctx, int dp) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, dp, ctx.getResources().getDisplayMetrics());
    }

    @Nullable
    private static View findDragHandle(ViewGroup host) {
        String description = host.getResources().getString(DRAG_HANDLE_DESCRIPTION_ID);
        for (int i = 0, count = host.getChildCount(); i < count; i++) {
            View child = host.getChildAt(i);
            CharSequence childDescription = child.getContentDescription();
            if (childDescription != null && description.contentEquals(childDescription)) return child;
        }
        return null;
    }

    public static void onDragHandleCreated(View handle) {
        try {
            if (!isWideDragHandlesActive()) return;

            final int width = dipToPixels(handle, DRAG_HANDLE_WIDTH_DIP);
            if (handle.getParent() instanceof View container && container.getId() == DRAG_HANDLE_CONTAINER_ID) {
                if (widen(container, width)) {
                    container.post(() -> container.setTouchDelegate(new TouchDelegate(
                            new Rect(0, 0, container.getWidth(), container.getHeight()), handle)));
                }
            } else {
                widen(handle, width);
            }
        } catch (Exception ex) {
            Logger.printException(() -> "onDragHandleCreated failure", ex);
        }
    }

    private static boolean widen(View view, int width) {
        ViewGroup.LayoutParams params = view.getLayoutParams();
        if (params == null || params.width <= 0 || params.width >= width) return false;

        params.width = width;
        view.setLayoutParams(params);
        return true;
    }

    public static void onComponentHostLayout(ComponentHost host) {
        try {
            if (isPlaylistShown()) {
                host.post(() -> {
                    ensureFloatingWLProButton(host);
                    if (Settings.PLAYLIST_WIDE_DRAG_HANDLES.get()) {
                        updateDragHandleTouchDelegate(host);
                    }
                });
            } else {
                removeDragHandleTouchDelegate(host);
                updateFloatingTagButtonVisibility();
            }
        } catch (Exception ex) {
            Logger.printException(() -> "onComponentHostLayout failure", ex);
        }
    }

    private static void updateDragHandleTouchDelegate(ComponentHost host) {
        try {
            View handle = isWideDragHandlesActive() ? findDragHandle(host) : null;
            if (handle == null) {
                removeDragHandleTouchDelegate(host);
                return;
            }

            final int width = dipToPixels(host, DRAG_HANDLE_WIDTH_DIP);
            final int hostWidth = host.getWidth();
            final int hostHeight = host.getHeight();
            Rect area = host.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL
                    ? new Rect(hostWidth - width, 0, hostWidth, hostHeight)
                    : new Rect(0, 0, width, hostHeight);
            host.setTouchDelegate(new DragHandleTouchDelegate(area, handle));
        } catch (Exception ex) {
            Logger.printException(() -> "updateDragHandleTouchDelegate failure", ex);
        }
    }

    private static void removeDragHandleTouchDelegate(ComponentHost host) {
        if (host.getTouchDelegate() instanceof DragHandleTouchDelegate) {
            host.setTouchDelegate(null);
        }
    }

    public static boolean onComponentHostInterceptTouch(ComponentHost host, MotionEvent event) {
        try {
            return Settings.PLAYLIST_WIDE_DRAG_HANDLES.get()
                    && event.getActionMasked() == MotionEvent.ACTION_DOWN
                    && host.getTouchDelegate() instanceof DragHandleTouchDelegate delegate
                    && isPlaylistShown()
                    && delegate.area.contains((int) event.getX(), (int) event.getY());
        } catch (Exception ex) {
            Logger.printException(() -> "onComponentHostInterceptTouch failure", ex);
            return false;
        }
    }

    public static boolean onComponentHostTouch(ComponentHost host, MotionEvent event) {
        if (dispatchingCancel || !Settings.PLAYLIST_LONG_PRESS_DRAG.get()) return false;
        try {
            final int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                onRowTouchDown(host, event);
            }

            RowLongPress longPress = rowLongPress;
            if (longPress == null || longPress.hostRef.get() != host) return false;

            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                rowLongPress = null;
            }
            if (longPress.dragging) {
                longPress.forwardToHandle(event);
                return true;
            }
            longPress.detector.onTouchEvent(event);
            return false;
        } catch (Exception ex) {
            rowLongPress = null;
            Logger.printException(() -> "onComponentHostTouch failure", ex);
            return false;
        }
    }

    private static void onRowTouchDown(ComponentHost host, MotionEvent event) {
        if (rowLongPress != null && rowLongPress.hostRef.get() == host) {
            rowLongPress = null;
        }
        if (!isPlaylistShown()) return;

        View handle = findDragHandle(host);
        if (handle == null) return;

        final int hostWidth = host.getWidth();
        final float x = host.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL
                ? hostWidth - event.getX()
                : event.getX();
        if (x < dipToPixels(host, DRAG_HANDLE_WIDTH_DIP)
                || x > hostWidth - dipToPixels(host, MENU_BUTTON_WIDTH_DIP)) {
            return;
        }

        rowLongPress = new RowLongPress(host, handle);
    }

    private static final class RowLongPress extends GestureDetector.SimpleOnGestureListener {
        private final WeakReference<ComponentHost> hostRef;
        private final WeakReference<View> handleRef;
        private final GestureDetector detector;
        private final int[] downLocation = new int[2];
        private final int[] currentLocation = new int[2];
        private boolean dragging;

        RowLongPress(ComponentHost host, View handle) {
            hostRef = new WeakReference<>(host);
            handleRef = new WeakReference<>(handle);
            host.getLocationOnScreen(downLocation);
            detector = new GestureDetector(host.getContext().getApplicationContext(), this);
        }

        @Override
        public void onLongPress(@NonNull MotionEvent event) {
            try {
                ComponentHost host = hostRef.get();
                View handle = handleRef.get();
                if (rowLongPress != this || host == null || handle == null
                        || !host.isAttachedToWindow() || !isPlaylistShown()
                        || findDragHandle(host) != handle) {
                    return;
                }
                host.getLocationOnScreen(currentLocation);
                if (currentLocation[0] != downLocation[0] || currentLocation[1] != downLocation[1]) {
                    return;
                }

                cancelChildrenTouch(host, event);
                host.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);

                dragging = true;
                MotionEvent down = MotionEvent.obtain(event);
                try {
                    down.setAction(MotionEvent.ACTION_DOWN);
                    dispatchToHandleCenter(handle, down);
                } finally {
                    down.recycle();
                }
            } catch (Exception ex) {
                Logger.printException(() -> "onLongPress failure", ex);
            }
        }

        private static void cancelChildrenTouch(ComponentHost host, MotionEvent event) {
            MotionEvent cancel = MotionEvent.obtain(event);
            dispatchingCancel = true;
            try {
                cancel.setAction(MotionEvent.ACTION_CANCEL);
                host.dispatchTouchEvent(cancel);
            } finally {
                dispatchingCancel = false;
                cancel.recycle();
            }
        }

        void forwardToHandle(MotionEvent event) {
            View handle = handleRef.get();
            if (handle != null) {
                dispatchToHandleCenter(handle, event);
            }
        }

        private static void dispatchToHandleCenter(View handle, MotionEvent event) {
            MotionEvent copy = MotionEvent.obtain(event);
            try {
                copy.setLocation(handle.getWidth() / 2f, handle.getHeight() / 2f);
                handle.dispatchTouchEvent(copy);
            } finally {
                copy.recycle();
            }
        }
    }

    public static MessageLite onPlaylistEditResponse(PlaylistEditCallback callback,
                                                     @Nullable String playlistId,
                                                     List<? extends MessageLite> actions,
                                                     MessageLite response) {
        if (!Settings.PLAYLIST_HIDE_REMOVAL_SNACKBAR.get()) return response;

        try {
            if (!RemovalSnackbar.isPlaylistRemoval(playlistId, actions)) return response;

            var result = QueueSaveEditResponse.parseFrom(response.toByteArray());
            var filtered = RemovalSnackbar.removeToasts(result);
            return filtered == result
                    ? response
                    : callback.patch_parsePlaylistEditResponse(filtered.toByteArray());
        } catch (Exception ex) {
            Logger.printException(() -> "onPlaylistEditResponse failure", ex);
            return response;
        }
    }

    static final class RemovalSnackbar {
        private static final int STATUS_SUCCEEDED = 1;

        static boolean isPlaylistRemoval(@Nullable String playlistId, List<? extends MessageLite> actions)
                throws InvalidProtocolBufferException {
            if (playlistId == null || playlistId.isEmpty() || actions.isEmpty()) return false;

            for (MessageLite appAction : actions) {
                var action = PlaylistEditAction.parseFrom(appAction.toByteArray()).getAction();
                if (action != PlaylistEditActionType.ACTION_REMOVE_VIDEO
                        && action != PlaylistEditActionType.ACTION_REMOVE_VIDEO_BY_VIDEO_ID) {
                    return false;
                }
            }
            return true;
        }

        static QueueSaveEditResponse removeToasts(QueueSaveEditResponse response) {
            if (response.getStatus() != STATUS_SUCCEEDED) return response;

            QueueSaveEditResponse.Builder builder = null;
            for (int i = 0, count = response.getActionsCount(); i < count; i++) {
                var action = response.getActions(i);
                if (action.hasAddToToastAction()) {
                    if (builder == null) builder = response.toBuilder();
                    builder.setActions(i, action.toBuilder().clearAddToToastAction());
                }
            }
            return builder == null ? response : builder.build();
        }
    }

    // =========================================================================
    // WLPro Smart Tagging Engine, SQLite Database & Playlist Filter UI
    // =========================================================================

    private static synchronized VenomTagDatabase getDb(Context context) {
        if (dbInstance == null) {
            dbInstance = new VenomTagDatabase(context.getApplicationContext());
        }
        return dbInstance;
    }

    private static String normalizePlaylistId(@Nullable String rawId) {
        if (rawId == null || rawId.isEmpty()) return "VLWL";
        if (rawId.equals("WL") || rawId.equals("VLWL")) return "VLWL";
        if (rawId.startsWith("VLPL")) return rawId.substring(2);
        return rawId;
    }

    private static void scanBufferForPlaylistVideo(byte[] buffer, @Nullable String accessibility) {
        try {
            String raw = new String(buffer, StandardCharsets.UTF_8);
            Matcher m = VIDEO_ID_PATTERN.matcher(raw);
            if (!m.find()) return;

            String videoId = m.group(1);
            if (videoId == null || videoId.length() != 11) return;

            Context ctx = Utils.getContext();
            if (ctx == null) return;

            String fallbackTitle = "";
            String fallbackChannel = "";
            if (accessibility != null && accessibility.contains(" - ")) {
                String[] parts = accessibility.split(" - ");
                if (parts.length > 0) fallbackTitle = parts[0].trim();
            }

            VenomTagDatabase db = getDb(ctx);
            db.linkVideoToPlaylist(activePlaylistId, videoId, fallbackTitle, fallbackChannel);

            if (!db.isVideoTagged(videoId)) {
                fetchAndClassifyVideoAsync(ctx, videoId, fallbackTitle, fallbackChannel);
            }
        } catch (Exception ignored) {}
    }

    private static void fetchAndClassifyVideoAsync(final Context context,
                                                   final String videoId,
                                                   final String fallbackTitle,
                                                   final String fallbackChannel) {
        final VenomTagDatabase db = getDb(context);
        if (db.isVideoTagged(videoId) || !inFlightVideoIds.add(videoId)) {
            return;
        }

        TAG_EXECUTOR.submit(() -> {
            try {
                URL url = new URL("https://www.youtube.com/youtubei/v1/player?prettyPrint=false");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);

                String body = "{\"context\":{\"client\":{\"clientName\":\"ANDROID\",\"clientVersion\":\"21.30.216\"}},\"videoId\":\"" + videoId + "\"}";
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(body.getBytes(StandardCharsets.UTF_8));
                }

                if (conn.getResponseCode() != 200) return;

                StringBuilder sb = new StringBuilder();
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = br.readLine()) != null) sb.append(line);
                }

                JSONObject root = new JSONObject(sb.toString());
                JSONObject videoDetails = root.optJSONObject("videoDetails");
                if (videoDetails == null) return;

                String title = videoDetails.optString("title", fallbackTitle);
                String author = videoDetails.optString("author", fallbackChannel);
                String shortDesc = videoDetails.optString("shortDescription", "");
                if (shortDesc.length() > 500) shortDesc = shortDesc.substring(0, 500);

                String ytCategory = "";
                JSONObject microformat = root.optJSONObject("microformat");
                if (microformat != null) {
                    JSONObject renderer = microformat.optJSONObject("playerMicroformatRenderer");
                    if (renderer != null) {
                        ytCategory = renderer.optString("category", "");
                    }
                }

                List<String> keywords = new ArrayList<>();
                JSONArray kwArr = videoDetails.optJSONArray("keywords");
                if (kwArr != null) {
                    for (int i = 0; i < Math.min(kwArr.length(), 15); i++) {
                        String k = kwArr.optString(i, "").trim();
                        if (!k.isEmpty()) keywords.add(k);
                    }
                }

                SmartTagResult res = SmartTagClassifier.classify(title, author, shortDesc, ytCategory, keywords);
                db.saveTaggedVideo(videoId, title, author, res.primaryCategory, res.subBadge, res.yearTag, res.smartTagsCsv);
                updateFloatingTagButtonVisibility();
            } catch (Exception ignored) {
            } finally {
                inFlightVideoIds.remove(videoId);
            }
        });
    }

    private static void ensureFloatingWLProButton(View anchorView) {
        try {
            View root = anchorView.getRootView();
            if (root == null) return;
            ViewGroup content = root.findViewById(android.R.id.content);
            if (content == null) return;

            TextView fab = content.findViewWithTag(WLPRO_FAB_TAG);
            Context ctx = content.getContext();

            if (fab == null) {
                fab = new TextView(ctx);
                fab.setTag(WLPRO_FAB_TAG);
                fab.setTextColor(Color.WHITE);
                fab.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
                fab.setTypeface(Typeface.DEFAULT_BOLD);
                fab.setGravity(Gravity.CENTER);
                fab.setPadding(dp(ctx, 14), dp(ctx, 9), dp(ctx, 14), dp(ctx, 9));
                fab.setElevation(dp(ctx, 6));

                GradientDrawable bg = new GradientDrawable();
                bg.setColor(Color.parseColor("#E51C23"));
                bg.setCornerRadius(dp(ctx, 22));
                bg.setStroke(dp(ctx, 1), Color.parseColor("#FF5252"));
                fab.setBackground(bg);

                FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        Gravity.BOTTOM | Gravity.END
                );
                lp.setMargins(dp(ctx, 16), dp(ctx, 16), dp(ctx, 16), dp(ctx, 72));
                content.addView(fab, lp);
                fab.setOnClickListener(v -> showWLProDialog(v.getContext()));
                activeFabRef = new WeakReference<>(fab);
            }

            int count = getDb(ctx).getPlaylistVideoCount(activePlaylistId);
            fab.setText("WLPro Tags (" + count + ")");
            fab.setVisibility(isPlaylistShown() ? View.VISIBLE : View.GONE);
        } catch (Exception ignored) {}
    }

    private static void updateFloatingTagButtonVisibility() {
        Utils.runOnMainThreadNowOrLater(() -> {
            TextView fab = activeFabRef.get();
            if (fab != null) {
                if (isPlaylistShown()) {
                    int count = getDb(fab.getContext()).getPlaylistVideoCount(activePlaylistId);
                    fab.setText("WLPro Tags (" + count + ")");
                    fab.setVisibility(View.VISIBLE);
                } else {
                    fab.setVisibility(View.GONE);
                }
            }
        });
    }

    private static final class SmartTagResult {
        final String primaryCategory;
        final String subBadge;
        final String yearTag;
        final String smartTagsCsv;

        SmartTagResult(String primaryCategory, String subBadge, String yearTag, String smartTagsCsv) {
            this.primaryCategory = primaryCategory;
            this.subBadge = subBadge;
            this.yearTag = yearTag;
            this.smartTagsCsv = smartTagsCsv;
        }
    }

    private static final class SmartTagClassifier {
        private static final Set<String> SPAM_KEYWORDS = new HashSet<>(Arrays.asList(
                "viral", "trending", "youtube", "video", "shorts", "subscribe", "share", "like",
                "new", "latest", "best", "top", "full", "hd", "4k", "official", "live"
        ));

        static SmartTagResult classify(String title,
                                       String channel,
                                       String description,
                                       String ytCategory,
                                       List<String> keywords) {
            String titleLow = title.toLowerCase(Locale.ROOT);
            String channelLow = channel.toLowerCase(Locale.ROOT);
            String combinedLow = titleLow + " " + channelLow + " " + description.toLowerCase(Locale.ROOT);
            String catLow = ytCategory.toLowerCase(Locale.ROOT);

            String yearTag = "";
            Matcher ym = YEAR_PATTERN.matcher(title);
            if (ym.find()) yearTag = ym.group(1);

            String primary = "Other";
            String subBadge = "Other";
            LinkedHashSet<String> tags = new LinkedHashSet<>();

            if (containsAny(combinedLow, "cricket", "odi", "t20", "ipl", "test match", "wicket", "batting", "bowling", "icc", "bcci")
                    || (containsAny(titleLow, "beat", "vs", "v ", "highlights", "innings")
                        && containsAny(combinedLow, "india", "pakistan", "australia", "england", "new zealand", "bangladesh", "south africa"))) {
                primary = "Sports";
                subBadge = "Sports · Cricket";
                tags.add("#Cricket");
                if (containsAny(combinedLow, "odi")) tags.add("#ODI");
                if (containsAny(combinedLow, "t20")) tags.add("#T20");
                if (containsAny(combinedLow, "highlights")) tags.add("#Highlights");
            } else if (catLow.contains("sports") || containsAny(combinedLow, "football", "fifa", "premier league", "champions league", "nba", "ufc", "wwe", "f1", "formula 1", "match highlights")) {
                primary = "Sports";
                subBadge = containsAny(combinedLow, "football", "fifa", "premier league") ? "Sports · Football" : "Sports";
                tags.add("#Sports");
            } else if (containsAny(combinedLow, "gurbani", "kirtan", "sgpc", "harmandir", "darbar sahib", "shabad", "waheguru", "bhajan", "aarti", "ramayana", "mahabharat")) {
                primary = "Devotional";
                subBadge = "Devotional";
                tags.add("#Devotional");
                if (containsAny(combinedLow, "gurbani", "kirtan")) tags.add("#Gurbani");
            } else if (catLow.contains("science") || catLow.contains("tech")
                    || containsAny(combinedLow, "android", "apk", "github", "kotlin", "javascript", "ai ", "llm", "gemini", "deepseek", "termux", "browser", "extension", "smartphone")) {
                primary = "Tech";
                subBadge = containsAny(combinedLow, "android", "apk", "kotlin", "mod") ? "Tech · Android" : "Tech";
                tags.add("#Tech");
            } else if (catLow.contains("gaming")
                    || containsAny(combinedLow, "clash of clans", "brawl stars", "boom beach", "fortnite", "minecraft", "gta", "warhammer", "gameplay", "esports")) {
                primary = "Gaming";
                subBadge = "Gaming";
                tags.add("#Gaming");
            } else if (catLow.contains("music")
                    || containsAny(combinedLow, "official video", "official audio", "lyrics", "song", "album", "remix", "bgm", "soundtrack", "ost")) {
                primary = "Music";
                subBadge = "Music";
                tags.add("#Music");
            } else if (catLow.contains("film") || catLow.contains("entertainment")
                    || containsAny(combinedLow, "trailer", "teaser", "movie", "cinema", "episode", "anime", "manga", "iruma", "dc ", "marvel", "the boys")) {
                primary = "Cinema";
                subBadge = containsAny(combinedLow, "anime", "manga") ? "Cinema · Anime" : "Cinema";
                tags.add("#Cinema");
            } else if (catLow.contains("news")
                    || containsAny(combinedLow, "breaking news", "news", "geopolitics", "election")) {
                primary = "News";
                subBadge = "News";
                tags.add("#News");
            } else if (catLow.contains("howto") || catLow.contains("education")
                    || containsAny(titleLow, "how to ", "tutorial", "step by step", "guide", "course", "explained")) {
                primary = "How-to";
                subBadge = "How-to";
                tags.add("#Tutorial");
            }

            for (String kw : keywords) {
                if (tags.size() >= 5) break;
                String clean = kw.replaceAll("[^a-zA-Z0-9 ]", "").trim();
                String cleanLow = clean.toLowerCase(Locale.ROOT);
                if (clean.length() < 3 || clean.length() > 20 || SPAM_KEYWORDS.contains(cleanLow)) continue;
                if (("Sports".equals(primary) || "News".equals(primary))
                        && (cleanLow.contains("how to") || cleanLow.contains("tutorial"))) {
                    continue;
                }
                tags.add(toHashtag(clean));
            }

            StringBuilder csv = new StringBuilder();
            for (String t : tags) {
                if (csv.length() > 0) csv.append("  ");
                csv.append(t);
            }
            return new SmartTagResult(primary, subBadge, yearTag, csv.toString());
        }

        private static boolean containsAny(String text, String... needles) {
            for (String n : needles) {
                if (text.contains(n)) return true;
            }
            return false;
        }

        private static String toHashtag(String phrase) {
            String[] parts = phrase.split("\\s+");
            StringBuilder sb = new StringBuilder("#");
            for (String p : parts) {
                if (!p.isEmpty()) {
                    sb.append(Character.toUpperCase(p.charAt(0)));
                    if (p.length() > 1) sb.append(p.substring(1));
                }
            }
            return sb.toString();
        }
    }

    private static final class VideoTagRecord {
        final String videoId;
        final String title;
        final String channelName;
        final String primaryCategory;
        final String subBadge;
        final String yearTag;
        final String smartTagsCsv;
        final boolean isTagged;

        VideoTagRecord(String videoId, String title, String channelName, String primaryCategory,
                       String subBadge, String yearTag, String smartTagsCsv, boolean isTagged) {
            this.videoId = videoId;
            this.title = title != null ? title : "";
            this.channelName = channelName != null ? channelName : "";
            this.primaryCategory = primaryCategory != null && !primaryCategory.isEmpty() ? primaryCategory : "Other";
            this.subBadge = subBadge != null && !subBadge.isEmpty() ? subBadge : "Other";
            this.yearTag = yearTag != null ? yearTag : "";
            this.smartTagsCsv = smartTagsCsv != null ? smartTagsCsv : "";
            this.isTagged = isTagged;
        }
    }

    private static final class VenomTagDatabase extends SQLiteOpenHelper {
        private static final String DB_NAME = "venom_playlist_tags.db";
        private static final int DB_VERSION = 2;

        VenomTagDatabase(Context context) {
            super(context, DB_NAME, null, DB_VERSION);
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE IF NOT EXISTS playlist_videos (" +
                    "video_id TEXT PRIMARY KEY, " +
                    "title TEXT, " +
                    "channel_name TEXT, " +
                    "primary_category TEXT, " +
                    "sub_badge TEXT, " +
                    "year_tag TEXT, " +
                    "smart_tags_csv TEXT, " +
                    "is_tagged INTEGER DEFAULT 0, " +
                    "updated_at INTEGER)");

            db.execSQL("CREATE TABLE IF NOT EXISTS playlist_membership (" +
                    "playlist_id TEXT NOT NULL, " +
                    "video_id TEXT NOT NULL, " +
                    "added_at INTEGER, " +
                    "PRIMARY KEY (playlist_id, video_id))");
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            db.execSQL("DROP TABLE IF EXISTS playlist_videos");
            db.execSQL("DROP TABLE IF EXISTS playlist_membership");
            onCreate(db);
        }

        synchronized void linkVideoToPlaylist(String playlistId, String videoId, String title, String channel) {
            try {
                SQLiteDatabase db = getWritableDatabase();
                ContentValues link = new ContentValues();
                link.put("playlist_id", playlistId);
                link.put("video_id", videoId);
                link.put("added_at", System.currentTimeMillis());
                db.insertWithOnConflict("playlist_membership", null, link, SQLiteDatabase.CONFLICT_IGNORE);

                ContentValues stub = new ContentValues();
                stub.put("video_id", videoId);
                if (title != null && !title.isEmpty()) stub.put("title", title);
                if (channel != null && !channel.isEmpty()) stub.put("channel_name", channel);
                stub.put("is_tagged", 0);
                stub.put("updated_at", System.currentTimeMillis());
                db.insertWithOnConflict("playlist_videos", null, stub, SQLiteDatabase.CONFLICT_IGNORE);
            } catch (Exception ignored) {}
        }

        synchronized boolean isVideoTagged(String videoId) {
            try (Cursor c = getReadableDatabase().rawQuery(
                    "SELECT is_tagged FROM playlist_videos WHERE video_id = ?",
                    new String[]{videoId})) {
                if (c.moveToFirst()) return c.getInt(0) == 1;
            } catch (Exception ignored) {}
            return false;
        }

        synchronized int getPlaylistVideoCount(String playlistId) {
            try (Cursor c = getReadableDatabase().rawQuery(
                    "SELECT COUNT(*) FROM playlist_membership WHERE playlist_id = ?",
                    new String[]{playlistId})) {
                if (c.moveToFirst()) return c.getInt(0);
            } catch (Exception ignored) {}
            return 0;
        }

        synchronized void saveTaggedVideo(String videoId, String title, String channelName,
                                          String primaryCategory, String subBadge, String yearTag, String smartTagsCsv) {
            try {
                ContentValues cv = new ContentValues();
                cv.put("video_id", videoId);
                cv.put("title", title);
                cv.put("channel_name", channelName);
                cv.put("primary_category", primaryCategory);
                cv.put("sub_badge", subBadge);
                cv.put("year_tag", yearTag);
                cv.put("smart_tags_csv", smartTagsCsv);
                cv.put("is_tagged", 1);
                cv.put("updated_at", System.currentTimeMillis());
                getWritableDatabase().insertWithOnConflict("playlist_videos", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
            } catch (Exception ignored) {}
        }

        synchronized List<VideoTagRecord> queryPlaylist(String playlistId, String searchQuery,
                                                        String categoryFilter, String channelFilter) {
            List<VideoTagRecord> list = new ArrayList<>();
            String q = "%" + (searchQuery == null ? "" : searchQuery.trim().toLowerCase(Locale.ROOT)) + "%";
            StringBuilder sql = new StringBuilder();
            List<String> args = new ArrayList<>();

            sql.append("SELECT v.video_id, v.title, v.channel_name, v.primary_category, v.sub_badge, v.year_tag, v.smart_tags_csv, v.is_tagged ")
               .append("FROM playlist_videos v ")
               .append("INNER JOIN playlist_membership m ON v.video_id = m.video_id ")
               .append("WHERE m.playlist_id = ? ");
            args.add(playlistId);

            sql.append("AND (LOWER(COALESCE(v.title,'')) LIKE ? ")
               .append("OR LOWER(COALESCE(v.channel_name,'')) LIKE ? ")
               .append("OR LOWER(COALESCE(v.sub_badge,'')) LIKE ? ")
               .append("OR LOWER(COALESCE(v.smart_tags_csv,'')) LIKE ?) ");
            args.add(q);
            args.add(q);
            args.add(q);
            args.add(q);

            if (categoryFilter != null && !categoryFilter.isEmpty()) {
                sql.append("AND LOWER(COALESCE(v.primary_category,'')) = ? ");
                args.add(categoryFilter.toLowerCase(Locale.ROOT));
            }
            if (channelFilter != null && !channelFilter.isEmpty()) {
                sql.append("AND LOWER(COALESCE(v.channel_name,'')) = ? ");
                args.add(channelFilter.toLowerCase(Locale.ROOT));
            }

            sql.append("ORDER BY m.added_at DESC LIMIT 500");

            try (Cursor c = getReadableDatabase().rawQuery(sql.toString(), args.toArray(new String[0]))) {
                while (c.moveToNext()) {
                    list.add(new VideoTagRecord(
                            c.getString(0), c.getString(1), c.getString(2),
                            c.getString(3), c.getString(4), c.getString(5),
                            c.getString(6), c.getInt(7) == 1
                    ));
                }
            } catch (Exception ignored) {}
            return list;
        }
    }

    private static void showWLProDialog(final Context context) {
        final VenomTagDatabase db = getDb(context);
        final List<VideoTagRecord> allItems = db.queryPlaylist(activePlaylistId, "", "", "");

        int taggedCount = 0;
        Map<String, Integer> categoryCounts = new LinkedHashMap<>();
        Map<String, Integer> channelCounts = new LinkedHashMap<>();

        for (VideoTagRecord rec : allItems) {
            if (rec.isTagged) taggedCount++;
            Integer catC = categoryCounts.get(rec.primaryCategory);
            categoryCounts.put(rec.primaryCategory, catC == null ? 1 : catC + 1);

            if (!rec.channelName.isEmpty()) {
                Integer chC = channelCounts.get(rec.channelName);
                channelCounts.put(rec.channelName, chC == null ? 1 : chC + 1);
            }
        }
        int percentTagged = allItems.isEmpty() ? 0 : Math.round((taggedCount * 100f) / allItems.size());

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#0F0F0F"));
        int pad = dp(context, 14);
        root.setPadding(pad, pad, pad, pad);

        TextView statsHeader = new TextView(context);
        statsHeader.setText("WLPro  •  " + allItems.size() + " videos  •  " + percentTagged + "% tagged");
        statsHeader.setTextColor(Color.parseColor("#CCCCCC"));
        statsHeader.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        statsHeader.setTypeface(Typeface.DEFAULT_BOLD);
        statsHeader.setPadding(0, 0, 0, dp(context, 10));
        root.addView(statsHeader);

        final EditText searchBox = new EditText(context);
        searchBox.setHint("Search title, channel, tag...");
        searchBox.setTextColor(Color.WHITE);
        searchBox.setHintTextColor(Color.parseColor("#888888"));
        searchBox.setSingleLine(true);
        searchBox.setPadding(dp(context, 12), dp(context, 10), dp(context, 12), dp(context, 10));
        GradientDrawable searchBg = new GradientDrawable();
        searchBg.setColor(Color.parseColor("#1C1C1C"));
        searchBg.setCornerRadius(dp(context, 12));
        searchBg.setStroke(dp(context, 1), Color.parseColor("#333333"));
        searchBox.setBackground(searchBg);
        root.addView(searchBox);

        final String[] activeCategory = {""};
        final String[] activeChannel = {""};

        HorizontalScrollView chipScroll = new HorizontalScrollView(context);
        chipScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout chipRow = new LinearLayout(context);
        chipRow.setOrientation(LinearLayout.HORIZONTAL);
        chipRow.setPadding(0, dp(context, 10), 0, dp(context, 10));

        final List<VideoTagRecord> filteredItems = new ArrayList<>(allItems);
        final WLProVideoAdapter adapter = new WLProVideoAdapter(context, filteredItems);
        ListView listView = new ListView(context);
        listView.setAdapter(adapter);
        listView.setDividerHeight(dp(context, 1));

        final Runnable applyFilters = () -> {
            filteredItems.clear();
            filteredItems.addAll(db.queryPlaylist(
                    activePlaylistId,
                    searchBox.getText().toString(),
                    activeCategory[0],
                    activeChannel[0]
            ));
            adapter.notifyDataSetChanged();
        };

        chipRow.addView(createPill(context, "All (" + allItems.size() + ")", true, v -> {
            activeCategory[0] = "";
            activeChannel[0] = "";
            applyFilters.run();
        }));

        for (Map.Entry<String, Integer> entry : categoryCounts.entrySet()) {
            final String cat = entry.getKey();
            chipRow.addView(createPill(context, cat + "  " + entry.getValue(), false, v -> {
                activeCategory[0] = cat.equals(activeCategory[0]) ? "" : cat;
                applyFilters.run();
            }));
        }

        for (Map.Entry<String, Integer> entry : channelCounts.entrySet()) {
            final String ch = entry.getKey();
            chipRow.addView(createPill(context, "👤 " + ch + "  " + entry.getValue(), false, v -> {
                activeChannel[0] = ch.equals(activeChannel[0]) ? "" : ch;
                applyFilters.run();
            }));
        }

        chipScroll.addView(chipRow);
        root.addView(chipScroll);

        root.addView(listView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 380)));

        searchBox.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { applyFilters.run(); }
            @Override public void afterTextChanged(Editable s) {}
        });

        final AlertDialog dialog = new AlertDialog.Builder(context)
                .setView(root)
                .setNegativeButton("Close", null)
                .create();

        listView.setOnItemClickListener((parent, view, position, id) -> {
            if (position >= 0 && position < filteredItems.size()) {
                VideoTagRecord selected = filteredItems.get(position);
                dialog.dismiss();
                String url = "https://www.youtube.com/watch?v=" + selected.videoId;
                LoadVideoPatch.openVideoIntent(url, false);
            }
        });

        dialog.show();
    }

    private static final class WLProVideoAdapter extends BaseAdapter {
        private final Context context;
        private final List<VideoTagRecord> items;

        WLProVideoAdapter(Context context, List<VideoTagRecord> items) {
            this.context = context;
            this.items = items;
        }

        @Override public int getCount() { return items.size(); }
        @Override public Object getItem(int position) { return items.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            LinearLayout card;
            TextView badgeTv;
            TextView titleTv;
            TextView metaTv;

            if (convertView instanceof LinearLayout existing) {
                card = existing;
                badgeTv = (TextView) card.getChildAt(0);
                titleTv = (TextView) card.getChildAt(1);
                metaTv = (TextView) card.getChildAt(2);
            } else {
                card = new LinearLayout(context);
                card.setOrientation(LinearLayout.VERTICAL);
                card.setPadding(dp(context, 4), dp(context, 10), dp(context, 4), dp(context, 10));

                badgeTv = new TextView(context);
                badgeTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
                badgeTv.setTypeface(Typeface.DEFAULT_BOLD);
                badgeTv.setTextColor(Color.parseColor("#81C784"));
                card.addView(badgeTv);

                titleTv = new TextView(context);
                titleTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
                titleTv.setTypeface(Typeface.DEFAULT_BOLD);
                titleTv.setTextColor(Color.WHITE);
                titleTv.setPadding(0, dp(context, 2), 0, dp(context, 2));
                card.addView(titleTv);

                metaTv = new TextView(context);
                metaTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
                metaTv.setTextColor(Color.parseColor("#AAAAAA"));
                card.addView(metaTv);
            }

            VideoTagRecord item = items.get(position);
            String badgeLine = item.subBadge;
            if (!item.yearTag.isEmpty()) badgeLine += "   •   " + item.yearTag;
            if (!item.smartTagsCsv.isEmpty()) badgeLine += "   •   " + item.smartTagsCsv;
            badgeTv.setText(badgeLine);

            titleTv.setText(item.title.isEmpty() ? item.videoId : item.title);
            metaTv.setText(item.channelName.isEmpty() ? "Indexing channel..." : item.channelName);
            return card;
        }
    }

    private static View createPill(Context context, String text, boolean active, View.OnClickListener listener) {
        TextView tv = new TextView(context);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setTextColor(active ? Color.BLACK : Color.WHITE);
        tv.setPadding(dp(context, 12), dp(context, 6), dp(context, 12), dp(context, 6));
        tv.setGravity(Gravity.CENTER);

        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(context, 18));
        bg.setColor(active ? Color.parseColor("#E0E0E0") : Color.parseColor("#222222"));
        bg.setStroke(dp(context, 1), Color.parseColor("#444444"));
        tv.setBackground(bg);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd(dp(context, 8));
        tv.setLayoutParams(lp);
        tv.setOnClickListener(listener);
        return tv;
    }
}
