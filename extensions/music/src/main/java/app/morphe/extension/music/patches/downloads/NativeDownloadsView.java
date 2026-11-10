/*
 * Copyright 2026 Morphe.
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.music.patches.downloads;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.ShapeDrawable;
import android.graphics.drawable.shapes.OvalShape;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;

/**
 * Renders locally downloaded songs directly inside YouTube Music's library screen.
 * Replaces the empty DRM state view with native YouTube Music styled track rows.
 * Maintains persistent visibility across navigation and app backgrounding.
 */
public final class NativeDownloadsView {

    private static final String VIEW_TAG = "morphe_native_downloads_container";
    private static final int ROW_HEIGHT_DP = 56;
    private static final int THUMBNAIL_DP = 44;
    private static final int CORNER_RADIUS_DP = 4;
    private static final int HORIZONTAL_PADDING_DP = 16;
    private static final int BG_COLOR = 0xFF030303; // YouTube Music dark theme background

    private static final LruCache<String, Bitmap> artworkCache = new LruCache<>(60);
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    private static WeakReference<Activity> attachedActivity = new WeakReference<>(null);
    private static WeakReference<View> currentInjectedView = new WeakReference<>(null);
    private static WeakReference<View> hiddenEmptyStateView = new WeakReference<>(null);
    private static ViewTreeObserver.OnGlobalLayoutListener globalLayoutListener;
    private static volatile boolean isOfflineScreenExplicitlyActive = false;
    private static volatile boolean isSavedListenerRegistered = false;

    private static final class MiniPlayerHolder {
        final View root;
        final View progressBar;
        final ImageView thumbnail;
        final TextView title;
        final TextView artist;
        final ImageButton playPauseBtn;
        volatile String currentTrackPath = "";

        MiniPlayerHolder(View root, View progressBar, ImageView thumbnail,
                         TextView title, TextView artist, ImageButton playPauseBtn) {
            this.root = root;
            this.progressBar = progressBar;
            this.thumbnail = thumbnail;
            this.title = title;
            this.artist = artist;
            this.playPauseBtn = playPauseBtn;
        }
    }

    private static WeakReference<MiniPlayerHolder> currentMiniPlayer = new WeakReference<>(null);
    private static volatile boolean isPlaybackListenerRegistered = false;

    private static final OfflinePlaybackService.PlaybackListener playbackListener =
            (path, title, playing, position, duration) ->
                    mainHandler.post(() -> updateMiniPlayerState(path, title, playing, position, duration));

    private static void ensurePlaybackListener() {
        if (!isPlaybackListenerRegistered) {
            isPlaybackListenerRegistered = true;
            OfflinePlaybackService.addListener(playbackListener);
        }
    }

    private static String normalize(@Nullable CharSequence text) {
        if (text == null) return "";
        return text.toString()
                .replace("İ", "i")
                .replace("I", "i")
                .replace("ı", "i")
                .toLowerCase(Locale.ROOT)
                .trim();
    }

    private NativeDownloadsView() {}

    /**
     * Activates native downloads view for the current activity.
     * Deprecated: Native sideloaded tracks redirection is used instead of custom overlays.
     */
    public static void show(@Nullable Activity activity) {
        // Disabled: Native sideloaded tracks view is used instead of custom view overlays.
    }

    /**
     * Hides the native downloads view when switching away from the offline screen.
     */
    public static void hide() {
        // Disabled.
    }

    /**
     * Installs a persistent layout observer on the decor view so the list never disappears
     * during fragment transactions, app resume, or screen re-entry.
     */
    private static void setupGlobalLayoutWatcher(@NonNull Activity activity) {
        ViewGroup decor = (ViewGroup) activity.getWindow().getDecorView();
        if (decor == null) return;

        if (globalLayoutListener == null) {
            globalLayoutListener = () -> {
                Activity act = attachedActivity.get();
                if (act == null || act.isFinishing() || act.isDestroyed()) {
                    removeGlobalLayoutWatcher(decor);
                    return;
                }

                boolean offlineTabActive = isOfflineScreenExplicitlyActive || isOfflineChipSelected(decor);
                if (offlineTabActive) {
                    View injected = currentInjectedView.get();
                    if (injected == null || !injected.isAttachedToWindow()) {
                        attachOrUpdate(act);
                    }
                } else if (!isOfflineScreenExplicitlyActive) {
                    removeInjectedView();
                    restoreEmptyState();
                }
            };
            decor.getViewTreeObserver().addOnGlobalLayoutListener(globalLayoutListener);
        }
    }

    private static void removeGlobalLayoutWatcher(@Nullable ViewGroup decor) {
        if (decor != null && globalLayoutListener != null) {
            try {
                decor.getViewTreeObserver().removeOnGlobalLayoutListener(globalLayoutListener);
            } catch (Exception ignored) {}
            globalLayoutListener = null;
        }
    }

    private static void attachOrUpdate(@NonNull Activity activity) {
        ViewGroup decor = (ViewGroup) activity.getWindow().getDecorView();
        if (decor == null) return;

        TargetInfo targetInfo = findContentContainer(decor);
        if (targetInfo == null || targetInfo.container == null) {
            return;
        }

        ViewGroup container = targetInfo.container;
        if (targetInfo.emptyStateView != null) {
            targetInfo.emptyStateView.setVisibility(View.GONE);
            hiddenEmptyStateView = new WeakReference<>(targetInfo.emptyStateView);
        }

        View existing = container.findViewWithTag(VIEW_TAG);
        if (existing != null) {
            currentInjectedView = new WeakReference<>(existing);
            return;
        }

        FrameLayout hostLayout = new FrameLayout(activity);
        hostLayout.setTag(VIEW_TAG);
        hostLayout.setBackgroundColor(BG_COLOR);

        ListView listView = new ListView(activity);
        listView.setId(android.R.id.list);
        listView.setDivider(null);
        listView.setDividerHeight(0);
        listView.setBackgroundColor(BG_COLOR);
        listView.setVerticalScrollBarEnabled(false);
        listView.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        listView.setClipToPadding(false);
        listView.setPadding(0, 0, 0, dp(activity, 68));

        DownloadsAdapter adapter = new DownloadsAdapter(activity, new ArrayList<>());
        listView.setAdapter(adapter);

        hostLayout.addView(listView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        View miniPlayer = createMiniPlayer(activity);
        FrameLayout.LayoutParams miniParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM);
        hostLayout.addView(miniPlayer, miniParams);

        container.addView(hostLayout, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        currentInjectedView = new WeakReference<>(hostLayout);

        // Load offline catalogue
        loadTracks(activity, adapter);

        // Auto-refresh when a new download completes
        if (!isSavedListenerRegistered) {
            isSavedListenerRegistered = true;
            LocalDownloadManager.addSavedListener(() -> {
                Activity act = attachedActivity.get();
                if (act != null && !act.isFinishing()) {
                    loadTracks(act, adapter);
                }
            });
        }

        Logger.printDebug(() -> "NativeDownloadsView: attached successfully with native styling");
    }

    private static void removeInjectedView() {
        View view = currentInjectedView.get();
        if (view != null) {
            ViewGroup parent = (ViewGroup) view.getParent();
            if (parent != null) {
                parent.removeView(view);
            }
            currentInjectedView.clear();
        }
        currentMiniPlayer.clear();
    }

    private static void restoreEmptyState() {
        View emptyView = hiddenEmptyStateView.get();
        if (emptyView != null) {
            emptyView.setVisibility(View.VISIBLE);
            hiddenEmptyStateView.clear();
        }
    }

    private static void loadTracks(Activity activity, DownloadsAdapter adapter) {
        Utils.submitOnBackgroundThread(() -> {
            OfflineCatalogue.update(LocalDownloadManager.getDownloadDirectory());
            OfflineCatalogue.Snapshot snapshot = OfflineCatalogue.cached();
            List<OfflineTrack> tracks = snapshot != null ? snapshot.tracks() : new ArrayList<>();
            mainHandler.post(() -> {
                if (activity != null && !activity.isFinishing()) {
                    adapter.setTracks(tracks);
                }
            });
            return null;
        });
    }

    /**
     * Detects if the "İndirilenler" / "Downloads" chip is currently the active selected chip.
     */
    private static boolean isOfflineChipSelected(@NonNull ViewGroup root) {
        TextView chipTv = findChipTextView(root);
        if (chipTv == null) return false;

        if (chipTv.isSelected()) return true;
        if (chipTv.getParent() instanceof View parentView && parentView.isSelected()) return true;

        // In YouTube Music, the selected chip text is black/dark while unselected chips are white
        int color = chipTv.getCurrentTextColor();
        int brightness = (Color.red(color) + Color.green(color) + Color.blue(color)) / 3;
        return brightness < 120;
    }

    @Nullable
    private static TextView findChipTextView(ViewGroup group) {
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child instanceof TextView tv) {
                CharSequence text = tv.getText();
                if (text != null && text.length() > 0) {
                    String str = normalize(text);
                    if (str.equals("indirilenler") || str.equals("downloads")
                            || str.contains("ndirilenler")) {
                        return tv;
                    }
                }
            } else if (child instanceof ViewGroup childGroup) {
                TextView found = findChipTextView(childGroup);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static class TargetInfo {
        final ViewGroup container;
        @Nullable final View emptyStateView;

        TargetInfo(ViewGroup container, @Nullable View emptyStateView) {
            this.container = container;
            this.emptyStateView = emptyStateView;
        }
    }

    @Nullable
    private static TargetInfo findContentContainer(ViewGroup root) {
        // Find empty state view
        for (int i = 0; i < root.getChildCount(); i++) {
            View child = root.getChildAt(i);
            if (child instanceof TextView tv) {
                CharSequence text = tv.getText();
                if (text != null && text.length() > 0) {
                    String str = normalize(text);
                    if (str.contains("burada gösterilir") || str.contains("burada gosterilir")
                            || str.contains("appear here") || str.contains("shown here")
                            || str.contains("gösterilir") || str.contains("gosterilir")
                            || (str.contains("ndirilen") && str.contains("podcast"))
                            || (str.contains("download") && str.contains("podcast"))) {
                        ViewParent parent = tv.getParent();
                        if (parent instanceof ViewGroup emptyGroup) {
                            ViewParent containerParent = emptyGroup.getParent();
                            if (containerParent instanceof ViewGroup containerGroup) {
                                return new TargetInfo(containerGroup, emptyGroup);
                            }
                            return new TargetInfo(emptyGroup, tv);
                        }
                    }
                }
            } else if (child instanceof ViewGroup childGroup) {
                TargetInfo res = findContentContainer(childGroup);
                if (res != null) return res;
            }
        }
        return null;
    }

    /**
     * Plays a downloaded track offline via Morphe's OfflinePlaybackService.
     * Uses Android's native MediaPlayer to read directly from local storage,
     * requiring zero network requests and ensuring 100% offline playback.
     */
    public static void playTrack(@NonNull Context context, @NonNull OfflineTrack track,
                                 @Nullable List<OfflineTrack> allTracks, int position) {
        try {
            List<String> queuePaths = new ArrayList<>();
            int queueIndex = 0;
            if (allTracks != null && !allTracks.isEmpty()) {
                for (OfflineTrack t : allTracks) {
                    if (t.audioFile() != null && t.audioFile().isFile()) {
                        queuePaths.add(t.audioFile().getAbsolutePath());
                    }
                }
                if (track.audioFile() != null) {
                    String currentPath = track.audioFile().getAbsolutePath();
                    queueIndex = Math.max(0, queuePaths.indexOf(currentPath));
                }
            } else if (track.audioFile() != null && track.audioFile().isFile()) {
                queuePaths.add(track.audioFile().getAbsolutePath());
            }

            if (queuePaths.isEmpty()) {
                Logger.printException(() -> "playTrack: audioFile does not exist for " + track.videoId());
                return;
            }

            final int finalQueueIndex = queueIndex;
            OfflinePlaybackService.play(context, track, queuePaths, finalQueueIndex);
            Logger.printDebug(() -> "NativeDownloadsView: started local playback for " + track.videoId()
                    + " (queue: " + queuePaths.size() + ", index: " + finalQueueIndex + ")");
        } catch (Exception ex) {
            Logger.printException(() -> "playTrack failure", ex);
        }
    }

    public static void playTrack(@NonNull Context context, @NonNull OfflineTrack track) {
        playTrack(context, track, null, 0);
    }

    private static View createMiniPlayer(Activity activity) {
        OfflineUi ui = new OfflineUi(activity);

        LinearLayout miniContainer = new LinearLayout(activity);
        miniContainer.setOrientation(LinearLayout.VERTICAL);
        miniContainer.setBackgroundColor(0xFF212121);
        miniContainer.setClickable(true);
        miniContainer.setVisibility(View.GONE);

        // Thin progress indicator bar at the top (2dp)
        View progressBar = new View(activity);
        progressBar.setBackgroundColor(Color.WHITE);
        progressBar.setScaleX(0f);
        progressBar.setPivotX(0f);
        miniContainer.addView(progressBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 2)));

        // Main content row (56dp height)
        LinearLayout contentRow = new LinearLayout(activity);
        contentRow.setOrientation(LinearLayout.HORIZONTAL);
        contentRow.setGravity(Gravity.CENTER_VERTICAL);
        contentRow.setPadding(dp(activity, 12), 0, dp(activity, 8), 0);

        // Ripple background on content row
        contentRow.setBackground(new RippleDrawable(
                ColorStateList.valueOf(0x22FFFFFF),
                null,
                new ColorDrawable(Color.WHITE)));

        // Thumbnail (40x40dp, rounded corners)
        ImageView thumb = new ImageView(activity);
        thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
        final int cornerPx = dp(activity, CORNER_RADIUS_DP);
        thumb.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), cornerPx);
            }
        });
        thumb.setClipToOutline(true);
        thumb.setImageResource(android.R.drawable.ic_media_play);
        LinearLayout.LayoutParams thumbParams = new LinearLayout.LayoutParams(dp(activity, 40), dp(activity, 40));
        thumbParams.setMarginEnd(dp(activity, 12));
        contentRow.addView(thumb, thumbParams);

        // Title & artist column
        LinearLayout textCol = new LinearLayout(activity);
        textCol.setOrientation(LinearLayout.VERTICAL);
        textCol.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(activity);
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        textCol.addView(title);

        TextView artist = new TextView(activity);
        artist.setTextColor(Color.parseColor("#AAAAAA"));
        artist.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        artist.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        artist.setSingleLine(true);
        artist.setEllipsize(TextUtils.TruncateAt.END);
        textCol.addView(artist);

        contentRow.addView(textCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // Previous button
        ImageButton prevBtn = ui.icon("yt_fill_experimental_skip_previous_vd_theme_24", "Previous");
        prevBtn.setOnClickListener(v -> OfflinePlaybackService.skipPrevious(activity));
        contentRow.addView(prevBtn, new LinearLayout.LayoutParams(dp(activity, 40), dp(activity, 40)));

        // Play/Pause button
        ImageButton playPauseBtn = ui.icon("yt_fill_experimental_play_vd_theme_24", "Play/Pause");
        playPauseBtn.setOnClickListener(v -> OfflinePlaybackService.toggle(activity));
        contentRow.addView(playPauseBtn, new LinearLayout.LayoutParams(dp(activity, 44), dp(activity, 44)));

        // Next button
        ImageButton nextBtn = ui.icon("yt_fill_experimental_skip_next_vd_theme_24", "Next");
        nextBtn.setOnClickListener(v -> OfflinePlaybackService.skipNext(activity));
        contentRow.addView(nextBtn, new LinearLayout.LayoutParams(dp(activity, 40), dp(activity, 40)));

        miniContainer.addView(contentRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 56)));

        MiniPlayerHolder holder = new MiniPlayerHolder(miniContainer, progressBar, thumb, title, artist, playPauseBtn);
        miniContainer.setTag(holder);
        currentMiniPlayer = new WeakReference<>(holder);

        ensurePlaybackListener();
        return miniContainer;
    }

    private static void updateMiniPlayerState(String path, String title, boolean playing,
                                              int position, int duration) {
        MiniPlayerHolder holder = currentMiniPlayer.get();
        if (holder == null || !holder.root.isAttachedToWindow()) return;

        if (path == null || path.isEmpty() || title == null || title.isEmpty()) {
            holder.root.setVisibility(View.GONE);
            return;
        }

        holder.root.setVisibility(View.VISIBLE);
        holder.title.setText(title);

        Drawable playPauseDrawable = ResourceUtils.getDrawable(playing
                ? "yt_fill_experimental_pause_vd_theme_24"
                : "yt_fill_experimental_play_vd_theme_24");
        if (playPauseDrawable != null) {
            holder.playPauseBtn.setImageDrawable(playPauseDrawable);
        } else {
            holder.playPauseBtn.setImageResource(playing
                    ? android.R.drawable.ic_media_pause
                    : android.R.drawable.ic_media_play);
        }

        if (duration > 0) {
            float progress = Math.min(1.0f, Math.max(0.0f, (float) position / duration));
            holder.progressBar.setScaleX(progress);
        } else {
            holder.progressBar.setScaleX(0f);
        }

        if (!path.equals(holder.currentTrackPath)) {
            holder.currentTrackPath = path;
            Utils.submitOnBackgroundThread(() -> {
                File audioFile = new File(path);
                OfflineTrack t = OfflineTrack.load(audioFile);
                String artistName = t.displayArtist();
                Bitmap bmp = null;
                File artFile = t.artworkFile();
                if (artFile != null && artFile.isFile()) {
                    bmp = BitmapFactory.decodeFile(artFile.getAbsolutePath());
                }
                final Bitmap finalBmp = bmp;
                mainHandler.post(() -> {
                    MiniPlayerHolder cur = currentMiniPlayer.get();
                    if (cur != null && path.equals(cur.currentTrackPath)) {
                        cur.artist.setText(artistName);
                        if (finalBmp != null) {
                            cur.thumbnail.setImageBitmap(finalBmp);
                        } else {
                            cur.thumbnail.setImageResource(android.R.drawable.ic_media_play);
                        }
                    }
                });
                return null;
            });
        }
    }

    private static String formatDuration(int seconds) {
        int m = seconds / 60;
        int s = seconds % 60;
        return String.format(Locale.US, "%d:%02d", m, s);
    }

    private static int dp(Context context, int value) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value, context.getResources().getDisplayMetrics());
    }

    private static final class DownloadsAdapter extends BaseAdapter {
        private final Context context;
        private List<OfflineTrack> tracks;

        DownloadsAdapter(Context context, List<OfflineTrack> tracks) {
            this.context = context;
            this.tracks = tracks;
        }

        void setTracks(List<OfflineTrack> newTracks) {
            this.tracks = newTracks;
            notifyDataSetChanged();
        }

        @Override
        public int getCount() {
            return tracks.size();
        }

        @Override
        public OfflineTrack getItem(int position) {
            return tracks.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            ViewHolder holder;
            if (convertView == null) {
                int rowHeightPx = dp(context, ROW_HEIGHT_DP);
                int thumbPx = dp(context, THUMBNAIL_DP);
                int padPx = dp(context, HORIZONTAL_PADDING_DP);
                int cornerPx = dp(context, CORNER_RADIUS_DP);

                LinearLayout row = new LinearLayout(context);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(padPx, 0, padPx, 0);
                row.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, rowHeightPx));

                // YouTube Music native bounded touch ripple
                RippleDrawable rowRipple = new RippleDrawable(
                        ColorStateList.valueOf(0x22FFFFFF),
                        null,
                        new ColorDrawable(Color.WHITE));
                row.setBackground(rowRipple);

                // Thumbnail with 4dp rounded corners
                ImageView thumbnail = new ImageView(context);
                thumbnail.setScaleType(ImageView.ScaleType.CENTER_CROP);
                thumbnail.setOutlineProvider(new ViewOutlineProvider() {
                    @Override
                    public void getOutline(View view, Outline outline) {
                        outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), cornerPx);
                    }
                });
                thumbnail.setClipToOutline(true);

                LinearLayout.LayoutParams thumbParams = new LinearLayout.LayoutParams(thumbPx, thumbPx);
                thumbParams.setMarginEnd(padPx);
                row.addView(thumbnail, thumbParams);

                // Title & Subtitle column
                LinearLayout textCol = new LinearLayout(context);
                textCol.setOrientation(LinearLayout.VERTICAL);
                textCol.setGravity(Gravity.CENTER_VERTICAL);
                LinearLayout.LayoutParams textColParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);

                TextView title = new TextView(context);
                title.setTextColor(Color.WHITE);
                title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
                title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
                title.setSingleLine(true);
                title.setEllipsize(TextUtils.TruncateAt.END);
                textCol.addView(title);

                TextView subtitle = new TextView(context);
                subtitle.setTextColor(Color.parseColor("#AAAAAA"));
                subtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
                subtitle.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
                subtitle.setSingleLine(true);
                subtitle.setEllipsize(TextUtils.TruncateAt.END);
                textCol.addView(subtitle);

                row.addView(textCol, textColParams);

                // 3-dot overflow menu
                ImageView menuButton = new ImageView(context);
                Drawable overflowIcon = ResourceUtils.getDrawable("yt_outline_overflow_vertical_vd_theme_24");
                if (overflowIcon != null) {
                    menuButton.setImageDrawable(overflowIcon);
                } else {
                    menuButton.setImageResource(android.R.drawable.ic_menu_more);
                }
                menuButton.setColorFilter(Color.parseColor("#AAAAAA"));
                menuButton.setBackground(new RippleDrawable(
                        ColorStateList.valueOf(0x22FFFFFF),
                        null,
                        new ShapeDrawable(new OvalShape())));

                int menuPad = dp(context, 8);
                menuButton.setPadding(menuPad, menuPad, menuPad, menuPad);
                LinearLayout.LayoutParams menuParams = new LinearLayout.LayoutParams(
                        dp(context, 40), dp(context, 40));
                row.addView(menuButton, menuParams);

                holder = new ViewHolder(row, thumbnail, title, subtitle, menuButton);
                row.setTag(holder);
                convertView = row;
            } else {
                holder = (ViewHolder) convertView.getTag();
            }

            OfflineTrack track = getItem(position);
            holder.title.setText(track.displayTitle());

            String sub = "✓ " + track.displayArtist();
            if (track.durationSeconds() > 0) {
                sub += " • " + formatDuration(track.durationSeconds());
            }
            holder.subtitle.setText(sub);

            loadThumbnail(track, holder.thumbnail);

            convertView.setOnClickListener(v -> playTrack(context, track, tracks, position));

            holder.menuButton.setOnClickListener(v -> {
                Context popupContext = new ContextThemeWrapper(context, android.R.style.Theme_DeviceDefault);
                PopupMenu popup = new PopupMenu(popupContext, v);
                popup.getMenu().add("Play");
                popup.getMenu().add("Delete download");
                popup.setOnMenuItemClickListener(item -> {
                    String itemTitle = item.getTitle().toString();
                    if ("Play".equals(itemTitle)) {
                        playTrack(context, track, tracks, position);
                    } else if ("Delete download".equals(itemTitle)) {
                        deleteTrack(track);
                    }
                    return true;
                });
                popup.show();
            });

            return convertView;
        }

        private void deleteTrack(OfflineTrack track) {
            Utils.submitOnBackgroundThread(() -> {
                if (track.audioFile() != null) track.audioFile().delete();
                if (track.artworkFile() != null) track.artworkFile().delete();
                if (track.audioFile() != null && track.audioFile().getParentFile() != null) {
                    File json = new File(track.audioFile().getParentFile(), track.videoId() + ".json");
                    if (json.isFile()) json.delete();
                }

                OfflineCatalogue.clear();
                loadTracks(Utils.getActivity(), this);
                return null;
            });
        }

        private void loadThumbnail(OfflineTrack track, ImageView imageView) {
            Bitmap cached = artworkCache.get(track.videoId());
            if (cached != null) {
                imageView.setImageBitmap(cached);
                return;
            }

            File artFile = track.artworkFile();
            if (artFile != null && artFile.isFile()) {
                Utils.submitOnBackgroundThread(() -> {
                    try {
                        Bitmap bmp = BitmapFactory.decodeFile(artFile.getAbsolutePath());
                        if (bmp != null) {
                            artworkCache.put(track.videoId(), bmp);
                            mainHandler.post(() -> imageView.setImageBitmap(bmp));
                        }
                    } catch (Exception ignored) {}
                    return null;
                });
            } else {
                imageView.setImageResource(android.R.drawable.ic_media_play);
            }
        }
    }

    private static final class ViewHolder {
        final View root;
        final ImageView thumbnail;
        final TextView title;
        final TextView subtitle;
        final ImageView menuButton;

        ViewHolder(View root, ImageView thumbnail, TextView title, TextView subtitle, ImageView menuButton) {
            this.root = root;
            this.thumbnail = thumbnail;
            this.title = title;
            this.subtitle = subtitle;
            this.menuButton = menuButton;
        }
    }
}
