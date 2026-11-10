/*
 * Copyright 2026 Morphe.
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.music.patches.downloads;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
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
import app.morphe.extension.shared.Utils;

/**
 * Native view that displays locally downloaded tracks directly inside YouTube Music's
 * library screen when the offline browse tab (FEmusic_offline) is active.
 * Replaces the empty state overlay with native song rows that play via the app's native player.
 */
public final class NativeDownloadsView {

    private static final String VIEW_TAG = "morphe_native_downloads_container";
    private static final int THUMBNAIL_DP = 48;
    private static final int ROW_HEIGHT_DP = 64;

    private static final LruCache<String, Bitmap> artworkCache = new LruCache<>(50);
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    private static WeakReference<View> currentInjectedView = new WeakReference<>(null);
    private static WeakReference<View> hiddenEmptyStateView = new WeakReference<>(null);
    private static volatile boolean isShowing = false;

    private NativeDownloadsView() {}

    /**
     * Shows downloaded songs directly inside YouTube Music's current screen container.
     */
    public static void show(@Nullable Activity activity) {
        if (activity == null) {
            activity = Utils.getActivity();
        }
        if (activity == null) return;

        isShowing = true;
        final Activity act = activity;
        Utils.runOnMainThreadNowOrLater(() -> {
            attachToActivity(act);
            // Retry in case view hierarchy is still animating or rendering
            mainHandler.postDelayed(() -> { if (isShowing) attachToActivity(act); }, 100);
            mainHandler.postDelayed(() -> { if (isShowing) attachToActivity(act); }, 300);
            mainHandler.postDelayed(() -> { if (isShowing) attachToActivity(act); }, 600);
        });
    }

    /**
     * Hides the native downloads view when navigating away from the offline tab.
     */
    public static void hide() {
        Utils.runOnMainThreadNowOrLater(() -> {
            isShowing = false;
            View view = currentInjectedView.get();
            if (view != null) {
                ViewGroup parent = (ViewGroup) view.getParent();
                if (parent != null) {
                    parent.removeView(view);
                }
                currentInjectedView.clear();
            }

            View hiddenEmpty = hiddenEmptyStateView.get();
            if (hiddenEmpty != null) {
                hiddenEmpty.setVisibility(View.VISIBLE);
                hiddenEmptyStateView.clear();
            }

            Logger.printDebug(() -> "NativeDownloadsView: hidden and restored state");
        });
    }

    private static void attachToActivity(@NonNull Activity activity) {
        if (!isShowing) return;

        View existing = currentInjectedView.get();
        if (existing != null && existing.isAttachedToWindow()) {
            return;
        }

        ViewGroup decor = (ViewGroup) activity.getWindow().getDecorView();
        TargetInfo targetInfo = findContentContainer(decor);
        if (targetInfo == null || targetInfo.container == null) {
            Logger.printDebug(() -> "NativeDownloadsView: targetContainer not found");
            return;
        }

        ViewGroup targetContainer = targetInfo.container;
        if (targetInfo.emptyStateView != null) {
            targetInfo.emptyStateView.setVisibility(View.GONE);
            hiddenEmptyStateView = new WeakReference<>(targetInfo.emptyStateView);
        }

        // Remove any stale view
        View old = targetContainer.findViewWithTag(VIEW_TAG);
        if (old != null) {
            targetContainer.removeView(old);
        }

        FrameLayout container = new FrameLayout(activity);
        container.setTag(VIEW_TAG);
        container.setBackgroundColor(Color.BLACK);

        ListView listView = new ListView(activity);
        listView.setDivider(null);
        listView.setDividerHeight(0);
        listView.setBackgroundColor(Color.BLACK);

        DownloadsAdapter adapter = new DownloadsAdapter(activity, new ArrayList<>());
        listView.setAdapter(adapter);

        container.addView(listView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        targetContainer.addView(container, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        currentInjectedView = new WeakReference<>(container);

        // Load tracks asynchronously
        loadTracks(activity, adapter);

        // Refresh on new downloads
        LocalDownloadManager.addSavedListener(() -> {
            if (isShowing) {
                loadTracks(activity, adapter);
            }
        });

        Logger.printDebug(() -> "NativeDownloadsView: successfully attached to screen");
    }

    private static void loadTracks(Activity activity, DownloadsAdapter adapter) {
        Utils.submitOnBackgroundThread(() -> {
            OfflineCatalogue.update(LocalDownloadManager.getDownloadDirectory());
            OfflineCatalogue.Snapshot snapshot = OfflineCatalogue.cached();
            List<OfflineTrack> tracks = snapshot != null ? snapshot.tracks() : new ArrayList<>();
            mainHandler.post(() -> {
                if (isShowing && activity != null && !activity.isFinishing()) {
                    adapter.setTracks(tracks);
                }
            });
            return null;
        });
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
        // Priority 1: Locate the empty state message TextView ("burada gösterilir" / "appear here")
        TargetInfo fromEmpty = findFromEmptyState(root);
        if (fromEmpty != null) {
            return fromEmpty;
        }

        // Priority 2: Fallback to finding the main browse content frame
        return null;
    }

    @Nullable
    private static TargetInfo findFromEmptyState(ViewGroup root) {
        for (int i = 0; i < root.getChildCount(); i++) {
            View child = root.getChildAt(i);
            if (child instanceof TextView tv) {
                CharSequence text = tv.getText();
                if (text != null && text.length() > 0) {
                    String str = text.toString().toLowerCase(Locale.ROOT);
                    if (str.contains("burada gösterilir") || str.contains("appear here")
                            || str.contains("shown here") || str.contains("gösterilir")
                            || (str.contains("indirilen") && str.contains("podcast"))
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
                TargetInfo res = findFromEmptyState(childGroup);
                if (res != null) return res;
            }
        }
        return null;
    }

    /**
     * Plays a downloaded track using YouTube Music's native player and miniplayer.
     */
    public static void playTrack(@NonNull Context context, @NonNull OfflineTrack track) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://music.youtube.com/watch?v=" + track.videoId()));
            intent.setPackage(context.getPackageName());
            if (!(context instanceof Activity)) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            }
            context.startActivity(intent);
            Logger.printDebug(() -> "NativeDownloadsView: launched native playback for " + track.videoId());
        } catch (Exception ex) {
            Logger.printException(() -> "playTrack failure", ex);
        }
    }

    private static String formatDuration(int seconds) {
        int m = seconds / 60;
        int s = seconds % 60;
        return String.format(Locale.US, "%d:%02d", m, s);
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
                int rowHeightPx = (int) TypedValue.applyDimension(
                        TypedValue.COMPLEX_UNIT_DIP, ROW_HEIGHT_DP, context.getResources().getDisplayMetrics());
                int thumbPx = (int) TypedValue.applyDimension(
                        TypedValue.COMPLEX_UNIT_DIP, THUMBNAIL_DP, context.getResources().getDisplayMetrics());
                int padPx = (int) TypedValue.applyDimension(
                        TypedValue.COMPLEX_UNIT_DIP, 12, context.getResources().getDisplayMetrics());

                LinearLayout row = new LinearLayout(context);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(padPx, 0, padPx, 0);
                row.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, rowHeightPx));

                ImageView thumbnail = new ImageView(context);
                thumbnail.setScaleType(ImageView.ScaleType.CENTER_CROP);
                LinearLayout.LayoutParams thumbParams = new LinearLayout.LayoutParams(thumbPx, thumbPx);
                thumbParams.setMarginEnd(padPx);
                row.addView(thumbnail, thumbParams);

                LinearLayout textCol = new LinearLayout(context);
                textCol.setOrientation(LinearLayout.VERTICAL);
                textCol.setGravity(Gravity.CENTER_VERTICAL);
                LinearLayout.LayoutParams textColParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);

                TextView title = new TextView(context);
                title.setTextColor(Color.WHITE);
                title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
                title.setTypeface(Typeface.DEFAULT_BOLD);
                title.setSingleLine(true);
                title.setEllipsize(TextUtils.TruncateAt.END);
                textCol.addView(title);

                TextView subtitle = new TextView(context);
                subtitle.setTextColor(Color.parseColor("#AAAAAA"));
                subtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
                subtitle.setSingleLine(true);
                subtitle.setEllipsize(TextUtils.TruncateAt.END);
                textCol.addView(subtitle);

                row.addView(textCol, textColParams);

                ImageView menuButton = new ImageView(context);
                menuButton.setImageResource(android.R.drawable.ic_menu_more);
                menuButton.setColorFilter(Color.parseColor("#AAAAAA"));
                int menuPad = (int) TypedValue.applyDimension(
                        TypedValue.COMPLEX_UNIT_DIP, 8, context.getResources().getDisplayMetrics());
                menuButton.setPadding(menuPad, menuPad, menuPad, menuPad);
                LinearLayout.LayoutParams menuParams = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
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

            // Load artwork
            loadThumbnail(track, holder.thumbnail);

            // Row click -> Play natively
            convertView.setOnClickListener(v -> playTrack(context, track));

            // Menu click -> Options
            holder.menuButton.setOnClickListener(v -> {
                PopupMenu popup = new PopupMenu(context, v);
                popup.getMenu().add("Play");
                popup.getMenu().add("Delete download");
                popup.setOnMenuItemClickListener(item -> {
                    String itemTitle = item.getTitle().toString();
                    if ("Play".equals(itemTitle)) {
                        playTrack(context, track);
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
                File json = new File(track.audioFile().getParentFile(), track.videoId() + ".json");
                if (json.isFile()) json.delete();

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
