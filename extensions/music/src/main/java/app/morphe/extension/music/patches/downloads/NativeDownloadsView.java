/*
 * Copyright 2026 Morphe.
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.music.patches.downloads;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
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
import android.net.Uri;
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
     */
    public static void show(@Nullable Activity activity) {
        if (activity == null) {
            activity = Utils.getActivity();
        }
        if (activity == null) return;

        isOfflineScreenExplicitlyActive = true;
        attachedActivity = new WeakReference<>(activity);

        final Activity act = activity;
        Utils.runOnMainThreadNowOrLater(() -> {
            setupGlobalLayoutWatcher(act);
            attachOrUpdate(act);
        });
    }

    /**
     * Hides the native downloads view when switching away from the offline screen.
     */
    public static void hide() {
        isOfflineScreenExplicitlyActive = false;
        Utils.runOnMainThreadNowOrLater(() -> {
            removeInjectedView();
            restoreEmptyState();
            Logger.printDebug(() -> "NativeDownloadsView: hidden");
        });
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
     * Plays a downloaded track offline via YouTube Music's native player (ExoPlayer),
     * served directly from the private internal app storage via LocalStreamServer.
     */
    public static void playTrack(@NonNull Context context, @NonNull OfflineTrack track,
                                 @Nullable List<OfflineTrack> allTracks, int position) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://music.youtube.com/watch?v=" + track.videoId()));
            intent.setPackage(context.getPackageName());
            context.startActivity(intent);
            Logger.printDebug(() -> "NativeDownloadsView: launched native player for " + track.videoId());
        } catch (Exception ex) {
            Logger.printException(() -> "playTrack failure", ex);
        }
    }

    public static void playTrack(@NonNull Context context, @NonNull OfflineTrack track) {
        playTrack(context, track, null, 0);
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
